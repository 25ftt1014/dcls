package edu.pb.dcls;

import javafx.application.Platform;
import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fetches and caches OSM raster tiles.
 *
 * <p>Everything that outlives a frame lives here: the memory LRU, the on-disk cache,
 * in-flight bookkeeping, failure state and its retry schedule, the worker pool,
 * per-host pacing and the global back-off. MapRenderer decides <em>what</em> a frame
 * wants; this class owns getting it.
 *
 * <h2>Why requests are tied to a view, not to a moment</h2>
 * Painting used to ask for every tile it needed, every frame, and each request was
 * queued on a pool that never forgot it. A drag or zoom storm then buried the view the
 * user was looking at behind thousands of requests for views they had already left —
 * and because a tile was marked "in flight" the moment it was <em>queued</em>, those
 * tiles could not even be re-requested. The map greyed out and never recovered.
 *
 * <p>So every load carries the {@code generation} of the view that asked for it. A view
 * change bumps the generation: work that has not started is dropped, work in flight is
 * cancelled, and its in-flight mark is released so the tile can be asked for again.
 * Waiting is also debounced, so a gesture that is still moving never plans fetches at
 * all — only the settled view does.
 *
 * <p>Two seams make this testable without a network or a JavaFX toolkit:
 * {@link Source} supplies bytes for a URL, and the "a tile arrived, repaint" callback is
 * injected rather than hard-wired to {@link Platform#runLater}.
 */
final class TileLoader {

    /** Supplies tile bytes for a URL. Implementations may throw {@link TileHttpException}
     *  for a non-200 response, or {@link IOException} for a transport failure. */
    interface Source {
        byte[] fetch(String url) throws IOException, InterruptedException;

        /** Cancels every fetch currently running. Called when the view that wanted
         *  them has been abandoned; whatever the new view still needs is re-requested. */
        default void cancelAll() { }
    }

    /** Builds the URL for a tile on a given subdomain (0 is the first choice). */
    interface UrlBuilder {
        String url(int z, int x, int y, int hostShift);
    }

    /** Non-200 tile response, carrying the status and any Retry-After hint. */
    static final class TileHttpException extends IOException {
        private final int code;
        private final String retryAfter;

        TileHttpException(int code, String retryAfter) {
            super("HTTP " + code);
            this.code = code;
            this.retryAfter = retryAfter;
        }

        int code() { return code; }
        String retryAfter() { return retryAfter; }
    }

    /** The loader the app paints through. Its notifier hops to the FX thread; tests
     *  inject a fake source and a direct notifier instead. */
    static final TileLoader DEFAULT = new TileLoader(
            MapRenderer::osmUrl, new HttpSource(),
            () -> Platform.runLater(MapRenderer::fireTilesUpdated),
            MapRenderer.currentScopeName(), 320, Debounce.DEFAULT_MS);

    /** The shared HTTP client, also used by the place search so both tile and geocode
     *  traffic share one pool, proxy and connect timeout. */
    static java.net.http.HttpClient httpClient() {
        return DEFAULT.source instanceof HttpSource http ? http.client : null;
    }

    // ---- policy knobs, all package-private so tests can see the contract ----

    /** How long a view must hold still before its tiles are requested. A gesture that
     *  is still moving plans nothing; the frames it paints meanwhile come from cache
     *  and the parent-tile fallback, so the map is never blank. */
    static final class Debounce {
        static final long DEFAULT_MS = 120;

        private Debounce() { }
    }

    /** Retries for one tile before it is put on the long, per-tile cooldown. */
    static final int MAX_TRIES = 3;

    /** Back-off between those tries: 500ms, 1s, 2s. */
    static long retryDelayMillis(int attempt) {
        long delay = 500L << Math.max(0, Math.min(attempt, 3));
        return Math.min(delay, 2_000L);
    }

    /** After the retries, a failed tile is left alone for this long. Short enough that
     *  the map heals on its own, long enough not to hammer a struggling server. */
    static final long TILE_COOLDOWN_MS = 10_000;

    /** How often to re-check while a failure notice is up. The old single 65s wake-up
     *  meant a map could stay broken for over a minute, or forever if the failures
     *  re-armed it faster than it fired. */
    static final long RECOVERY_SWEEP_MS = 10_000;

    /** Requests that may wait to start. Beyond this the oldest waiting request is
     *  dropped: a full queue means the view moved on, and its work is worthless. */
    static final int MAX_PENDING = 512;

    /** Simultaneous tile requests per OSM subdomain. */
    static final int PER_HOST_CONCURRENCY = 6;

    /** Worker threads. Sized to the three subdomains at the per-host cap. */
    static final int WORKER_THREADS = 6;

    /** Failures per window before the loader logs a summary instead of staying quiet. */
    static final int FAILURE_SPIKE = 20;
    static final long FAILURE_WINDOW_MS = 10_000;

    private final UrlBuilder urlBuilder;
    private final Source source;
    private final Runnable tileArrived;
    private final String cacheScope;
    private final int memoryCapacity;
    private final long debounceMs;

    /** Memory cache, least-recently-used. Ordered map under its own lock. */
    private final Map<String, Image> mem = Collections.synchronizedMap(
            new LinkedHashMap<>(384, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Image> oldest) {
                    return size() > memoryCapacity;
                }
            });

    /** Tiles this loader knows about and their failure state. Absent means "no failures
     *  yet"; {@link State#GONE} means the server says there is no such tile. */
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** Requests waiting to start, oldest first, each tagged with the view generation
     *  that wanted it. Bounded, with drop-oldest. */
    private final Deque<Pending> waiting = new ArrayDeque<>();

    /** Tiles currently being fetched, so a tile is only loaded once at a time and its
     *  in-flight mark is released exactly once. */
    private final Map<String, Boolean> loading = new ConcurrentHashMap<>();

    /** Bumped by every view change; work from an older generation is stale by definition. */
    private final AtomicLong generation = new AtomicLong();

    /** Keys the view that most recently changed wants. Read by {@link #cancelStale} to
     *  decide which running fetches are still worth finishing. */
    private volatile Set<String> currentTiles = Set.of();

    private ScheduledFuture<?> pendingPlan;

    private final AtomicBoolean urlLogged = new AtomicBoolean();
    private final AtomicInteger droppedFromQueue = new AtomicInteger();
    private final AtomicInteger cancelledStale = new AtomicInteger();
    private final AtomicInteger failureWindow = new AtomicInteger();
    private final AtomicLong failureWindowStart = new AtomicLong(System.currentTimeMillis());
    private volatile boolean failureSpikeLogged;

    /** Minimum spacing between fetch starts, one slot per OSM subdomain so the hosts
     *  progress in parallel at the same per-host rate a single slot allowed. */
    private final AtomicLong[] nextFetchAt = {new AtomicLong(), new AtomicLong(), new AtomicLong()};
    private final Semaphore[] hostPermits = {new Semaphore(PER_HOST_CONCURRENCY),
            new Semaphore(PER_HOST_CONCURRENCY), new Semaphore(PER_HOST_CONCURRENCY)};

    /** Set when the server asks us to slow down (429/503 + Retry-After). No fetch
     *  starts before this instant. */
    private volatile long globalBackoffUntil;

    private final ExecutorService workers = Executors.newFixedThreadPool(WORKER_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "dcls-tiles");
        thread.setDaemon(true);
        // Nothing a tile task does may take the pool down with it.
        thread.setUncaughtExceptionHandler((t, error) ->
                System.out.println("[dcls-tiles] worker " + t.getName() + " died: " + describe(error)));
        return thread;
    });

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dcls-tiles-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    /** Per-tile failure bookkeeping. */
    private static final class State {
        /** The server confirmed there is no such tile; never fetched again. */
        static final State GONE = new State(Integer.MAX_VALUE, 0);

        final int tries;
        final long cooldownUntil;

        State(int tries, long cooldownUntil) {
            this.tries = tries;
            this.cooldownUntil = cooldownUntil;
        }
    }

    /** A request waiting for a worker, tagged with the view that wanted it. */
    private record Pending(String key, int z, int x, int y, long generation) { }

    /** Last tile-failure class, for the on-canvas notice: offline, denied (401/403),
     *  throttled (429), error, or null. Cleared by any successful tile. */
    private volatile String problem;
    private volatile long retryAfterAt;
    private volatile ScheduledFuture<?> sweep;

    TileLoader(UrlBuilder urlBuilder, Source source, Runnable tileArrived,
               String cacheScope, int memoryCapacity) {
        this(urlBuilder, source, tileArrived, cacheScope, memoryCapacity, Debounce.DEFAULT_MS);
    }

    TileLoader(UrlBuilder urlBuilder, Source source, Runnable tileArrived,
               String cacheScope, int memoryCapacity, long debounceMs) {
        this.urlBuilder = urlBuilder;
        this.source = source;
        this.tileArrived = tileArrived;
        this.cacheScope = cacheScope;
        this.memoryCapacity = memoryCapacity;
        this.debounceMs = debounceMs;
    }

    // ---- what the paint thread asks for ----

    /** Cached tile image, or null on a miss. Memory-only on the calling thread: disk
     *  decode and fetching never block a paint, so fast pans can't hitch on I/O. */
    Image request(int z, int x, int y) {
        String key = key(z, x, y);
        Image hit = mem.get(key);
        if (hit != null) return hit;
        return null;
    }

    /** Cached tile image without blocking (memory only). Safe for fallback painting on
     *  every frame, and never decodes on the calling thread. */
    Image peek(int z, int x, int y) {
        String key = key(z, x, y);
        Image hit = mem.get(key);
        if (hit != null && !hit.isError()) return hit;
        return null;
    }

    /** True while the tile is deliberately not being fetched: it failed and is cooling
     *  down, or the server says it does not exist. */
    boolean coolingDown(int z, int x, int y) {
        State state = states.get(key(z, x, y));
        if (state == null) return false;
        if (state == State.GONE) return true;
        if (state.cooldownUntil > System.currentTimeMillis()) return true;
        states.remove(key(z, x, y), state);
        return false;
    }

    String problem() { return problem; }

    long retryAfterAt() { return retryAfterAt; }

    /** Tiles waiting to start: the queue's depth, for tests and the console. */
    int queued() { synchronized (waiting) { return waiting.size(); } }

    /** Requests dropped or cancelled because their view moved on. */
    int staleDropped() { return droppedFromQueue.get() + cancelledStale.get(); }

    /**
     * The view changed: this is the only place fetches are planned from.
     *
     * <p>Bumps the generation (which invalidates queued and in-flight work from the
     *  view the user just left, cancelling what is still running and releasing its
     *  in-flight marks), then waits for the view to hold still before planning. The
     *  tiles themselves are asked for here rather than per frame, so a moving gesture
     *  costs nothing but a reschedule.
     */
    void viewChanged(int z, java.util.Collection<int[]> tiles) {
        // A steady view repeats every frame; re-planning it would do nothing but churn.
        String signature = viewSignature(tiles);
        if (signature.equals(lastView)) return;
        lastView = signature;
        Set<String> wanted = new LinkedHashSet<>();
        for (int[] tile : tiles) wanted.add(key(tile[0], tile[1], tile[2]));
        currentTiles = wanted;
        long mine = generation.incrementAndGet();
        cancelStale();
        List<int[]> snapshot = new ArrayList<>(tiles);
        synchronized (waiting) {
            if (pendingPlan != null) pendingPlan.cancel(false);
            pendingPlan = scheduler.schedule(() -> plan(mine, snapshot), debounceMs, TimeUnit.MILLISECONDS);
        }
    }

    /** Cheap identity for a view, so a repaint of the same tiles plans nothing. */
    private static String viewSignature(java.util.Collection<int[]> tiles) {
        StringBuilder signature = new StringBuilder();
        for (int[] tile : tiles) {
            signature.append(tile[0]).append('/').append(tile[1]).append('/').append(tile[2]).append(',');
        }
        return signature.toString();
    }

    /** Starts the settled view's tiles, dropping anything still waiting from an older
     *  view. Runs on the scheduler thread. */
    private void plan(long forGeneration, List<int[]> tiles) {
        if (forGeneration != generation.get()) return;
        List<Pending> wanted = new ArrayList<>();
        for (int[] tile : tiles) {
            String key = key(tile[0], tile[1], tile[2]);
            if (mem.get(key) != null) continue;
            if (coolingDown(tile[0], tile[1], tile[2])) continue;
            wanted.add(new Pending(key, tile[0], tile[1], tile[2], forGeneration));
        }
        if (wanted.isEmpty()) return;
        // Deepest level first: the visible tiles matter more than their parents.
        wanted.sort((a, b) -> Integer.compare(b.z(), a.z()));
        synchronized (waiting) {
            for (Pending pending : wanted) {
                if (waiting.size() >= MAX_PENDING) {
                    Pending dropped = waiting.pollFirst();
                    if (dropped != null) {
                        loading.remove(dropped.key());
                        droppedFromQueue.incrementAndGet();
                    }
                }
                enqueue(pending);
            }
        }
        drain();
    }

    /** Queues one tile for the current view, ignoring the debounce. Used by the retry
     *  ladder and the recovery sweep, which are already their own schedule. */
    private void retry(String key) {
        String[] parts = key.split("/");
        if (parts.length != 3) return;
        Pending pending = new Pending(key, Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), generation.get());
        if (mem.get(key) != null) return;
        synchronized (waiting) {
            while (waiting.size() >= MAX_PENDING) {
                Pending dropped = waiting.pollFirst();
                if (dropped == null) break;
                loading.remove(dropped.key());
                droppedFromQueue.incrementAndGet();
            }
            enqueue(pending);
        }
        drain();
    }

    /** Adds one request, taking its in-flight mark here so it is never queued twice. */
    private void enqueue(Pending pending) {
        if (loading.putIfAbsent(pending.key(), Boolean.TRUE) != null) return;
        waiting.addLast(pending);
    }

    /** Hands queued work to the pool.
 *
 *  <p>At most {@link #WORKER_THREADS} tasks are ever submitted at once, so the pool's
 *  own queue stays effectively empty: work is handed straight to a free worker. That is
 *  the point — an unbounded queue is what let stale requests pile up behind live ones,
 *  and no amount of cancelling would empty it once the tasks were inside. */
    private void drain() {
        while (submitted.get() < WORKER_THREADS) {
            Pending pending;
            synchronized (waiting) {
                pending = waiting.pollFirst();
            }
            if (pending == null) return;
            if (pending.generation() != generation.get()) {
                // The view moved on while this waited: it is worthless, and its
                // in-flight mark must go so the new view can ask again.
                loading.remove(pending.key());
                droppedFromQueue.incrementAndGet();
                continue;
            }
            submitted.incrementAndGet();
            workers.submit(() -> {
                try {
                    run(pending);
                } catch (Throwable unexpected) {
                    // One tile must never take a worker down with the rest of the pool.
                    System.out.println("[dcls-tiles] tile task escaped: " + describe(unexpected));
                } finally {
                    submitted.decrementAndGet();
                    drain();
                }
            });
        }
    }

    /** Cancels work belonging to a view the user has left: requests still waiting are
     *  dropped and lose their in-flight marks, and fetches already running are
     *  cancelled unless the new view still wants them. */
    private void cancelStale() {
        Set<String> wanted = currentTiles;
        synchronized (waiting) {
            waiting.removeIf(pending -> {
                if (wanted.contains(pending.key())) return false;
                loading.remove(pending.key());
                droppedFromQueue.incrementAndGet();
                return true;
            });
        }
        if (!running.isEmpty()) {
            for (String key : List.copyOf(running.keySet())) {
                if (wanted.contains(key)) continue;
                cancelledStale.incrementAndGet();
            }
            source.cancelAll();
        }
    }

    /** Keys of the fetches currently running, so a view change can see what to cancel. */
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    /** Tasks handed to the pool. Capped at the pool size, which keeps the pool's own
     *  queue from ever becoming a backlog. */
    private final AtomicInteger submitted = new AtomicInteger();

    /** The view that last asked for tiles. A paint repeats itself every frame, and
     *  re-planning an identical view would churn the scheduler for nothing. */
    private volatile String lastView;

    /** One tile, end to end: disk cache, then network, then cache and notify. */
    private void run(Pending pending) {
        String key = pending.key();
        try {
            if (pending.generation() != generation.get()) {
                cancelledStale.incrementAndGet();
                return;
            }
            if (mem.get(key) != null) return;
            Image disk = decodeFile(diskPath(key));
            if (disk != null) {
                mem.put(key, disk);
                succeed(key);
                return;
            }
            fetch(pending);
        } catch (Throwable unexpected) {
            // A tile task must never take a worker down with it.
            fail(key, "error", "unexpected " + describe(unexpected), TILE_COOLDOWN_MS);
        } finally {
            loading.remove(key);
        }
    }

    private void fetch(Pending pending) {
        String key = pending.key();
        int z = pending.z(), x = pending.x(), y = pending.y();
        int host = MapRenderer.osmHostIndex(x, y, 0);
        if (urlLogged.compareAndSet(false, true)) {
            System.out.println("[dcls-tiles] provider: OSM Standard (no key)");
        }
        long waited = globalBackoffUntil - System.currentTimeMillis();
        if (waited > 0) sleep(waited);
        try {
            hostPermits[host].acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (pending.generation() != generation.get()) { cancelledStale.incrementAndGet(); return; }
            pace(host);
            running.put(key, Boolean.TRUE);
            try {
                store(key, download(z, x, y, 0));
            } finally {
                running.remove(key);
            }
        } catch (TileHttpException e) {
            handleHttpFailure(key, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // Transport failure: retry once via the next subdomain (covers per-host
            // DNS/adblock flakiness) before recording the failure.
            if (pending.generation() != generation.get()) return;
            try {
                int fallback = MapRenderer.osmHostIndex(x, y, 1);
                hostPermits[fallback].acquire();
                try {
                    pace(fallback);
                    running.put(key, Boolean.TRUE);
                    try {
                        store(key, download(z, x, y, 1));
                        System.out.println("[dcls-tiles] " + key + " -> recovered via fallback host");
                    } finally {
                        running.remove(key);
                    }
                } finally {
                    hostPermits[fallback].release();
                }
            } catch (TileHttpException retry) {
                handleHttpFailure(key, retry);
            } catch (InterruptedException retry) {
                Thread.currentThread().interrupt();
            } catch (IOException retry) {
                recordFailure(key, classifyOffline(retry), "host retry: " + describe(retry));
            }
        } catch (Throwable unexpected) {
            fail(key, "error", "unexpected " + describe(unexpected), TILE_COOLDOWN_MS);
        } finally {
            hostPermits[host].release();
        }
    }

    private byte[] download(int z, int x, int y, int hostShift) throws IOException, InterruptedException {
        return source.fetch(urlBuilder.url(z, x, y, hostShift));
    }

    /** Minimum spacing between fetch starts to one host, so bursts never look abusive
     *  while still letting the other subdomains work in parallel. */
    private void pace(int hostIndex) {
        AtomicLong slot = nextFetchAt[Math.floorMod(hostIndex, nextFetchAt.length)];
        long waitUntil = slot.get();
        long now = System.currentTimeMillis();
        if (waitUntil > now) sleep(waitUntil - now);
        slot.set(Math.max(System.currentTimeMillis(), waitUntil) + 150);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(millis, 0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** HTTP statuses, each with its own meaning for retry and for the notice. */
    private void handleHttpFailure(String key, TileHttpException e) {
        int code = e.code();
        if (code == 404) {
            states.put(key, State.GONE);
            fail(key, "error", "HTTP 404 (no such tile)", TILE_COOLDOWN_MS);
            return;
        }
        if (code == 429 || code == 503) {
            // The server is telling us to slow down. Honour it for everyone, not just
            // this tile: hammering through the window is what gets a client blocked.
            long backoff = parseRetryAfter(e.retryAfter());
            backOffEveryone(backoff);
            fail(key, "throttled", "HTTP " + code + " (retry in " + (backoff / 1000) + "s)", backoff);
            return;
        }
        if (code == 401 || code == 403) {
            backOffEveryone(60_000);
            fail(key, "denied", "HTTP " + code, 60_000);
            return;
        }
        recordFailure(key, "error", "HTTP " + code);
    }

    /** Holds every host back for a while, and says so on the notice. */
    private void backOffEveryone(long millis) {
        long until = System.currentTimeMillis() + Math.max(millis, 1_000);
        globalBackoffUntil = Math.max(globalBackoffUntil, until);
        retryAfterAt = Math.max(retryAfterAt, until);
        problem = "throttled";
    }

    /** A failure that is worth retrying: counts against the tile's tries first. */
    private void recordFailure(String key, String kind, String detail) {
        int tries = triesSoFar(key) + 1;
        if (tries < MAX_TRIES) {
            long delay = retryDelayMillis(tries - 1);
            states.put(key, new State(tries, System.currentTimeMillis() + delay));
            problem = kind;
            logFailure(key, detail + " (retry " + tries + " in " + delay + "ms)");
            scheduleRetry(delay, key);
            return;
        }
        fail(key, kind, detail, TILE_COOLDOWN_MS);
    }

    private int triesSoFar(String key) {
        State state = states.get(key);
        return state == null || state == State.GONE ? 0 : state.tries;
    }

    /** A failure that has used up its retries: long cooldown plus a periodic retry. */
    private void fail(String key, String kind, String detail, long cooldownMs) {
        states.put(key, new State(MAX_TRIES, System.currentTimeMillis() + Math.max(cooldownMs, 1_000)));
        problem = kind;
        logFailure(key, detail);
        armRecoverySweep();
    }

    /** Every tile that has ever failed is retried on a timer while the notice is up,
     *  so the map heals by itself instead of waiting for the user to leave and come
     *  back. */
    private void armRecoverySweep() {
        if (sweep != null) return;
        synchronized (this) {
            if (sweep != null) return;
            sweep = scheduler.scheduleAtFixedRate(this::recoverySweep,
                    RECOVERY_SWEEP_MS, RECOVERY_SWEEP_MS, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void recoverySweep() {
        long now = System.currentTimeMillis();
        List<String> due = new ArrayList<>();
        for (Map.Entry<String, State> entry : states.entrySet()) {
            State state = entry.getValue();
            if (state == State.GONE) continue;
            if (state.cooldownUntil <= now) {
                states.remove(entry.getKey(), state);
                due.add(entry.getKey());
            }
        }
        if (due.isEmpty() && problem == null) {
            sweep.cancel(false);
            sweep = null;
            return;
        }
        for (String key : due) {
            // Only the tiles the current view actually wants: a retry sweep must not
            // resurrect work for a map the user has scrolled away from.
            if (currentTiles.contains(key)) retry(key);
        }
        notifyTileArrived();
    }

    /** A tile landed: the failure notice goes away, and so does the recovery sweep. */
    private void succeed(String key) {
        states.remove(key);
        problem = null;
        retryAfterAt = 0;
        notifyTileArrived();
        stopSweepIfIdle();
    }

    private void stopSweepIfIdle() {
        if (problem != null) return;
        synchronized (this) {
            if (sweep != null) { sweep.cancel(false); sweep = null; }
        }
    }

    private void scheduleRetry(long delayMs, String key) {
        scheduler.schedule(() -> {
            State state = states.get(key);
            if (state == null || state.cooldownUntil > System.currentTimeMillis()) return;
            states.remove(key, state);
            if (currentTiles.contains(key)) retry(key);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Per-tile failures are a debug signal; a burst of them is worth a warning. */
    private void logFailure(String key, String detail) {
        if (Boolean.getBoolean("dcls.tile.debug")) {
            System.out.println("[dcls-tiles] " + key + " -> " + detail);
        }
        long now = System.currentTimeMillis();
        if (now - failureWindowStart.get() > FAILURE_WINDOW_MS) {
            failureWindowStart.set(now);
            failureWindow.set(0);
            failureSpikeLogged = false;
        }
        int count = failureWindow.incrementAndGet();
        if (count == FAILURE_SPIKE && !failureSpikeLogged) {
            failureSpikeLogged = true;
            System.out.println("[dcls-tiles] warning: " + count
                    + " tile failures in " + (FAILURE_WINDOW_MS / 1000) + "s ("
                    + droppedFromQueue.get() + " stale requests dropped)");
        }
    }

    /** Repaint notification. Default hops to the FX thread; tests pass a direct call. */
    private void notifyTileArrived() {
        try {
            tileArrived.run();
        } catch (RuntimeException ignored) { }
    }

    static String key(int z, int x, int y) { return z + "/" + x + "/" + y; }

    Path diskPath(String key) {
        return Path.of(System.getProperty("user.home"), ".dcls", "tiles",
                cacheScope, "osm/" + key + ".png");
    }

    /** Decode tile bytes. Null when undecodable. */
    static Image decode(byte[] body) {
        try {
            Image img = new Image(new ByteArrayInputStream(body));
            return img.isError() ? null : img;
        } catch (Exception e) {
            return null;
        }
    }

    /** Decode a cached tile file. Null when missing/undecodable. */
    private Image decodeFile(Path disk) {
        try {
            if (Files.isRegularFile(disk)) return decode(Files.readAllBytes(disk));
        } catch (Exception ignored) { }
        return null;
    }

    /** Cache a downloaded tile; returns false (and records failure) on decode errors. */
    private boolean store(String key, byte[] body) {
        Image img = decode(body);
        if (img == null) {
            fail(key, "error", "decode error", TILE_COOLDOWN_MS);
            return false;
        }
        mem.put(key, img);
        try {
            Path disk = diskPath(key);
            Files.createDirectories(disk.getParent());
            Files.write(disk, body);
        } catch (Exception ignored) { }
        succeed(key);
        return true;
    }

    static String classifyOffline(IOException e) {
        return (e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException
                || e instanceof java.net.NoRouteToHostException
                || e instanceof java.net.http.HttpConnectTimeoutException) ? "offline" : "error";
    }

    static String describe(Throwable e) {
        String detail = e.getClass().getSimpleName();
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            String message = e.getMessage();
            detail += ": " + message.substring(0, Math.min(message.length(), 120));
        }
        return detail;
    }

    /** Retry-After value (delta-seconds or HTTP date) in millis, capped at 10 minutes. */
    static long parseRetryAfter(String value) {
        if (value == null || value.isBlank()) return 60_000;
        try {
            return Math.min(Long.parseLong(value.trim()) * 1000, 600_000);
        } catch (NumberFormatException notSeconds) { }
        try {
            long at = java.time.ZonedDateTime.parse(value.trim(),
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            return Math.min(Math.max(at - System.currentTimeMillis(), 1000), 600_000);
        } catch (Exception ignored) { }
        return 60_000;
    }

    /** The real network source: OSM Standard, HTTP/1.1, five-second connect timeout. */
    static final class HttpSource implements Source {
        private final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).proxy(MapRenderer.tileProxy()).build();

        /** Per-URL handle so a view change can cancel an abandoned fetch. */
        private final Map<String, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();

        @Override
        public byte[] fetch(String url) throws IOException, InterruptedException {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(Duration.ofSeconds(TileLoader.READ_TIMEOUT_MS))
                    .header("User-Agent", TileLoader.USER_AGENT)
                    .GET().build();
            CompletableFuture<java.net.http.HttpResponse<byte[]>> call = client.sendAsync(
                    request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            inFlight.put(url, call);
            try {
                java.net.http.HttpResponse<byte[]> response =
                        call.get(TileLoader.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (response.statusCode() == 200 && response.body().length > 0) {
                    // Truncated bodies decode into blocky half-tiles; never cache them as good.
                    long expected = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (expected >= 0 && expected != response.body().length) {
                        throw new IOException("truncated tile body");
                    }
                    return response.body();
                }
                throw new TileHttpException(response.statusCode(),
                        response.headers().firstValue("Retry-After").orElse(null));
            } catch (java.util.concurrent.ExecutionException failed) {
                if (call.isCancelled()) throw new InterruptedException("cancelled");
                Throwable cause = failed.getCause();
                if (cause instanceof IOException io) throw io;
                throw new IOException(cause == null ? "request failed" : cause.toString());
            } catch (java.util.concurrent.TimeoutException timedOut) {
                call.cancel(true);
                throw new java.net.http.HttpTimeoutException("request timed out");
            } finally {
                inFlight.remove(url);
            }
        }

        /** Cancels every request in flight for a view the user has left. */
        @Override
        public void cancelAll() {
            for (CompletableFuture<?> call : List.copyOf(inFlight.values())) call.cancel(true);
        }
    }

    /** Identifies the app to the tile server, as the OSM usage policy requires. */
    static final String USER_AGENT = "DCLS/1.0 (classroom project)";

    /** Read timeout for a tile, in milliseconds. */
    static final long READ_TIMEOUT_MS = 5_000;
}
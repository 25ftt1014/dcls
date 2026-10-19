package edu.pb.dcls;

import javafx.application.Platform;
import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fetches and caches OSM raster tiles.
 *
 * <p>Everything that survives a view change lives here: the memory LRU, the on-disk
 * cache, which requests are in flight, which have failed and when they may be tried
 * again, the worker pool, per-host pacing and the retry wake-up. MapRenderer owns the
 * painting and decides <em>which</em> tiles a frame wants; this class owns getting them.
 *
 * <p>Two seams make that split testable without a network or a JavaFX toolkit:
 * {@link Source} supplies bytes for a URL, and the "a tile arrived, repaint" callback is
 * injected rather than hard-wired to {@link Platform#runLater}.
 */
final class TileLoader {

    /** Supplies tile bytes for a URL. Implementations may throw {@link TileHttpException}
     *  for a non-200 response, or {@link IOException} for a transport failure. */
    interface Source {
        byte[] fetch(String url) throws IOException, InterruptedException;
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
            MapRenderer.currentScopeName(), 320);

    /** The shared HTTP client, also used by the place search so both tile and geocode
     *  traffic share one pool, proxy and connect timeout. */
    static java.net.http.HttpClient httpClient() {
        return DEFAULT.source instanceof HttpSource http ? http.client : null;
    }

    private final UrlBuilder urlBuilder;
    private final Source source;
    private final Runnable tileArrived;
    private final String cacheScope;
    private final int memoryCapacity;

    /** Memory cache, least-recently-used. Ordered map under its own lock. */
    private final Map<String, Image> mem = Collections.synchronizedMap(
            new LinkedHashMap<>(384, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Image> oldest) {
                    return size() > memoryCapacity;
                }
            });

    /** Keys with a load running or queued. The mark is taken when work is queued and
     *  released when it finishes, so a tile is only ever loaded once at a time. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** Post-failure cooldown deadline per tile: do not try this key before then. */
    private final Map<String, Long> failed = new ConcurrentHashMap<>();

    /** Tiles the server confirms do not exist (404): never retried within a session. */
    private final Set<String> gone = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean urlLogged = new AtomicBoolean();

    /** Minimum spacing between fetch starts, one slot per OSM subdomain so the hosts
     *  progress in parallel at the same per-host rate a single slot allowed. */
    private final AtomicLong[] nextFetchAt = {new AtomicLong(), new AtomicLong(), new AtomicLong()};

    private final ExecutorService workers = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "dcls-tiles");
        thread.setDaemon(true);
        return thread;
    });

    private final ScheduledExecutorService retryTimer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dcls-tiles-retry");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean retryArmed = new AtomicBoolean();

    /** Last tile-failure class, for the on-canvas notice: offline, denied (401/403),
     *  throttled (429), error, or null. */
    private volatile String problem;
    private volatile long retryAfterAt;

    TileLoader(UrlBuilder urlBuilder, Source source, Runnable tileArrived,
               String cacheScope, int memoryCapacity) {
        this.urlBuilder = urlBuilder;
        this.source = source;
        this.tileArrived = tileArrived;
        this.cacheScope = cacheScope;
        this.memoryCapacity = memoryCapacity;
    }

    /** Cached tile image, or null on a miss (queues one background load: disk first,
     *  then network). Memory-only on the calling thread: disk decode and fetching
     *  never block a paint, so fast pans can't hitch on file or network I/O. */
    Image request(int z, int x, int y) {
        String key = key(z, x, y);
        Image hit = mem.get(key);
        if (hit != null) return hit;
        Long retryAt = failed.get(key);
        if (retryAt != null && System.currentTimeMillis() < retryAt) return null;
        if (inFlight.add(key)) {
            problem = null;
            workers.submit(() -> load(key, z, x, y));
        }
        return null;
    }

    /** Cached tile image without blocking (memory only). No cooldown or retry
     *  side effects beyond queueing the normal background load: safe for fallback
     *  painting on every frame, and never decodes on the calling thread. */
    Image peek(int z, int x, int y) {
        String key = key(z, x, y);
        Image hit = mem.get(key);
        if (hit != null && !hit.isError()) return hit;
        request(z, x, y);
        return null;
    }

    /** True while the tile sits out its post-failure cooldown (no fetch in flight). */
    boolean coolingDown(int z, int x, int y) {
        String key = key(z, x, y);
        if (gone.contains(key)) return true;
        Long retryAt = failed.get(key);
        if (retryAt == null) return false;
        if (retryAt > System.currentTimeMillis()) return true;
        failed.remove(key, retryAt);
        return false;
    }

    String problem() { return problem; }

    long retryAfterAt() { return retryAfterAt; }

    /** Background tile load: disk cache first, then one network fetch. Runs off the
     *  UI thread; arrival triggers a repaint through the notifier. */
    private void load(String key, int z, int x, int y) {
        try {
            if (gone.contains(key)) return;
            Image disk = decodeFile(diskPath(key));
            if (disk != null) {
                mem.put(key, disk);
                failed.remove(key);
                clearProblem();
                notifyTileArrived();
                return;
            }
            fetch(key, z, x, y);
        } finally {
            inFlight.remove(key);
        }
    }

    private void fetch(String key, int z, int x, int y) {
        if (urlLogged.compareAndSet(false, true)) {
            System.out.println("[dcls-tiles] provider: OSM Standard (no key)");
        }
        pace(MapRenderer.osmHostIndex(x, y, 0));
        try {
            store(key, download(z, x, y, 0));
        } catch (TileHttpException e) {
            int code = e.code();
            if (code == 404) {
                gone.add(key);
                fail(key, "error", "HTTP 404 (no such tile)", 60_000);
            } else if (code == 429) {
                fail(key, "throttled", "HTTP 429" + (e.retryAfter() == null ? "" : " (retry in " + e.retryAfter() + "s)"),
                        parseRetryAfter(e.retryAfter()));
            } else if (code == 401 || code == 403) {
                fail(key, "denied", "HTTP " + code, 60_000);
            } else {
                fail(key, "error", "HTTP " + code, 60_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(key, "error", "interrupted", 60_000);
        } catch (IOException e) {
            // Transport failure: retry once via the next subdomain (covers per-host
            // DNS/adblock flakiness) before recording the failure.
            try {
                pace(MapRenderer.osmHostIndex(x, y, 1));
                store(key, download(z, x, y, 1));
                System.out.println("[dcls-tiles] " + key + " -> recovered via fallback host");
            } catch (TileHttpException retry) {
                fail(key, "error", "HTTP " + retry.code() + " after host retry", 60_000);
            } catch (InterruptedException retry) {
                Thread.currentThread().interrupt();
                fail(key, "error", "interrupted", 60_000);
            } catch (IOException retry) {
                fail(key, classifyOffline(retry), "host retry: " + describe(retry), 60_000);
            }
        } catch (Exception e) {
            fail(key, "error", describe(e), 60_000);
        } finally {
            inFlight.remove(key);
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
        if (waitUntil > now) {
            try {
                Thread.sleep(waitUntil - now);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        slot.set(Math.max(System.currentTimeMillis(), waitUntil) + 150);
    }

    private void fail(String key, String kind, String detail, long cooldownMs) {
        failed.put(key, System.currentTimeMillis() + Math.max(cooldownMs, 1000));
        problem = kind;
        System.out.println("[dcls-tiles] " + key + " -> " + detail);
        armRetry();
    }

    private void clearProblem() {
        problem = null;
        retryAfterAt = 0;
    }

    /** One repaint after the failure cooldown, so transient blips recover without input. */
    private void armRetry() {
        if (retryArmed.compareAndSet(false, true)) {
            retryTimer.schedule(this::retryFired, 65, TimeUnit.SECONDS);
        }
    }

    private void retryFired() {
        retryArmed.set(false);
        runOnNotifier();
        // Chain another wake-up if any tile still waits out a longer cooldown.
        long now = System.currentTimeMillis();
        long earliest = Long.MAX_VALUE;
        for (Map.Entry<String, Long> entry : List.copyOf(failed.entrySet())) {
            long retryAt = entry.getValue();
            if (retryAt <= now) failed.remove(entry.getKey(), retryAt);
            else if (retryAt < earliest) earliest = retryAt;
        }
        if (earliest != Long.MAX_VALUE && retryArmed.compareAndSet(false, true)) {
            retryTimer.schedule(this::retryFired,
                    Math.max(earliest - System.currentTimeMillis(), 1000), TimeUnit.MILLISECONDS);
        }
    }

    /** Repaint notification. Default hops to the FX thread; tests pass a direct call. */
    private void runOnNotifier() {
        try {
            tileArrived.run();
        } catch (RuntimeException ignored) { }
    }

    private void notifyTileArrived() {
        runOnNotifier();
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
            fail(key, "error", "decode error", 60_000);
            return false;
        }
        mem.put(key, img);
        try {
            Path disk = diskPath(key);
            Files.createDirectories(disk.getParent());
            Files.write(disk, body);
        } catch (Exception ignored) { }
        failed.remove(key);
        clearProblem();
        notifyTileArrived();
        return true;
    }

    static String classifyOffline(IOException e) {
        return (e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException
                || e instanceof java.net.NoRouteToHostException
                || e instanceof java.net.http.HttpConnectTimeoutException) ? "offline" : "error";
    }

    static String describe(Exception e) {
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

        @Override
        public byte[] fetch(String url) throws IOException, InterruptedException {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "DCLS classroom demo")
                    .GET().build();
            java.net.http.HttpResponse<byte[]> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
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
        }
    }
}
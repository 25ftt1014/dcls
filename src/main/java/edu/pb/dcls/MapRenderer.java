package edu.pb.dcls;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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

import static edu.pb.dcls.Domain.Stop;
import static edu.pb.dcls.Domain.TrackingPoint;

/**
 * Real-map rendering over live tiles (OSM Standard default, no key; CARTO Voyager @2x
 * retina when DCLS_CARTO_KEY is set, zoom 3-20). WiFi-first: the disk tile cache covers
 * transient blips. Stateless per call except the shared tile cache: every method takes
 * what it draws, so there is no shared state between screens.
 * Stop.x()/y() carry longitude/latitude (WGS84 degrees).
 */
final class MapRenderer {
    private MapRenderer() { }

    static final double MAP_LON_MIN = 114.43359375, MAP_LON_MAX = 115.13671875;
    static final double MAP_LAT_MIN = 4.56547355071028, MAP_LAT_MAX = 5.09094417503339;

    record ViewBounds(double lonMin, double lonMax, double latMin, double latMax) { }

    static final ViewBounds FULL_MAP = new ViewBounds(MAP_LON_MIN, MAP_LON_MAX, MAP_LAT_MIN, MAP_LAT_MAX);

    // ---- Live Google-style tiles (CARTO Voyager, free with attribution) ----
    static final int TILE_SIZE = 256, LIVE_MIN_Z = 3, LIVE_MAX_Z = 20, LIVE_MAX_TILES = 64;
    private static final String[] TILE_HOSTS = {"a", "b", "c", "d"};
    /** Free CARTO key (no billing, no account): set DCLS_CARTO_KEY. When empty the app
     *  uses OSM Standard automatically and never requests CARTO (CARTO now serves
     *  "API KEY REQUIRED" watermark tiles to keyless callers). */
    static final String CARTO_KEY = System.getenv().getOrDefault("DCLS_CARTO_KEY", "").trim();

    static {
        System.out.println("[dcls-tiles] key configured: " + (CARTO_KEY.isEmpty() ? "no → OSM Standard default" : "yes"));
        purgePoisonedKeylessCache();
    }

    /** One-time cleanup of dead cache weight: watermark keyless scopes, the obsolete
     *  keyed scope, abandoned numeric-zoom layout dirs, and OSRM/geocode files
     *  older than 30 days. Current scopes are never touched. */
    private static void purgePoisonedKeylessCache() {
        try {
            Path tiles = Path.of(System.getProperty("user.home"), ".dcls", "tiles");
            String live = currentScopeName();
            if (!"keyless".equals(live)) deleteTree(tiles.resolve("keyless"));
            if (!"k6d775938".equals(live)) deleteTree(tiles.resolve("k6d775938"));
            if (Files.isDirectory(tiles)) {
                try (var entries = Files.list(tiles)) {
                    entries.filter(p -> Files.isDirectory(p)
                                    && p.getFileName().toString().matches("\\d+"))
                            .forEach(MapRenderer::deleteTree);
                } catch (Exception ignored) { }
            }
            pruneOldFiles(Path.of(System.getProperty("user.home"), ".dcls", "osrm"));
            pruneOldFiles(Path.of(System.getProperty("user.home"), ".dcls", "geocode"));
        } catch (Exception ignored) { }
    }

    /** Scope dir written by {@link #tileDiskPath} for the current config. */
    static String currentScopeName() {
        boolean osm = basemap == Basemap.OSM_STANDARD || CARTO_KEY.isEmpty();
        return (osm ? "osm-v2-" : "")
                + (CARTO_KEY.isEmpty() ? "keyless-v2" : "k" + Integer.toHexString(CARTO_KEY.hashCode()));
    }

    private static void deleteTree(Path root) {
        try {
            if (!Files.isDirectory(root)) return;
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
            }
            System.out.println("[dcls-tiles] purged stale cache " + root.getFileName());
        } catch (Exception ignored) { }
    }

    private static void pruneOldFiles(Path dir) {
        try {
            if (!Files.isDirectory(dir)) return;
            long cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
            final int[] removed = {0};
            try (var walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        if (Files.getLastModifiedTime(p).toMillis() < cutoff
                                && Files.deleteIfExists(p)) removed[0]++;
                    } catch (Exception ignored) { }
                });
            }
            if (removed[0] > 0) System.out.println("[dcls-tiles] pruned " + removed[0] + " old files in " + dir.getFileName());
        } catch (Exception ignored) { }
    }

    /** Tile zoom whose pixels best match the screen resolution for the given span. */
    static int zoomFor(double lonSpan, double viewPx) {
        double pxPerDeg = viewPx / Math.max(lonSpan, 1e-9);
        int z = (int) Math.ceil(Math.log(pxPerDeg * 360 / TILE_SIZE) / Math.log(2));
        return Math.min(LIVE_MAX_Z, Math.max(LIVE_MIN_Z, z));
    }

    static int lonToTileX(double lon, int z) {
        return (int) Math.floor((lon + 180) / 360 * (1 << z));
    }

    static int latToTileY(double lat, int z) {
        double rad = Math.toRadians(Math.min(Math.max(lat, -85.0511), 85.0511));
        return (int) Math.floor((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * (1 << z));
    }

    static double tileXToLon(int x, int z) { return x / Math.pow(2, z) * 360 - 180; }

    static double tileYToLat(int y, int z) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * y / Math.pow(2, z)))));
    }

    static String tileUrl(int z, int x, int y) {
        return tileUrlShifted(z, x, y, 0);
    }

    static String osmUrl(int z, int x, int y, int hostShift) {
        String[] osm = {"a", "b", "c"};
        String host = osm[Math.floorMod(x + y + hostShift, osm.length)];
        return "https://" + host + ".tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png";
    }

    private static String tileUrlShifted(int z, int x, int y, int hostShift) {
        return tileUrlFor(z, x, y, hostShift, basemap);
    }

    private static String tileUrlFor(int z, int x, int y, int hostShift, Basemap provider) {
        if (provider == Basemap.OSM_STANDARD) return osmUrl(z, x, y, hostShift);
        // Never request CARTO without a key: it returns HTTP 200 watermark tiles
        // ("API KEY REQUIRED") which would otherwise be cached as good tiles.
        if (CARTO_KEY.isEmpty()) return osmUrl(z, x, y, hostShift);
        String host = TILE_HOSTS[Math.floorMod(x + y + hostShift, TILE_HOSTS.length)];
        return "https://" + host + ".basemaps.cartocdn.com/rastertiles/voyager/" + z + "/" + x + "/" + y + "@2x.png?key=" + CARTO_KEY;
    }

    private static final Map<String, Image> TILE_MEM = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Image> entry) { return size() > 192; }
    });
    private static final Set<String> TILE_IN_FLIGHT = Collections.synchronizedSet(new HashSet<>());
    private static final AtomicBoolean TILE_URL_LOGGED = new AtomicBoolean();
    private static final AtomicLong NEXT_FETCH_AT = new AtomicLong();
    private static final Map<String, Long> TILE_FAILED = new ConcurrentHashMap<>();
    /** Tiles the server confirms do not exist (404): never retried within a session. */
    private static final Set<String> TILE_GONE = ConcurrentHashMap.newKeySet();
    /** Free basemap style: OSM Standard (no-key default) or CARTO Voyager (key required). */
    enum Basemap { VOYAGER, OSM_STANDARD }
    static volatile Basemap basemap = CARTO_KEY.isEmpty() ? Basemap.OSM_STANDARD : Basemap.VOYAGER;
    private static final ExecutorService TILE_IO = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "dcls-tiles"); thread.setDaemon(true); return thread;
    });
    private static final java.net.http.HttpClient TILE_HTTP = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).proxy(tileProxy()).build();

    /** Proxy from HTTPS_PROXY/HTTP_PROXY (host:port, scheme optional) when set,
     *  so campus networks work; otherwise the platform default. */
    static ProxySelector tileProxy() {
        String spec = System.getenv().getOrDefault("HTTPS_PROXY", System.getenv().getOrDefault("HTTP_PROXY", "")).trim();
        if (spec.isEmpty()) return ProxySelector.getDefault();
        try {
            String hostPort = spec.replaceFirst("^[a-zA-Z]+://", "").split("/")[0];
            int colon = hostPort.lastIndexOf(':');
            String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
            int port = colon < 0 ? 8080 : Integer.parseInt(hostPort.substring(colon + 1));
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
            System.out.println("[dcls-tiles] using proxy " + host + ":" + port);
            return new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(proxy); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException e) { }
            };
        } catch (Exception e) {
            return ProxySelector.getDefault();
        }
    }

    private static Path tileDiskPath(String key) {
        return tileDiskPathFor(key, basemap);
    }

    private static Path tileDiskPathFor(String key, Basemap provider) {
        // v2 scope: old "keyless/voyager/*" entries hold "API KEY REQUIRED" watermark
        // tiles (served HTTP 200) and must never be reused.
        boolean osm = provider == Basemap.OSM_STANDARD || CARTO_KEY.isEmpty();
        String scope = (osm ? "osm-v2-" : "")
                + (CARTO_KEY.isEmpty() ? "keyless-v2" : "k" + Integer.toHexString(CARTO_KEY.hashCode()));
        String cacheKey = (osm ? "osm/" : "voyager/") + key;
        return Path.of(System.getProperty("user.home"), ".dcls", "tiles", scope, cacheKey + ".png");
    }

    /** Cached tile image, or null on a miss (queues one background fetch). Failures
     *  are recorded with a reason (see tileProblem) and retried after a cooldown.
     *  All cache keys are provider-scoped and the provider is snapshotted per
     *  request, so toggling the basemap mid-fetch can never mix OSM/CARTO tiles. */
    static Image requestTile(int z, int x, int y) {
        String key = z + "/" + x + "/" + y;
        Basemap provider = basemap;
        String skey = (provider == Basemap.OSM_STANDARD ? "o/" : "v/") + key;
        Image hit = TILE_MEM.get(skey);
        if (hit != null) return hit;
        try {
            Path disk = tileDiskPathFor(key, provider);
            if (Files.isRegularFile(disk)) {
                Image img = new Image(disk.toUri().toString(), false);
                if (!img.isError()) { TILE_MEM.put(skey, img); return img; }
            }
        } catch (Exception ignored) { }
        Long failedAt = TILE_FAILED.get(skey);
        if (failedAt != null && System.currentTimeMillis() - failedAt < 60_000) return null;
        if (TILE_IN_FLIGHT.add(skey)) {
            tileProblem = null;
            TILE_IO.submit(() -> fetchTile(skey, key, z, x, y, provider));
        }
        return null;
    }

    private static void fetchTile(String skey, String key, int z, int x, int y, Basemap provider) {
        if (TILE_URL_LOGGED.compareAndSet(false, true)) {
            boolean osm = provider == Basemap.OSM_STANDARD || CARTO_KEY.isEmpty();
            System.out.println("[dcls-tiles] provider: " + (osm ? "OSM Standard (no key)"
                    : "CARTO Voyager (?key=****)"));
        }
        paceFetches();
        try {
            storeTile(skey, key, downloadTile(z, x, y, 0, provider), provider);
        } catch (TileHttpException e) {
            int code = e.code();
            if (code == 404) {
                TILE_GONE.add(skey);
                failTile(skey, "error", "HTTP 404 (no such tile)", 60_000);
            } else if (code == 429) {
                failTile(skey, "throttled", "HTTP 429" + (e.retryAfter() == null ? "" : " (retry in " + e.retryAfter() + "s)"),
                        parseRetryAfter(e.retryAfter()));
            } else if (code == 401 || code == 403) {
                failTile(skey, "denied", "HTTP " + code, 60_000);
            } else {
                failTile(skey, "error", "HTTP " + code, 60_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failTile(skey, "error", "interrupted", 60_000);
        } catch (IOException e) {
            // Transport failure: retry once via the next subdomain (covers per-host
            // DNS/adblock flakiness) before recording the failure.
            try {
                storeTile(skey, key, downloadTile(z, x, y, 1, provider), provider);
                System.out.println("[dcls-tiles] " + skey + " -> recovered via fallback host");
            } catch (TileHttpException retry) {
                failTile(skey, "error", "HTTP " + retry.code() + " after host retry", 60_000);
            } catch (InterruptedException retry) {
                Thread.currentThread().interrupt();
                failTile(skey, "error", "interrupted", 60_000);
            } catch (IOException retry) {
                failTile(skey, classifyOffline(retry), "host retry: " + describe(retry), 60_000);
            }
        } catch (Exception e) {
            failTile(skey, "error", describe(e), 60_000);
        } finally {
            TILE_IN_FLIGHT.remove(skey);
        }
    }

    /** Minimum spacing between fetch starts so bursts never look abusive. */
    private static void paceFetches() {
        long waitUntil = NEXT_FETCH_AT.get();
        long now = System.currentTimeMillis();
        if (waitUntil > now) {
            try {
                Thread.sleep(waitUntil - now);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        NEXT_FETCH_AT.set(Math.max(System.currentTimeMillis(), waitUntil) + 150);
    }

    /** Non-200 tile response, carrying the status and any Retry-After hint. */
    private static final class TileHttpException extends IOException {
        private final int code;
        private final String retryAfter;
        TileHttpException(int code, String retryAfter) {
            super("HTTP " + code);
            this.code = code; this.retryAfter = retryAfter;
        }
        int code() { return code; }
        String retryAfter() { return retryAfter; }
    }

    private static byte[] downloadTile(int z, int x, int y, int hostShift) throws IOException, InterruptedException {
        return downloadTile(z, x, y, hostShift, basemap);
    }

    private static byte[] downloadTile(int z, int x, int y, int hostShift, Basemap provider) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(tileUrlFor(z, x, y, hostShift, provider)))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "DCLS classroom demo")
                .GET().build();
        HttpResponse<byte[]> response = TILE_HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 200 && response.body().length > 0) return response.body();
        throw new TileHttpException(response.statusCode(),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    /** Cache a downloaded tile; returns false (and records failure) on decode errors. */
    private static boolean storeTile(String skey, String key, byte[] body, Basemap provider) {
        Image img = new Image(new ByteArrayInputStream(body));
        if (img.isError()) {
            failTile(skey, "error", "decode error", 60_000);
            return false;
        }
        TILE_MEM.put(skey, img);
        try {
            Path disk = tileDiskPathFor(key, provider);
            Files.createDirectories(disk.getParent());
            Files.write(disk, body);
        } catch (Exception ignored) { }
        TILE_FAILED.remove(skey);
        tileProblem = null;
        Platform.runLater(MapRenderer::fireTilesUpdated);
        return true;
    }

    private static String classifyOffline(IOException e) {
        return (e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException
                || e instanceof java.net.NoRouteToHostException
                || e instanceof java.net.http.HttpConnectTimeoutException) ? "offline" : "error";
    }

    private static String describe(Exception e) {
        String detail = e.getClass().getSimpleName();
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            String message = e.getMessage().replace(CARTO_KEY, "****");
            detail += ": " + message.substring(0, Math.min(message.length(), 120));
        }
        return detail;
    }

    /** Retry-After value (delta-seconds or HTTP date) in millis, capped at 10 minutes. */
    private static long parseRetryAfter(String value) {
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

    /** Last tile-failure class, for the on-canvas notice: offline, denied (401/403 —
     *  usually a missing DCLS_CARTO_KEY), throttled (429), error, or null. */
    private static volatile String tileProblem;

    private static void failTile(String key, String problem, String detail, long cooldownMs) {
        TILE_FAILED.put(key, System.currentTimeMillis() + Math.max(cooldownMs, 1000));
        tileProblem = problem;
        System.out.println("[dcls-tiles] " + key + " -> " + detail);
        armTileRetry();
    }

    private static final ScheduledExecutorService TILE_RETRY = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dcls-tiles-retry"); thread.setDaemon(true); return thread;
    });
    private static final AtomicBoolean TILE_RETRY_ARMED = new AtomicBoolean();

    /** One repaint after the failure cooldown, so transient blips recover without input. */
    private static void armTileRetry() {
        if (TILE_RETRY_ARMED.compareAndSet(false, true)) {
            TILE_RETRY.schedule(MapRenderer::tileRetryFired, 65, TimeUnit.SECONDS);
        }
    }

    private static void tileRetryFired() {
        TILE_RETRY_ARMED.set(false);
        Platform.runLater(MapRenderer::fireTilesUpdated);
        // Chain another wake-up if any tile still waits out a longer cooldown.
        long now = System.currentTimeMillis();
        long earliest = Long.MAX_VALUE;
        for (Map.Entry<String, Long> entry : List.copyOf(TILE_FAILED.entrySet())) {
            long retryAt = entry.getValue();
            if (retryAt <= now) TILE_FAILED.remove(entry.getKey(), retryAt);
            else if (retryAt < earliest) earliest = retryAt;
        }
        if (earliest != Long.MAX_VALUE && TILE_RETRY_ARMED.compareAndSet(false, true)) {
            TILE_RETRY.schedule(MapRenderer::tileRetryFired,
                    Math.max(earliest - System.currentTimeMillis(), 1000), TimeUnit.MILLISECONDS);
        }
    }

    // Repaint hooks so arrived tiles pop in. Owners keep their Runnable strongly
    // (field / node properties); the registry only holds weak refs, so rebuilt
    // pages never leak and dead listeners are purged on every fire.
    private static final List<WeakReference<Runnable>> TILE_LISTENERS = new ArrayList<>();

    static synchronized void addTileListener(Runnable refresh) {
        TILE_LISTENERS.removeIf(ref -> ref.get() == null || ref.get() == refresh);
        TILE_LISTENERS.add(new WeakReference<>(refresh));
    }

    private static synchronized void fireTilesUpdated() {
        TILE_LISTENERS.removeIf(ref -> ref.get() == null);
        for (WeakReference<Runnable> ref : List.copyOf(TILE_LISTENERS)) {
            Runnable refresh = ref.get();
            if (refresh != null) {
                try { refresh.run(); } catch (RuntimeException ignored) { }
            }
        }
    }

    /** What one frame actually needs: tiles drawn, still arriving, or failed-cooling. */
    private record FrameCoverage(int painted, int pending, int failed) { }

    /** Paint live tiles; reports coverage so callers can tell loading apart from failure. */
    private static FrameCoverage drawLiveTiles(GraphicsContext g, double w, double h, ViewBounds view) {
        double pad = 14;
        int z = zoomFor(view.lonMax() - view.lonMin(), w - pad * 2);
        int x0 = lonToTileX(view.lonMin(), z), x1 = lonToTileX(view.lonMax(), z);
        int y0 = latToTileY(view.latMax(), z), y1 = latToTileY(view.latMin(), z);
        while ((x1 - x0 + 1) * (y1 - y0 + 1) > LIVE_MAX_TILES && z > LIVE_MIN_Z) {
            z--;
            x0 = lonToTileX(view.lonMin(), z); x1 = lonToTileX(view.lonMax(), z);
            y0 = latToTileY(view.latMax(), z); y1 = latToTileY(view.latMin(), z);
        }
        double top = mercator(view.latMax()), bottom = mercator(view.latMin());
        int painted = 0, pending = 0, failed = 0;
        g.save();
        g.beginPath(); g.rect(0, 0, w, h); g.clip();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                Image img = requestTile(z, x, y);
                if (img == null || img.isError()) {
                    if (tileCoolingDown(z, x, y)) failed++;
                    else pending++;
                    continue;
                }
                double dx0 = pad + (tileXToLon(x, z) - view.lonMin()) / (view.lonMax() - view.lonMin()) * (w - pad * 2);
                double dx1 = pad + (tileXToLon(x + 1, z) - view.lonMin()) / (view.lonMax() - view.lonMin()) * (w - pad * 2);
                double dy0 = pad + (top - mercator(tileYToLat(y, z))) / (top - bottom) * (h - pad * 2);
                double dy1 = pad + (top - mercator(tileYToLat(y + 1, z))) / (top - bottom) * (h - pad * 2);
                g.drawImage(img, dx0, dy0, dx1 - dx0, dy1 - dy0);
                painted++;
            }
        }
        g.restore();
        return new FrameCoverage(painted, pending, failed);
    }

    /** True while the tile sits out its post-failure cooldown (no fetch in flight). */
    static boolean tileCoolingDown(int z, int x, int y) {
        String key = z + "/" + x + "/" + y;
        String skey = (basemap == Basemap.OSM_STANDARD ? "o/" : "v/") + key;
        if (TILE_GONE.contains(skey)) return true;
        Long retryAt = TILE_FAILED.get(skey);
        if (retryAt == null) return false;
        if (retryAt > System.currentTimeMillis()) return true;
        TILE_FAILED.remove(skey, retryAt);
        return false;
    }

    /** Max distance from the cluster centroid for a point to frame the map.
     *  Beyond this a lone noisy fix can't stretch the whole viewport. */
    static final double FRAME_OUTLIER_KM = 10.0;

    /** Drawable-area aspect: tiles and overlays map spans onto the padded area,
     *  so balancing against full-canvas w/h stretches one axis (~7% here). */
    static double drawableAspect(double w, double h) {
        return (w - 28) / Math.max(h - 28, 1);
    }

    /** Zoom-to-content: bounds enclosing the given lon/lat points plus margin, matched to
     *  the canvas aspect. Far outliers are ignored so one bad fix can't hijack the view. */
    static ViewBounds fitBounds(List<double[]> points, double aspect) {
        if (points == null || points.isEmpty()) return FULL_MAP;
        List<double[]> valid = new ArrayList<>();
        for (double[] p : points) {
            if (p == null || p.length < 2 || !Double.isFinite(p[0]) || !Double.isFinite(p[1])) continue;
            valid.add(p);
        }
        if (valid.isEmpty()) return FULL_MAP;
        List<double[]> core = dropFarOutliers(valid, FRAME_OUTLIER_KM);
        if (core.isEmpty()) core = valid; // genuinely spread out (e.g. two distant vehicles)
        double lonMin = 180, lonMax = -180, latMin = 90, latMax = -90;
        for (double[] p : core) {
            lonMin = Math.min(lonMin, p[0]); lonMax = Math.max(lonMax, p[0]);
            latMin = Math.min(latMin, p[1]); latMax = Math.max(latMax, p[1]);
        }
        double lonPad = Math.max((lonMax - lonMin) * 0.20, 0.07);
        double latPad = Math.max((latMax - latMin) * 0.20, 0.05);
        lonMin -= lonPad; lonMax += lonPad; latMin -= latPad; latMax += latPad;
        if (lonMax - lonMin < 0.14) {
            double mid = (lonMin + lonMax) / 2;
            lonMin = mid - 0.07; lonMax = mid + 0.07;
        }
        if (latMax - latMin < 0.10) {
            double mid = (latMin + latMax) / 2;
            latMin = mid - 0.05; latMax = mid + 0.05;
        }
        return balanceAndClamp((lonMin + lonMax) / 2,
                (mercator(latMax) + mercator(latMin)) / 2,
                Math.toRadians(lonMax - lonMin), mercator(latMax) - mercator(latMin), aspect);
    }

    /** Points within maxKm of the cluster center (haversine). Uses the component-wise
     *  median, not the mean, so one far outlier can't drag the center away from the
     *  cluster and disqualify the good points. The filter only applies when a strict
     *  majority clusters together — otherwise (e.g. two distant vehicles) everything
     *  is kept. Pure and testable. */
    static List<double[]> dropFarOutliers(List<double[]> points, double maxKm) {
        List<double[]> out = new ArrayList<>();
        if (points == null || points.isEmpty()) return out;
        List<Double> lons = new ArrayList<>(), lats = new ArrayList<>();
        for (double[] p : points) { lons.add(p[0]); lats.add(p[1]); }
        lons.sort(Double::compare);
        lats.sort(Double::compare);
        double lonMid = lons.get(lons.size() / 2), latMid = lats.get(lats.size() / 2);
        List<double[]> core = new ArrayList<>();
        for (double[] p : points) {
            if (AppServices.BruneiPlaces.haversineKm(lonMid, latMid, p[0], p[1]) <= maxKm) core.add(p);
        }
        if (core.size() * 2 > points.size()) return core;
        return new ArrayList<>(points);
    }

    /** Expand the smaller span so the view matches the canvas aspect, clamped back
     *  inside the map. Works in render units (radians lon vs mercator) so tiles
     *  render 1:1 — matching plain degrees visibly squashes the map. Keeps zoom
     *  in/out round-trips squeeze-free: every stored view carries identical proportions. */
    static ViewBounds fitAspect(ViewBounds view, double aspect) {
        double lonMid = (view.lonMin() + view.lonMax()) / 2;
        double mercMid = (mercator(view.latMax()) + mercator(view.latMin())) / 2;
        return balanceAndClamp(lonMid, mercMid,
                Math.toRadians(view.lonMax() - view.lonMin()),
                mercator(view.latMax()) - mercator(view.latMin()), aspect);
    }

    /** Largest undistorted view around (lonMidDeg, mercMid) for the requested spans,
     *  pinned inside the map. Balancing in render units keeps Web-Mercator tiles
     *  square; when a span hits the map edge the other span is rebalanced to it,
     *  so clamping can never squash the view (the old degree-based clamp did). */
    static ViewBounds balanceAndClamp(double lonMidDeg, double mercMid, double lonSpanRad, double mercSpan, double aspect) {
        double maxLonRad = Math.toRadians(MAP_LON_MAX - MAP_LON_MIN);
        double maxMerc = mercator(MAP_LAT_MAX) - mercator(MAP_LAT_MIN);
        if (lonSpanRad / Math.max(mercSpan, 1e-12) < aspect) lonSpanRad = mercSpan * aspect;
        else mercSpan = lonSpanRad / aspect;
        if (lonSpanRad >= maxLonRad && mercSpan >= maxMerc) return FULL_MAP;
        if (lonSpanRad >= maxLonRad) {
            lonSpanRad = maxLonRad;
            mercSpan = lonSpanRad / aspect;
        }
        if (mercSpan >= maxMerc) {
            mercSpan = maxMerc;
            lonSpanRad = mercSpan * aspect;
        }
        if (lonSpanRad >= maxLonRad && mercSpan >= maxMerc) return FULL_MAP;
        double lonSpanDeg = Math.toDegrees(lonSpanRad);
        double lonMin = lonMidDeg - lonSpanDeg / 2, lonMax = lonMidDeg + lonSpanDeg / 2;
        double latMax = mercToLat(mercMid + mercSpan / 2), latMin = mercToLat(mercMid - mercSpan / 2);
        if (lonMin < MAP_LON_MIN) { lonMax += MAP_LON_MIN - lonMin; lonMin = MAP_LON_MIN; }
        if (lonMax > MAP_LON_MAX) { lonMin -= lonMax - MAP_LON_MAX; lonMax = MAP_LON_MAX; }
        if (latMin < MAP_LAT_MIN) { latMax += MAP_LAT_MIN - latMin; latMin = MAP_LAT_MIN; }
        if (latMax > MAP_LAT_MAX) { latMin -= latMax - MAP_LAT_MAX; latMax = MAP_LAT_MAX; }
        return new ViewBounds(lonMin, lonMax, latMin, latMax);
    }

    static double mercator(double lat) {
        double rad = Math.toRadians(lat);
        return Math.log(Math.tan(rad) + 1 / Math.cos(rad));
    }

    static double mercToLat(double merc) {
        return Math.toDegrees(Math.atan(Math.sinh(merc)));
    }

    static double[] project(Stop stop, double width, double height, ViewBounds view) {
        return project(stop.x(), stop.y(), width, height, view);
    }

    static double[] project(double lon, double lat, double width, double height, ViewBounds view) {
        double pad = 14;
        double lonSpan = view.lonMax() - view.lonMin();
        double top = mercator(view.latMax()), bottom = mercator(view.latMin());
        if (!(lonSpan > 0) || !(top > bottom)) return new double[] {width / 2, height / 2};
        double x = pad + (lon - view.lonMin()) / lonSpan * (width - pad * 2);
        double y = pad + (top - mercator(Math.min(Math.max(lat, view.latMin()), view.latMax()))) / (top - bottom) * (height - pad * 2);
        return new double[] {Math.min(Math.max(x, 10), width - 10), Math.min(Math.max(y, 22), height - 10)};
    }

    /** Unclamped projection to pixel space: off-view points yield coordinates outside
     *  the canvas, which is what segment clipping needs. Use this for polylines;
     *  keep the clamped project() for point markers. */
    static double[] projectRaw(double lon, double lat, double width, double height, ViewBounds view) {
        double pad = 14;
        double x = pad + (lon - view.lonMin()) / (view.lonMax() - view.lonMin()) * (width - pad * 2);
        double top = mercator(view.latMax()), bottom = mercator(view.latMin());
        double y = pad + (top - mercator(lat)) / (top - bottom) * (height - pad * 2);
        return new double[] {x, y};
    }

    /** Projection, or null when the point lies outside the drawable pad area.
     *  Off-view markers are skipped instead of edge-clamped, like Leaflet markers. */
    static double[] projectIfVisible(double lon, double lat, double width, double height, ViewBounds view) {
        if (!Double.isFinite(lon) || !Double.isFinite(lat)) return null;
        double[] p = projectRaw(lon, lat, width, height, view);
        double pad = 14;
        if (p[0] < pad || p[0] > width - pad || p[1] < pad || p[1] > height - pad) return null;
        return p;
    }

    /** Stroke the visible portion of segment a-b, clipped to the drawable map area
     *  (Liang-Barsky). Fully off-view segments draw nothing, so zoomed-in polylines
     *  exit the viewport at the correct angle instead of flattening along the border. */
    static void strokeClippedLine(GraphicsContext g, double ax, double ay, double bx, double by, double width, double height) {
        double pad = 14;
        double xMin = pad, xMax = width - pad, yMin = pad, yMax = height - pad;
        double dx = bx - ax, dy = by - ay;
        double t0 = 0, t1 = 1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {ax - xMin, xMax - ax, ay - yMin, yMax - ay};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-12) {
                if (q[i] < 0) return;
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0) t0 = Math.max(t0, r);
                else t1 = Math.min(t1, r);
                if (t0 > t1) return;
            }
        }
        g.strokeLine(ax + t0 * dx, ay + t0 * dy, ax + t1 * dx, ay + t1 * dy);
    }

    static double[] unproject(double px, double py, double width, double height, ViewBounds view) {
        double pad = 14;
        double lon = view.lonMin() + (px - pad) / (width - pad * 2) * (view.lonMax() - view.lonMin());
        double mercTop = mercator(view.latMax()), mercBottom = mercator(view.latMin());
        double lat = mercToLat(mercTop - (py - pad) / (height - pad * 2) * (mercTop - mercBottom));
        return new double[] {
            Math.min(Math.max(lon, MAP_LON_MIN), MAP_LON_MAX),
            Math.min(Math.max(lat, MAP_LAT_MIN), MAP_LAT_MAX)};
    }

    static Canvas mapCanvas(double width, double height) {
        Canvas canvas = new Canvas(width, height);
        canvas.getStyleClass().add("map-canvas");
        prepareMap(canvas, FULL_MAP);
        return canvas;
    }

    static GraphicsContext prepareMap(Canvas canvas, ViewBounds view) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        g.setImageSmoothing(true);
        g.setFill(Color.web("#e8edf1")); g.fillRect(0, 0, w, h);
        FrameCoverage coverage = drawLiveTiles(g, w, h, view);
        if (coverage.painted() == 0 && (coverage.pending() > 0 || coverage.failed() > 0)) {
            String reason;
            if (coverage.pending() > 0) {
                reason = "Tiles are still loading…";
            } else {
                String problem = tileProblem == null ? "" : tileProblem;
                reason = switch (problem) {
                    case "denied" -> "CARTO needs an API key — using OSM Standard (free, no key).";
                    case "throttled" -> "Tile server is rate-limiting us — retrying automatically.";
                    case "offline" -> "Connect to the internet.";
                    default -> "A network error occurred — retrying automatically.";
                };
            }
            g.setFill(Color.web("#657487")); g.setFont(Font.font("Segoe UI", 12));
            g.fillText("Map tiles unavailable — " + reason, 14, h / 2);
            g.setFont(Font.font("Segoe UI", 10));
            g.fillText("Previously visited areas load from the tile cache.", 14, h / 2 + 18);
        }
        g.setFill(Color.web("#657487")); g.setFont(Font.font("Segoe UI", 11));
        g.fillText("Brunei Darussalam  ·  Live GPS" + (basemap == Basemap.OSM_STANDARD ? " · OSM" : " · CARTO"), 14, 20);
        g.setFont(Font.font("Segoe UI", 9));
        g.fillText("© OSM contributors © CARTO", w - 172, h - 8);
        drawScaleBar(g, w, h, view);
        return g;
    }

    /** Scale bar from the live view span (111.32 km per lon-degree, adjusted for latitude). */
    static void drawScaleBar(GraphicsContext g, double w, double h, ViewBounds view) {
        double latMid = (view.latMin() + view.latMax()) / 2;
        double metersPerPixel = (view.lonMax() - view.lonMin()) * 111320 * Math.cos(Math.toRadians(latMid)) / (w - 28);
        int[] steps = {5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000};
        int choice = steps[0];
        for (int step : steps) if (step / metersPerPixel <= 130) choice = step;
        double bar = choice / metersPerPixel;
        double x = 14, y = h - 14;
        g.setStroke(Color.WHITE); g.setLineWidth(5);
        g.strokeLine(x, y, x + bar, y);
        g.setStroke(Color.web("#172033")); g.setLineWidth(2.5);
        g.strokeLine(x, y, x + bar, y);
        g.setFill(Color.web("#172033"));
        g.setFont(Font.font("Segoe UI", 9));
        g.fillText(choice >= 1000 ? (choice / 1000) + " km" : choice + " m", x + bar + 6, y + 3);
    }

    static void drawRoute(Canvas canvas, List<Stop> stops, TrackingPoint moving) {
        if (stops == null || stops.isEmpty()) return;
        List<double[]> points = new ArrayList<>();
        for (Stop stop : stops) points.add(new double[] {stop.x(), stop.y()});
        if (moving != null) points.add(new double[] {moving.x(), moving.y()});
        ViewBounds view = fitBounds(points, drawableAspect(canvas.getWidth(), canvas.getHeight()));
        GraphicsContext g = prepareMap(canvas, view);
        drawRouteOn(g, canvas.getWidth(), canvas.getHeight(), stops, moving, "#176df6", view);
    }

    static void drawRouteOn(GraphicsContext g, double width, double height, List<Stop> stops, TrackingPoint moving) {
        drawRouteOn(g, width, height, stops, moving, "#176df6", autoView(stops, moving, drawableAspect(width, height)));
    }

    static void drawRouteOn(GraphicsContext g, double width, double height, List<Stop> stops, TrackingPoint moving, String color, ViewBounds view) {
        drawRouteOn(g, width, height, stops, moving, color, view, -1);
    }

    static void drawRouteOn(GraphicsContext g, double width, double height, List<Stop> stops, TrackingPoint moving, String color, ViewBounds view, int highlight) {
        if (stops == null || stops.isEmpty()) return;
        strokeLegs(g, width, height, stops, view, highlight, color);
        drawStopMarkers(g, width, height, stops, view);
        if (moving != null) drawVehicleMarker(g, width, height, moving.x(), moving.y(), "Vehicle", "#1457c5", false, view);
    }

    /** Route legs as separate cased polylines: white casing, round caps, direction
     *  chevrons, perpendicular offset where a road is used twice (out-and-back),
     *  and optional highlight dimming (highlight < 0 draws every leg full). */
    static void strokeLegs(GraphicsContext g, double width, double height, List<Stop> stops, ViewBounds view, int highlight, String color) {
        if (stops == null || stops.size() < 2) return;
        Map<String, Integer> seenPairs = new HashMap<>();
        int[] offsets = new int[stops.size()];
        for (int i = 1; i < stops.size(); i++) {
            String pair = legPairKey(stops.get(i - 1), stops.get(i));
            int count = seenPairs.getOrDefault(pair, 0);
            seenPairs.put(pair, count + 1);
            offsets[i] = count == 0 ? 0 : (count % 2 == 1 ? 5 : -5);
        }
        g.save();
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        for (int i = 1; i < stops.size(); i++) {
            List<double[]> path = AppServices.RouteService.legPath(stops.get(i - 1), stops.get(i));
            List<double[]> pixels = new ArrayList<>();
            for (double[] pt : path) {
                double[] raw = projectRaw(pt[0], pt[1], width, height, view);
                pixels.add(new double[] {raw[0], raw[1]});
            }
            if (offsets[i] != 0 && pixels.size() >= 2) {
                double[] a = pixels.get(0), b = pixels.get(pixels.size() - 1);
                double dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy);
                if (len > 1e-9) {
                    double nx = -dy / len * offsets[i], ny = dx / len * offsets[i];
                    for (double[] p : pixels) { p[0] += nx; p[1] += ny; }
                }
            }
            double alpha = highlight < 0 || highlight == i - 1 ? 1.0 : 0.15;
            g.setGlobalAlpha(1.0);
            g.setStroke(Color.web("#ffffff", 0.9 * alpha)); g.setLineWidth(7);
            strokePixelPath(g, pixels, width, height);
            g.setGlobalAlpha(0.75 * alpha);
            g.setStroke(Color.web(color)); g.setLineWidth(4);
            strokePixelPath(g, pixels, width, height);
            g.setGlobalAlpha(1.0);
            g.setStroke(Color.web(color, alpha)); g.setLineWidth(2.5);
            drawChevrons(g, pixels, width, height);
        }
        g.restore();
    }

    private static String legPairKey(Stop a, Stop b) {
        String ka = Math.round(a.x() * 1e6) + "," + Math.round(a.y() * 1e6);
        String kb = Math.round(b.x() * 1e6) + "," + Math.round(b.y() * 1e6);
        return ka.compareTo(kb) <= 0 ? ka + "|" + kb : kb + "|" + ka;
    }

    private static void strokePixelPath(GraphicsContext g, List<double[]> pixels, double width, double height) {
        for (int j = 1; j < pixels.size(); j++) {
            strokeClippedLine(g, pixels.get(j - 1)[0], pixels.get(j - 1)[1],
                    pixels.get(j)[0], pixels.get(j)[1], width, height);
        }
    }

    /** Direction chevrons stamped along a pixel polyline (~every 90 px). */
    private static void drawChevrons(GraphicsContext g, List<double[]> pixels, double width, double height) {
        double pad = 14;
        double total = 0;
        for (int j = 1; j < pixels.size(); j++) {
            total += Math.hypot(pixels.get(j)[0] - pixels.get(j - 1)[0], pixels.get(j)[1] - pixels.get(j - 1)[1]);
        }
        if (total < 70) return;
        for (double target = 60; target < total - 20; target += 90) {
            double[] tip = pointAtDistance(pixels, target);
            double[] ahead = pointAtDistance(pixels, Math.min(target + 1, total));
            if (tip == null || ahead == null) continue;
            if (tip[0] < pad || tip[0] > width - pad || tip[1] < pad || tip[1] > height - pad) continue;
            double angle = Math.atan2(ahead[1] - tip[1], ahead[0] - tip[0]);
            double arm = 8, spread = Math.toRadians(25);
            double x1 = tip[0] - arm * Math.cos(angle - spread), y1 = tip[1] - arm * Math.sin(angle - spread);
            double x2 = tip[0] - arm * Math.cos(angle + spread), y2 = tip[1] - arm * Math.sin(angle + spread);
            strokeClippedLine(g, tip[0], tip[1], x1, y1, width, height);
            strokeClippedLine(g, tip[0], tip[1], x2, y2, width, height);
        }
    }

    private static double[] pointAtDistance(List<double[]> pixels, double target) {
        double walked = 0;
        for (int j = 1; j < pixels.size(); j++) {
            double[] a = pixels.get(j - 1), b = pixels.get(j);
            double seg = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (walked + seg >= target) {
                double t = seg < 1e-9 ? 0 : (target - walked) / seg;
                return new double[] {a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])};
            }
            walked += seg;
        }
        return pixels.isEmpty() ? null : pixels.get(pixels.size() - 1);
    }

    /** Numbered circular badges (no text labels, so nothing overlaps the line).
     *  Names live in hover tooltips, click popups and the stop list instead. */
    static void drawStopMarkers(GraphicsContext g, double width, double height, List<Stop> stops, ViewBounds view) {
        if (stops == null) return;
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i);
            double[] p = projectIfVisible(stop.x(), stop.y(), width, height, view);
            if (p == null) continue;
            double x = p[0], y = p[1];
            String ring = i == 0 ? "#17613f" : stop.id().startsWith("custom-") ? "#7c3aed" : "#176df6";
            g.setFill(Color.WHITE);
            g.fillOval(x - 9, y - 9, 18, 18);
            g.setStroke(Color.web(ring)); g.setLineWidth(3);
            g.strokeOval(x - 9, y - 9, 18, 18);
            String number = String.valueOf(i + 1);
            g.setFill(Color.web(ring));
            g.setFont(Font.font("Segoe UI", 10));
            g.fillText(number, x - (number.length() > 1 ? 6 : 3), y + 3.5);
        }
    }

    /** Stroke every leg along its real road polyline (straight fallback when unknown). */
    static void strokeRoadPath(GraphicsContext g, double width, double height, List<Stop> stops, ViewBounds view) {
        for (int i = 1; i < stops.size(); i++) {
            List<double[]> path = AppServices.RouteService.legPath(stops.get(i - 1), stops.get(i));
            for (int j = 1; j < path.size(); j++) {
                double[] a = projectRaw(path.get(j - 1)[0], path.get(j - 1)[1], width, height, view);
                double[] b = projectRaw(path.get(j)[0], path.get(j)[1], width, height, view);
                strokeClippedLine(g, a[0], a[1], b[0], b[1], width, height);
            }
        }
    }

    static void mapLabel(GraphicsContext g, String text, double x, double y) {
        g.setStroke(Color.WHITE); g.setLineWidth(3);
        g.strokeText(text, x, y);
        g.setFill(Color.web("#172033")); g.fillText(text, x, y);
    }

    static void drawVehicleMarker(GraphicsContext g, double width, double height, double x, double y, String label) {
        drawVehicleMarker(g, width, height, x, y, label, "#1457c5", false, FULL_MAP);
    }

    static void drawVehicleMarker(GraphicsContext g, double width, double height, double x, double y, String label, String color, boolean stale, ViewBounds view) {
        double[] p = project(x, y, width, height, view); double px = p[0], py = p[1];
        g.setFill(Color.web(color)); g.fillOval(px - 9, py - 9, 18, 18);
        g.setStroke(Color.WHITE); g.setLineWidth(2); g.strokeOval(px - 9, py - 9, 18, 18);
        mapLabel(g, label + (stale ? " · STALE" : ""), px + 12, py + 4);
    }

    static void drawDashboardRoads(Canvas canvas) {
        prepareMap(canvas, FULL_MAP);
    }

    /** A single vehicle dot for the dashboard operations map. */
    record VehiclePlot(double lon, double lat, String label, String color, boolean stale) { }

    /** Operations-map rendering: bare map plus vehicle dots only (no routes, no stops).
     *  Returns the view used, so callers can hit-test hovers with project(). */
    static ViewBounds drawVehicles(Canvas canvas, List<VehiclePlot> plots) {
        double w = canvas.getWidth(), h = canvas.getHeight();
        List<double[]> points = new ArrayList<>();
        if (plots != null) for (VehiclePlot plot : plots) points.add(new double[] {plot.lon(), plot.lat()});
        ViewBounds view = fitBounds(points, drawableAspect(w, h));
        GraphicsContext g = prepareMap(canvas, view);
        if (plots != null) for (VehiclePlot plot : plots) {
            double[] p = project(plot.lon(), plot.lat(), w, h, view);
            g.setFill(Color.web(plot.color())); g.fillOval(p[0] - 9, p[1] - 9, 18, 18);
            g.setStroke(Color.WHITE); g.setLineWidth(2); g.strokeOval(p[0] - 9, p[1] - 9, 18, 18);
            mapLabel(g, plot.label() + (plot.stale() ? " · STALE" : ""), p[0] + 12, p[1] + 4);
        }
        return view;
    }

    static Canvas activityChart() {
        Canvas canvas = new Canvas(224, 74); GraphicsContext g = canvas.getGraphicsContext2D(); double[] values = {.34,.43,.39,.55,.74,.86,.69,.92,.84,.94,.82,1};
        for(int i=0;i<values.length;i++){double x=4+i*18,bar=values[i]*56;g.setFill(i==values.length-1?Color.web("#315cf5"):Color.web("#e5ebff"));g.fillRoundRect(x,58-bar,14,bar,4,4);} return canvas;
    }

    /** Free Nominatim search for Brunei (WiFi). Ranked rooftop-first. */
    record PlaceHit(double lon, double lat, String name, String displayName, double importance, int placeRank, String type) {
        PlaceHit(double lon, double lat, String name) { this(lon, lat, name, name, 0, 0, ""); }
    }

    /** Whole-Brunei acceptance window (wider than the Bandar map viewport). */
    static final double GEO_LON_MIN = 114.0, GEO_LON_MAX = 115.5, GEO_LAT_MIN = 4.0, GEO_LAT_MAX = 5.4;

    /** Progressive search over normalized query levels; returns hits + winning level. */
    record LevelHits(List<PlaceHit> hits, int level, String queryUsed) { }

    static LevelHits searchLevels(List<String> levels) {
        if (levels == null) return new LevelHits(List.of(), -1, "");
        for (int i = 0; i < levels.size(); i++) {
            String q = levels.get(i);
            if (q == null || q.isBlank()) continue;
            List<PlaceHit> hits = fetchNominatim(q);
            if (!hits.isEmpty()) return new LevelHits(hits, i, q);
        }
        return new LevelHits(List.of(), -1, "");
    }

    static List<PlaceHit> searchPlaces(String query) {
        List<PlaceHit> out = new ArrayList<>();
        if (query == null || query.isBlank()) return out;
        String q = query.trim();
        // Disk cache first (instant + offline-tolerant).
        try {
            Path cache = Path.of(System.getProperty("user.home"), ".dcls", "geocode",
                    Integer.toHexString(("bn|" + q.toLowerCase(java.util.Locale.ROOT)).hashCode()) + ".json");
            if (Files.isRegularFile(cache)
                    && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < 30L * 24 * 3600 * 1000) {
                List<PlaceHit> cached = parseNominatim(Files.readString(cache), q);
                if (!cached.isEmpty()) return cached;
            }
        } catch (Exception ignored) { }
        // Two attempts: full address, then shortened (house numbers can confuse the first pass).
        List<PlaceHit> hits = fetchNominatim(q);
        if (hits.isEmpty()) {
            String shortQ = q.replaceAll("(?i)\\b(no\\.?|lot|unit|spg|simpang)\\s*\\d+[\\w-]*,?", "").replaceAll("\\s+", " ").trim();
            if (!shortQ.equalsIgnoreCase(q) && !shortQ.isBlank()) hits = fetchNominatim(shortQ);
        }
        return hits;
    }

    /** Session caches so the 1-second tracking tick never touches disk or network
     *  for known queries. Misses (empty results) are cached too — otherwise an
     *  unresolvable address would fire HTTP requests every tick, forever. */
    private static final Map<String, List<PlaceHit>> GEO_HIT_MEM =
            Collections.synchronizedMap(new LinkedHashMap<>(200, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<PlaceHit>> e) { return size() > 200; }
            });
    private static final Map<String, Long> GEO_MISS_MEM = new ConcurrentHashMap<>();
    private static final long GEO_NEG_TTL_MS = 24L * 3600 * 1000;
    private static final long GEO_POS_TTL_MS = 30L * 24 * 3600 * 1000;

    private static Path geoCachePath(String q) {
        return Path.of(System.getProperty("user.home"), ".dcls", "geocode",
                Integer.toHexString(("bn|" + q.toLowerCase(java.util.Locale.ROOT)).hashCode()) + ".json");
    }

    private static Path geoMissPath(String q) {
        return Path.of(System.getProperty("user.home"), ".dcls", "geocode",
                Integer.toHexString(("bn|" + q.toLowerCase(java.util.Locale.ROOT)).hashCode()) + ".empty");
    }

    private static void rememberMiss(String key, String q) {
        GEO_MISS_MEM.put(key, System.currentTimeMillis());
        try {
            Path miss = geoMissPath(q);
            Files.createDirectories(miss.getParent());
            Files.writeString(miss, String.valueOf(System.currentTimeMillis()));
        } catch (Exception ignored) { }
    }

    // Test hooks (package-visible).
    static void noteGeoMissForTest(String q) {
        rememberMiss("bn|" + q.toLowerCase(java.util.Locale.ROOT), q);
    }

    static boolean isGeoMissCached(String q) {
        Long at = GEO_MISS_MEM.get("bn|" + q.toLowerCase(java.util.Locale.ROOT));
        return at != null && System.currentTimeMillis() - at < GEO_NEG_TTL_MS;
    }

    static List<PlaceHit> fetchNominatim(String q) {
        String key = "bn|" + q.toLowerCase(java.util.Locale.ROOT);
        List<PlaceHit> mem = GEO_HIT_MEM.get(key);
        if (mem != null) return mem;
        long now = System.currentTimeMillis();
        Long missAt = GEO_MISS_MEM.get(key);
        if (missAt != null && now - missAt < GEO_NEG_TTL_MS) return List.of();
        try {
            Path cache = geoCachePath(q);
            if (Files.isRegularFile(cache) && now - Files.getLastModifiedTime(cache).toMillis() < GEO_POS_TTL_MS) {
                List<PlaceHit> cached = parseNominatim(Files.readString(cache), q);
                if (!cached.isEmpty()) { GEO_HIT_MEM.put(key, cached); return cached; }
            }
            Path missFile = geoMissPath(q);
            if (Files.isRegularFile(missFile) && now - Files.getLastModifiedTime(missFile).toMillis() < GEO_NEG_TTL_MS) {
                GEO_MISS_MEM.put(key, now);
                return List.of();
            } else if (Files.isRegularFile(missFile)) {
                Files.deleteIfExists(missFile);
            }
        } catch (Exception ignored) { }
        try {
            String url = "https://nominatim.openstreetmap.org/search?q="
                    + java.net.URLEncoder.encode(q + ", Brunei", StandardCharsets.UTF_8)
                    + "&format=jsonv2&addressdetails=1&extratags=0&limit=8&countrycodes=bn"
                    + "&viewbox=114.0,5.4,115.5,4.0";
            var req = java.net.http.HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8)).header("User-Agent", "DCLS classroom demo").GET().build();
            var res = TILE_HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return List.of();
            List<PlaceHit> parsed = parseNominatim(res.body(), q);
            if (!parsed.isEmpty()) {
                GEO_HIT_MEM.put(key, parsed);
                try {
                    Path cache = geoCachePath(q);
                    Files.createDirectories(cache.getParent());
                    Files.writeString(cache, res.body());
                } catch (Exception ignored) { }
            } else {
                rememberMiss(key, q);
            }
            return parsed;
        } catch (Exception ignored) { }
        return List.of();
    }

    /** Parse Nominatim jsonv2 array; handles quoted or numeric lat/lon in any order. */
    static List<PlaceHit> parseNominatim(String json, String fallbackName) {
        List<ScoredHit> scored = new ArrayList<>();
        if (json == null || json.isBlank()) return List.of();
        for (String o : splitObjects(json)) {
            try {
                Double lat = numOrQuoted(o, "lat"), lon = numOrQuoted(o, "lon");
                if (lat == null || lon == null) continue;
                boolean inBrunei = lon >= GEO_LON_MIN && lon <= GEO_LON_MAX && lat >= GEO_LAT_MIN && lat <= GEO_LAT_MAX;
                String display = strField(o, "display_name");
                String name = display == null ? fallbackName : display.split(",")[0].trim();
                if (name.isBlank()) name = fallbackName;
                Double importance = numOrQuoted(o, "importance");
                int rank = intField(o, "place_rank", 0);
                String type = strField(o, "type");
                if (type == null) type = strField(o, "class");
                if (type == null) type = "";
                double score = scoreHit(rank, importance == null ? 0 : importance, type, inBrunei, lon, lat);
                scored.add(new ScoredHit(new PlaceHit(lon, lat, name, display == null ? name : display,
                        importance == null ? 0 : importance, rank, type), score));
            } catch (Exception ignored) { }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.stream().limit(5).map(ScoredHit::hit).toList();
    }

    private record ScoredHit(PlaceHit hit, double score) { }

    private static double scoreHit(int rank, double importance, String type, boolean inBrunei, double lon, double lat) {
        double s = 0;
        // Rooftop/building first.
        if (rank >= 26) s += 40;
        else if (rank >= 20) s += 25;
        else if (rank >= 16) s += 12;
        String t = type.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("house") || t.contains("building") || t.contains("amenity") || t.contains("shop")) s += 15;
        else if (t.contains("road") || t.contains("street") || t.contains("residential")) s += 8;
        s += Math.min(20, importance * 30);
        if (!inBrunei) s -= 60;
        // Sanity: far from Bandar core is suspicious but not fatal.
        double dCore = Math.hypot(lon - 114.94, lat - 4.90) * 111;
        if (dCore > 60) s -= 20;
        return s;
    }

    private static List<String> splitObjects(String json) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) inStr = !inStr;
            if (inStr) continue;
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && start >= 0) { out.add(json.substring(start, i + 1)); start = -1; } }
        }
        return out;
    }

    private static Double numOrQuoted(String o, String key) {
        var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"?(-?[\\d.]+)\"?").matcher(o);
        if (!m.find()) return null;
        try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException e) { return null; }
    }

    private static String strField(String o, String key) {
        var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(o);
        if (!m.find()) return null;
        return m.group(1).replace("\\\"", "\"").trim();
    }

    private static int intField(String o, String key, int def) {
        var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"?(\\d+)\"?").matcher(o);
        if (!m.find()) return def;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return def; }
    }

    private static ViewBounds autoView(List<Stop> stops, TrackingPoint moving, double aspect) {
        List<double[]> points = new ArrayList<>();
        if (stops != null) for (Stop stop : stops) points.add(new double[] {stop.x(), stop.y()});
        if (moving != null) points.add(new double[] {moving.x(), moving.y()});
        return fitBounds(points, aspect);
    }
}

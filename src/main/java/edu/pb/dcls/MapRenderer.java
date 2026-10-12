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
 * Real-map rendering over live Google-style tiles (CARTO Voyager @2x retina, zoom 0-20,
 * free key via DCLS_CARTO_KEY). Online-only: the disk tile cache covers previously
 * visited areas when offline. Stateless per call except the shared tile cache: every
 * method takes what it draws, so there is no shared state between screens.
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
    /** Free CARTO key (no billing, no account): set DCLS_CARTO_KEY, else keyless attempt. */
    private static final String CARTO_KEY = System.getenv().getOrDefault("DCLS_CARTO_KEY", "").trim();

    static {
        System.out.println("[dcls-tiles] key configured: " + (CARTO_KEY.isEmpty() ? "no" : "yes"));
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

    private static String tileUrlShifted(int z, int x, int y, int hostShift) {
        String host = TILE_HOSTS[Math.floorMod(x + y + hostShift, TILE_HOSTS.length)];
        String url = "https://" + host + ".basemaps.cartocdn.com/rastertiles/voyager/" + z + "/" + x + "/" + y + "@2x.png";
        return CARTO_KEY.isEmpty() ? url : url + "?key=" + CARTO_KEY;
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
    private static final ExecutorService TILE_IO = Executors.newFixedThreadPool(1, runnable -> {
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
        // Scope the cache by key state so watermark tiles fetched keyless can never
        // masquerade as good tiles once a key is configured (tag is a hash, never the key).
        String scope = CARTO_KEY.isEmpty() ? "keyless" : "k" + Integer.toHexString(CARTO_KEY.hashCode());
        return Path.of(System.getProperty("user.home"), ".dcls", "tiles", scope, key + ".png");
    }

    /** Cached tile image, or null on a miss (queues one background fetch). Failures
     *  are recorded with a reason (see tileProblem) and retried after a cooldown. */
    static Image requestTile(int z, int x, int y) {
        String key = z + "/" + x + "/" + y;
        Image hit = TILE_MEM.get(key);
        if (hit != null) return hit;
        try {
            Path disk = tileDiskPath(key);
            if (Files.isRegularFile(disk)) {
                Image img = new Image(disk.toUri().toString(), false);
                if (!img.isError()) { TILE_MEM.put(key, img); return img; }
            }
        } catch (Exception ignored) { }
        Long failedAt = TILE_FAILED.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < 60_000) return null;
        if (TILE_IN_FLIGHT.add(key)) {
            tileProblem = null;
            TILE_IO.submit(() -> fetchTile(key, z, x, y));
        }
        return null;
    }

    private static void fetchTile(String key, int z, int x, int y) {
        if (TILE_URL_LOGGED.compareAndSet(false, true)) {
            System.out.println("[dcls-tiles] url shape: https://<a-d>.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}@2x.png"
                    + (CARTO_KEY.isEmpty() ? " (keyless)" : "?key=****"));
        }
        paceFetches();
        try {
            storeTile(key, downloadTile(z, x, y, 0));
        } catch (TileHttpException e) {
            int code = e.code();
            if (code == 404) {
                TILE_GONE.add(key);
                failTile(key, "error", "HTTP 404 (no such tile)", 60_000);
            } else if (code == 429) {
                failTile(key, "throttled", "HTTP 429" + (e.retryAfter() == null ? "" : " (retry in " + e.retryAfter() + "s)"),
                        parseRetryAfter(e.retryAfter()));
            } else if (code == 401 || code == 403) {
                failTile(key, "denied", "HTTP " + code, 60_000);
            } else {
                failTile(key, "error", "HTTP " + code, 60_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failTile(key, "error", "interrupted", 60_000);
        } catch (IOException e) {
            // Transport failure: retry once via the next subdomain (covers per-host
            // DNS/adblock flakiness) before recording the failure.
            try {
                storeTile(key, downloadTile(z, x, y, 1));
                System.out.println("[dcls-tiles] " + key + " -> recovered via fallback host");
            } catch (TileHttpException retry) {
                failTile(key, "error", "HTTP " + retry.code() + " after host retry", 60_000);
            } catch (InterruptedException retry) {
                Thread.currentThread().interrupt();
                failTile(key, "error", "interrupted", 60_000);
            } catch (IOException retry) {
                failTile(key, classifyOffline(retry), "host retry: " + describe(retry), 60_000);
            }
        } catch (Exception e) {
            failTile(key, "error", describe(e), 60_000);
        } finally {
            TILE_IN_FLIGHT.remove(key);
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(tileUrlShifted(z, x, y, hostShift)))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "DCLS classroom demo")
                .GET().build();
        HttpResponse<byte[]> response = TILE_HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 200 && response.body().length > 0) return response.body();
        throw new TileHttpException(response.statusCode(),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    /** Cache a downloaded tile; returns false (and records failure) on decode errors. */
    private static boolean storeTile(String key, byte[] body) {
        Image img = new Image(new ByteArrayInputStream(body));
        if (img.isError()) {
            failTile(key, "error", "decode error", 60_000);
            return false;
        }
        TILE_MEM.put(key, img);
        try {
            Path disk = tileDiskPath(key);
            Files.createDirectories(disk.getParent());
            Files.write(disk, body);
        } catch (Exception ignored) { }
        TILE_FAILED.remove(key);
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
        if (TILE_GONE.contains(key)) return true;
        Long retryAt = TILE_FAILED.get(key);
        if (retryAt == null) return false;
        if (retryAt > System.currentTimeMillis()) return true;
        TILE_FAILED.remove(key, retryAt);
        return false;
    }

    /** Zoom-to-content: bounds enclosing the given lon/lat points plus margin, matched to
     *  the canvas aspect. */
    static ViewBounds fitBounds(List<double[]> points, double aspect) {
        if (points == null || points.isEmpty()) return FULL_MAP;
        double lonMin = 180, lonMax = -180, latMin = 90, latMax = -90;
        int valid = 0;
        for (double[] p : points) {
            if (p == null || p.length < 2 || !Double.isFinite(p[0]) || !Double.isFinite(p[1])) continue;
            valid++;
            lonMin = Math.min(lonMin, p[0]); lonMax = Math.max(lonMax, p[0]);
            latMin = Math.min(latMin, p[1]); latMax = Math.max(latMax, p[1]);
        }
        if (valid == 0) return FULL_MAP;
        double lonPad = Math.max((lonMax - lonMin) * 0.20, 0.07);
        double latPad = Math.max((latMax - latMin) * 0.20, 0.05);
        lonMin -= lonPad; lonMax += lonPad; latMin -= latPad; latMax += latPad;
        double lonSpan = Math.max(lonMax - lonMin, 0.14), latSpan = Math.max(latMax - latMin, 0.10);
        if (lonSpan / latSpan < aspect) lonSpan = latSpan * aspect;
        else latSpan = lonSpan / aspect;
        double lonMid = (lonMin + lonMax) / 2, latMid = (latMin + latMax) / 2;
        lonMin = lonMid - lonSpan / 2; lonMax = lonMid + lonSpan / 2;
        latMin = latMid - latSpan / 2; latMax = latMid + latSpan / 2;
        if (lonMax - lonMin >= MAP_LON_MAX - MAP_LON_MIN || latMax - latMin >= MAP_LAT_MAX - MAP_LAT_MIN) return FULL_MAP;
        if (lonMin < MAP_LON_MIN) { lonMax += MAP_LON_MIN - lonMin; lonMin = MAP_LON_MIN; }
        if (lonMax > MAP_LON_MAX) { lonMin -= lonMax - MAP_LON_MAX; lonMax = MAP_LON_MAX; }
        if (latMin < MAP_LAT_MIN) { latMax += MAP_LAT_MIN - latMin; latMin = MAP_LAT_MIN; }
        if (latMax > MAP_LAT_MAX) { latMin -= latMax - MAP_LAT_MAX; latMax = MAP_LAT_MAX; }
        return new ViewBounds(lonMin, lonMax, latMin, latMax);
    }

    /** Expand the smaller span so the view matches the canvas aspect (same degree
     *  convention as fitBounds, clamped back inside the map). Keeps zoom in/out
     *  round-trips squeeze-free: every stored view carries identical proportions. */
    static ViewBounds fitAspect(ViewBounds view, double aspect) {
        double lonSpan = view.lonMax() - view.lonMin(), latSpan = view.latMax() - view.latMin();
        double lonMid = (view.lonMin() + view.lonMax()) / 2, latMid = (view.latMin() + view.latMax()) / 2;
        if (lonSpan / latSpan < aspect) lonSpan = latSpan * aspect;
        else latSpan = lonSpan / aspect;
        double lonMin = lonMid - lonSpan / 2, lonMax = lonMid + lonSpan / 2;
        double latMin = latMid - latSpan / 2, latMax = latMid + latSpan / 2;
        if (lonMax - lonMin >= MAP_LON_MAX - MAP_LON_MIN) { lonMin = MAP_LON_MIN; lonMax = MAP_LON_MAX; }
        if (latMax - latMin >= MAP_LAT_MAX - MAP_LAT_MIN) { latMin = MAP_LAT_MIN; latMax = MAP_LAT_MAX; }
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
                    case "denied" -> "Tile server rejected the request (HTTP 401/403) — set DCLS_CARTO_KEY.";
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
        g.fillText("Brunei Darussalam  ·  simulated phone GPS @ 35 km/h", 14, 20);
        g.setFont(Font.font("Segoe UI", 9));
        g.fillText("© OSM contributors © CARTO", w - 172, h - 8);
        drawScaleBar(g, w, h, view);
        return g;
    }

    /** Scale bar from the live view span (111.32 km per lon-degree, adjusted for latitude). */
    static void drawScaleBar(GraphicsContext g, double w, double h, ViewBounds view) {
        double latMid = (view.latMin() + view.latMax()) / 2;
        double metersPerPixel = (view.lonMax() - view.lonMin()) * 111320 * Math.cos(Math.toRadians(latMid)) / w;
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
        ViewBounds view = fitBounds(points, canvas.getWidth() / canvas.getHeight());
        GraphicsContext g = prepareMap(canvas, view);
        drawRouteOn(g, canvas.getWidth(), canvas.getHeight(), stops, moving, "#176df6", view);
    }

    static void drawRouteOn(GraphicsContext g, double width, double height, List<Stop> stops, TrackingPoint moving) {
        drawRouteOn(g, width, height, stops, moving, "#176df6", autoView(stops, moving, width / height));
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
        ViewBounds view = fitBounds(points, w / h);
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

    private static ViewBounds autoView(List<Stop> stops, TrackingPoint moving, double aspect) {
        List<double[]> points = new ArrayList<>();
        if (stops != null) for (Stop stop : stops) points.add(new double[] {stop.x(), stop.y()});
        if (moving != null) points.add(new double[] {moving.x(), moving.y()});
        return fitBounds(points, aspect);
    }
}

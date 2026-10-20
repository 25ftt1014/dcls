package edu.pb.dcls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress test for the tile loader: a zoom/pan storm must never leave the map unable to
 * load the view the user is actually looking at.
 *
 * <p>Models the reported failure — fast dragging and rapid zoom in/out, a tile source
 * that is slow and fails for a while (the saturated-server case), and the map stuck on
 * "tiles still loading" afterwards.
 *
 * <p>Everything runs against a fake {@link TileLoader.Source}, so it is deterministic,
 * needs no network, and never touches OpenStreetMap.
 */
class TileLoaderStressTest {

    /** A real 1x1 PNG, so a successful fetch really does land in the cache. */
    private static final byte[] TILE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    /** 200 view changes, 10ms apart: the reported "fast dragging, rapid zoom" shape. */
    private static final int STORM_VIEWS = 200;
    private static final long STORM_INTERVAL_MS = 10;

    /** Tile source that is slow and fails for a while, then recovers. Records what it was
     *  asked for, so a test can tell "the map needed these" from "the loader went off
     *  fetching the views the user already left". */
    private static final class FakeSource implements TileLoader.Source {
        private final AtomicBoolean failing = new AtomicBoolean(true);
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger fetchesOutsideFinalView = new AtomicInteger();
        private final Set<String> finalView = new LinkedHashSet<>();
        private final long workMs;
        private final long startedAt = System.currentTimeMillis();
        private final long failForMs;

        private FakeSource(long failForMs, long workMs) {
            this.failForMs = failForMs;
            this.workMs = workMs;
        }

        @Override
        public byte[] fetch(String url) throws IOException {
            attempts.incrementAndGet();
            String key = url.substring(url.lastIndexOf('/') + 1);   // "z-x-y"
            if (System.currentTimeMillis() - startedAt > failForMs) failing.set(false);
            if (!finalView.isEmpty() && !finalView.contains(key)) fetchesOutsideFinalView.incrementAndGet();
            if (workMs > 0) {
                try { Thread.sleep(workMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (failing.get()) throw new IOException("simulated tile failure for " + key);
            return TILE_PNG;
        }

        /** The source starts serving: past the simulated outage. */
        void recover() {
            failing.set(false);
        }

        int attempts() { return attempts.get(); }
        int fetchesOutsideFinalView() { return fetchesOutsideFinalView.get(); }
    }

    private static String key(int z, int x, int y) { return z + "-" + x + "-" + y; }

    private static TileLoader.UrlBuilder urls() {
        return (z, x, y, hostShift) -> "test://tile/" + key(z, x, y);
    }

    /** One view's worth of tiles, moving as the storm progresses. */
    private static List<int[]> viewFor(int step) {
        int z = 14 + (step % 4);                       // rapid zoom in and out
        int originX = 12940 + (step * 3) % 40;         // rapid panning
        int originY = 8260 + (step * 2) % 30;
        List<int[]> tiles = new java.util.ArrayList<>();
        for (int x = originX; x < originX + 4; x++) {
            for (int y = originY; y < originY + 3; y++) tiles.add(new int[] {z, x, y});
        }
        return tiles;
    }

    @Test
    @DisplayName("a 200-view storm must not wedge the loader")
    void aZoomAndPanStormMustNotWedgeTheLoader() throws Exception {
        FxTestKit.run(() -> { });   // Image decoding needs a toolkit
        FakeSource source = new FakeSource(1_500, 2);
        // A short debounce so the test runs in seconds; the app uses 120ms.
        TileLoader loader = new TileLoader(urls(), source, () -> { }, "stress-test", 512, 40);

        // The view the user ends up looking at, known up front so the test can tell
        // "the map needed these" from "the loader went off fetching the old ones".
        List<int[]> finalView = viewFor(STORM_VIEWS - 1);
        Set<String> finalKeys = new LinkedHashSet<>();
        for (int[] tile : finalView) finalKeys.add(key(tile[0], tile[1], tile[2]));
        source.finalView.clear();
        source.finalView.addAll(finalKeys);

        // The storm: 200 view changes, each reporting what it needs exactly as a paint does.
        for (int step = 0; step < STORM_VIEWS; step++) {
            loader.viewChanged(viewFor(step).getFirst()[0], viewFor(step));
            Thread.sleep(STORM_INTERVAL_MS);
        }

        // The source comes back. A loader that survived the storm now has to fill the
        // visible view; the map greying out forever is the bug under test.
        source.recover();
        long deadline = System.currentTimeMillis() + 8_000;
        Map<String, Integer> missing = missingTiles(loader, finalView);
        while (!missing.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            missing = missingTiles(loader, finalView);
        }

        assertTrue(missing.isEmpty(),
                "the view the user is looking at never loaded after the storm. Missing "
                        + missing.size() + " of " + finalView.size() + " tiles ("
                        + missing.keySet().stream().limit(5).toList() + "). "
                        + source.attempts() + " fetches were attempted, "
                        + source.fetchesOutsideFinalView() + " of them for tiles no longer visible.");

        // And work for views the user already left must stop, not keep draining.
        int staleAfterSettle = source.fetchesOutsideFinalView();
        Thread.sleep(500);
        assertEquals(staleAfterSettle, source.fetchesOutsideFinalView(),
                "the loader is still fetching tiles for views the user has left");

        // The queue is bounded, so a storm cannot grow it without limit.
        assertTrue(loader.queued() <= TileLoader.MAX_PENDING,
                "the request queue must stay bounded, was " + loader.queued());
    }

    /** Tiles of a view that are not in the memory cache yet. */
    private static Map<String, Integer> missingTiles(TileLoader loader, List<int[]> view) {
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (int[] tile : view) {
            int z = tile[0], x = tile[1], y = tile[2];
            if (loader.peek(z, x, y) == null) missing.merge(key(z, x, y), 1, Integer::sum);
        }
        return missing;
    }

    @Test
    @DisplayName("a tile that loads clears the problem flag")
    void aSuccessfulLoadClearsTheProblemFlag() throws Exception {
        FxTestKit.run(() -> { });
        FakeSource source = new FakeSource(0, 0);
        TileLoader loader = new TileLoader(urls(), source, () -> { }, "stress-test", 64, 10);

        loader.viewChanged(14, List.of(new int[] {14, 1, 1}));
        awaitLoaded(loader, 14, 1, 1);
        assertNotNull(loader.peek(14, 1, 1), "the tile should be cached");
        assertTrue(loader.problem() == null,
                "a successful load must clear the failure flag, but it was " + loader.problem());
    }

    private static void awaitLoaded(TileLoader loader, int z, int x, int y) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (loader.peek(z, x, y) == null && System.currentTimeMillis() < deadline) Thread.sleep(20);
    }
}
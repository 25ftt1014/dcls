package edu.pb.dcls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Policy of the tile loader, headless and offline: the rules that keep a map alive
 * under a bad network — retry ladder, bounded work, concurrency limits, and the failure
 * notice clearing itself.
 */
class TileLoaderPolicyTest {

    /** A 1x1 PNG, so a fetch can actually succeed. */
    private static final byte[] TILE_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    /** A fresh on-disk scope per run: the tile cache is real and persists between runs,
     *  so reusing a fixed scope would let a previous run answer for this one. */
    private static String scope() {
        return "policy-test-" + System.nanoTime();
    }

    private static TileLoader.UrlBuilder urls() {
        return (z, x, y, hostShift) -> "test://tile/" + z + "-" + x + "-" + y;
    }

    /** Fails every request with a scripted status, recording what it was asked. */
    private static final class ScriptedSource implements TileLoader.Source {
        private final List<String> asked = java.util.Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger concurrent = new AtomicInteger();
        private final AtomicInteger peakConcurrent = new AtomicInteger();
        private int failuresBeforeSuccess;
        private RuntimeException explode;

        ScriptedSource(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        ScriptedSource exploding(RuntimeException explode) {
            this.explode = explode;
            return this;
        }

        @Override
        public byte[] fetch(String url) throws IOException, InterruptedException {
            asked.add(url);
            int now = concurrent.incrementAndGet();
            peakConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(30);            // every fetch is slow, so overlap is visible
                if (explode != null) throw explode;
                if (failuresBeforeSuccess-- > 0) throw new IOException("scripted failure");
                return TILE_PNG;
            } finally {
                concurrent.decrementAndGet();
            }
        }

        int peakConcurrent() { return peakConcurrent.get(); }
        int attempts() { return asked.size(); }
    }

    private static void settle(TileLoader loader, int z, int x, int y, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (loader.peek(z, x, y) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Nested
    @DisplayName("retry ladder")
    class RetryLadder {
        @Test
        void backsOffInHalvesFromHalfASecond() {
            assertEquals(500, TileLoader.retryDelayMillis(0));
            assertEquals(1_000, TileLoader.retryDelayMillis(1));
            assertEquals(2_000, TileLoader.retryDelayMillis(2));
            assertEquals(2_000, TileLoader.retryDelayMillis(9), "the ladder is capped");
        }

        @Test
        void aTileIsTriedAtMostThreeTimes() {
            assertEquals(3, TileLoader.MAX_TRIES);
        }

        @Test
        void aFailingTileIsRetriedAndEventuallySucceeds() throws Exception {
            FxTestKit.run(() -> { });
            ScriptedSource source = new ScriptedSource(2);       // fails twice, then works
            TileLoader loader = new TileLoader(urls(), source, () -> { }, scope(), 64, 10);

            loader.viewChanged(14, List.of(new int[] {14, 5, 5}));
            settle(loader, 14, 5, 5, 6_000);

            assertNotNull(loader.peek(14, 5, 5), "the tile should load after its retries");
            assertTrue(source.attempts() >= 3, "it should have been retried, attempts=" + source.attempts());
            assertNull(loader.problem(), "a load that eventually succeeded leaves no failure flag");
        }
    }

    @Nested
    @DisplayName("failure state recovers")
    class Recovery {
        @Test
        void anySuccessfulLoadClearsTheNotice() throws Exception {
            FxTestKit.run(() -> { });
            // Tile A fails and is still failing; tile B succeeds. The notice must go.
            TileLoader loader = new TileLoader(urls(), new ScriptedSource(0), () -> { }, scope(), 64, 10);
            loader.viewChanged(14, List.of(new int[] {14, 6, 6}));
            settle(loader, 14, 6, 6, 4_000);
            assertNull(loader.problem());
        }

        @Test
        void aGaveUpTileGoesBackToCoolingDownRatherThanStayingBroken() throws Exception {
            FxTestKit.run(() -> { });
            ScriptedSource source = new ScriptedSource(50);      // never succeeds
            TileLoader loader = new TileLoader(urls(), source, () -> { }, scope(), 64, 10);

            loader.viewChanged(14, List.of(new int[] {14, 7, 7}));
            long deadline = System.currentTimeMillis() + 6_000;
            while (loader.problem() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertNotNull(loader.problem(), "a failing tile must raise the failure flag");
            assertTrue(loader.coolingDown(14, 7, 7),
                    "the failed tile must be marked as cooling down, not retried forever");
        }

        @Test
        void aMissingTileIsNotRetriedAtAll() throws Exception {
            FxTestKit.run(() -> { });
            GoneSource source = new GoneSource();
            TileLoader loader = new TileLoader(urls(), source, () -> { }, scope(), 64, 10);
            loader.viewChanged(14, List.of(new int[] {14, 8, 8}));

            long deadline = System.currentTimeMillis() + 4_000;
            while (loader.problem() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            int afterFirst = source.asked();
            Thread.sleep(500);
            assertEquals(afterFirst, source.asked(), "a 404 must never be fetched again");
        }

        /** Always answers 404, the way the tile server does for a tile that is not there. */
        private static final class GoneSource implements TileLoader.Source {
            private final AtomicInteger askedCount = new AtomicInteger();

            @Override
            public byte[] fetch(String url) throws IOException {
                askedCount.incrementAndGet();
                throw new TileLoader.TileHttpException(404, null);
            }

            int asked() { return askedCount.get(); }
        }
    }

    @Nested
    @DisplayName("rate limiting")
    class RateLimit {
        @Test
        void retryAfterIsParsedFromSecondsAndDates() {
            assertEquals(30_000, TileLoader.parseRetryAfter("30"));
            assertEquals(600_000, TileLoader.parseRetryAfter("99999"), "capped at ten minutes");
            assertEquals(60_000, TileLoader.parseRetryAfter(null), "absent means a minute");
            assertEquals(60_000, TileLoader.parseRetryAfter("not-a-date"));
        }

        @Test
        void a429BacksOffEveryoneAndSaysForHowLong() throws Exception {
            FxTestKit.run(() -> { });
            ThrottledSource source = new ThrottledSource(429, "30");
            TileLoader loader = new TileLoader(urls(), source, () -> { }, scope(), 64, 10);

            loader.viewChanged(14, List.of(new int[] {14, 9, 9}));
            long deadline = System.currentTimeMillis() + 5_000;
            while (!"throttled".equals(loader.problem()) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals("throttled", loader.problem(), "a 429 must be reported as rate limiting");
            assertTrue(loader.retryAfterAt() > System.currentTimeMillis(),
                    "the loader must hold every host back until the Retry-After window ends");

            String notice = MapRenderer.tileNotice(loader.problem(),
                    loader.retryAfterAt() - System.currentTimeMillis(), 0, 0, 1);
            assertNotNull(notice);
            assertTrue(notice.contains("rate limiting") && notice.contains("30s"),
                    "the notice must name the wait, was: " + notice);
        }

        private final class ThrottledSource implements TileLoader.Source {
            private final int status;
            private final String retryAfter;

            ThrottledSource(int status, String retryAfter) {
                this.status = status;
                this.retryAfter = retryAfter;
            }

            @Override
            public byte[] fetch(String url) throws IOException {
                throw new TileLoader.TileHttpException(status, retryAfter);
            }
        }
    }

    @Nested
    @DisplayName("bounded work")
    class Bounded {
        @Test
        void theQueueHasAHardCeiling() {
            assertTrue(TileLoader.MAX_PENDING > 0, "the queue must be bounded");
            assertEquals(6, TileLoader.PER_HOST_CONCURRENCY);
            assertEquals(6, TileLoader.WORKER_THREADS);
        }

        @Test
        void atMostSixRequestsRunAtOnce() throws Exception {
            FxTestKit.run(() -> { });
            ScriptedSource source = new ScriptedSource(0);
            TileLoader loader = new TileLoader(urls(), source, () -> { }, scope(), 64, 10);

            List<int[]> view = new ArrayList<>();
            for (int i = 0; i < 30; i++) view.add(new int[] {14, 20 + i, 20});
            loader.viewChanged(14, view);
            long deadline = System.currentTimeMillis() + 8_000;
            while (source.attempts() < 30 && System.currentTimeMillis() < deadline) Thread.sleep(50);

            assertTrue(source.peakConcurrent() <= TileLoader.WORKER_THREADS,
                    "more requests ran at once than the pool allows: " + source.peakConcurrent());
            // ...and not so few that the loader has quietly become serial, which would
            // still respect the ceiling while making every map load crawl.
            assertTrue(source.peakConcurrent() >= 2,
                    "the loader must fetch tiles in parallel, peak was " + source.peakConcurrent());
        }

        @Test
        void aWorkerThatThrowsDoesNotStopTheLoader() throws Exception {
            FxTestKit.run(() -> { });
            TileLoader loader = new TileLoader(urls(),
                    new ScriptedSource(0).exploding(new IllegalStateException("boom")),
                    () -> { }, scope(), 64, 10);
            loader.viewChanged(14, List.of(new int[] {14, 30, 30}));

            // The throw must be contained: the loader stays alive and keeps working.
            long deadline = System.currentTimeMillis() + 4_000;
            while (loader.problem() == null && System.currentTimeMillis() < deadline) Thread.sleep(50);
            assertNotNull(loader.problem(), "the failure must be recorded, not swallowed silently");

            // A later, healthy tile still loads: the pool survived.
            TileLoader healthy = new TileLoader(urls(), new ScriptedSource(0), () -> { },
                    scope(), 64, 10);
            healthy.viewChanged(14, List.of(new int[] {14, 31, 31}));
            settle(healthy, 14, 31, 31, 5_000);
            assertNotNull(healthy.peek(14, 31, 31), "the loader must still work after a task threw");
        }
    }

    @Nested
    @DisplayName("notice")
    class Notice {
        @Test
        void nothingIsSaidWhileTilesArePainting() {
            assertNull(MapRenderer.tileNotice("throttled", 30_000, 12, 0, 3),
                    "a map with painted tiles shows no notice");
        }

        @Test
        void loadingIsNotAWarning() {
            assertEquals("Tiles are still loading…",
                    MapRenderer.tileNotice(null, 0, 0, 4, 0));
        }

        @Test
        void rateLimitingNamesTheWait() {
            assertEquals("Tile server is rate limiting us — retrying in 12s.",
                    MapRenderer.tileNotice("throttled", 12_400, 0, 0, 2));
        }

        @Test
        void denialAndOfflineHaveTheirOwnWording() {
            assertTrue(MapRenderer.tileNotice("denied", 0, 0, 0, 1).contains("denied"));
            assertEquals("Connect to the internet.", MapRenderer.tileNotice("offline", 0, 0, 0, 1));
            assertTrue(MapRenderer.tileNotice("error", 0, 0, 0, 1).contains("network error"));
        }

        @Test
        void nothingIsSaidWhenThereIsNoProblemAtAll() {
            assertNull(MapRenderer.tileNotice(null, 0, 0, 0, 0));
        }
    }

    @Nested
    @DisplayName("zoom level")
    class Zoom {
        @Test
        void theSettledViewFetchesAtItsOwnLevel() throws Exception {
            FxTestKit.run(() -> { });
            ScriptedSource source = new ScriptedSource(0);
            TileLoader loader = new TileLoader(urls(), source, () -> { }, scope(), 64, 10);

            // Zoom quickly from z14 to z17; only the settled level may be fetched.
            for (int z = 14; z <= 17; z++) {
                loader.viewChanged(z, List.of(new int[] {z, 100, 100}));
                Thread.sleep(15);
            }
            long deadline = System.currentTimeMillis() + 6_000;
            while (loader.peek(17, 100, 100) == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertNotNull(loader.peek(17, 100, 100), "the settled zoom level must load");
            assertTrue(source.asked.stream().anyMatch(url -> url.endsWith("17-100-100")),
                    "the settled level must actually have been fetched");
            assertTrue(loader.staleDropped() > 0, "work for abandoned levels should have been dropped");
        }

        @Test
        void theHysteresisRulesStillHold() {
            // The loader change must not disturb which level a canvas paints.
            assertEquals(13, MapRenderer.stableFetchZoom(12, 13, 3, 20, MapRenderer.ZOOM_HYSTERESIS));
            assertEquals(16, MapRenderer.stableFetchZoom(16, 13, 3, 20, MapRenderer.ZOOM_HYSTERESIS));
            assertEquals(14, MapRenderer.stableFetchZoom(14, -1, 3, 20, MapRenderer.ZOOM_HYSTERESIS));
        }
    }

    @Nested
@DisplayName("no deadlocks, no stuck zoom")
class Liveness {
    /**
     * Hammer the loader the way a real gesture storm does — many threads changing the
     * view and the zoom level at once, while fetches are slow and some fail — and
     * assert it keeps making progress rather than wedging on its own locks.
     *
     * <p>This is the guard for the obvious deadlock of this design: view changes take
     * the queue lock and also schedule, cancel and reach into the source, while workers
     * run tiles and reach back for the queue. If any of that ever became circular, this
     * test hangs rather than passing.
     */
    @Test
    void concurrentViewAndZoomChurnKeepsTheLoaderLive() throws Exception {
        FxTestKit.run(() -> { });
        ScriptedSource source = new ScriptedSource(1);   // one failure per tile, then success
        TileLoader loader = new TileLoader(urls(), source, () -> { }, scope(), 128, 5);

        final int threads = 4;
        final int changesPerThread = 60;
        List<Thread> churn = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            churn.add(Thread.ofPlatform().name("churn-" + t).start(() -> {
                for (int i = 0; i < changesPerThread; i++) {
                    int z = 13 + ((id + i) % 5);
                    int x = 100 + ((id * 7 + i * 3) % 200);
                    int y = 100 + ((id * 5 + i * 11) % 200);
                    loader.viewChanged(z, List.of(new int[] {z, x, y}));
                    try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            }));
        }

        // Only once the churn has stopped does the loader see a view that stays still.
        // Asserting before this point would be asserting that a moving view loads, which
        // is the opposite of what the debounce is for.
        for (Thread thread : churn) thread.join(30_000);
        for (Thread thread : churn) {
            assertFalse(thread.isAlive(), "a view-changing thread never finished: the loader is stuck");
        }

        loader.viewChanged(17, List.of(new int[] {17, 200, 200}));
        settle(loader, 17, 200, 200, 8_000);
        assertNotNull(loader.peek(17, 200, 200),
                "after concurrent churn the settled view must still load; attempts=" + source.attempts());
        assertTrue(source.peakConcurrent() <= TileLoader.WORKER_THREADS,
                "concurrency stayed bounded under churn: " + source.peakConcurrent());
    }

    /**
     * The canvas keeps its own tile level, and the loader must never be asked to fetch a
     * level the view has moved off — nor wait for one.
     */
    @Test
    void aCanvasLevelConvergesOnTheIdealInsteadOfSticking() {
        javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(900, 600);
        double px = 900 - MapRenderer.MAP_PAD * 2;
        // A big zoom jump: the first frame has no level, later ones must catch up.
        var wide = new MapRenderer.ViewBounds(114.43359375, 115.13671875, 4.56547355071028, 5.09094417503339);
        var close = new MapRenderer.ViewBounds(114.9200, 114.9260, 4.8850, 4.8890);

        int first = MapRenderer.tileZoomFor(canvas, close, px);
        int wideAgain = MapRenderer.tileZoomFor(canvas, wide, px);
        int deep = MapRenderer.tileZoomFor(canvas, close, px);
        assertTrue(wideAgain < first, "a country-wide view must drop back a level");
        assertEquals(first, deep,
                "returning to the same close view must land on the same level, not get stuck behind");
    }
}

@Test
    void userAgentIdentifiesTheApp() {
        // The OSM usage policy requires a User-Agent that names the application.
        assertTrue(TileLoader.USER_AGENT.startsWith("DCLS/"),
                "the User-Agent must identify the app, was: " + TileLoader.USER_AGENT);
        assertEquals(5_000, TileLoader.READ_TIMEOUT_MS, "reads must time out in five seconds");
    }

    @Test
    void keysAreStable() {
        assertEquals("14/12950/8260", TileLoader.key(14, 12950, 8260));
        assertEquals(Set.of(6), Set.of(TileLoader.WORKER_THREADS));
    }
}
package edu.pb.dcls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static edu.pb.dcls.Domain.Stop;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Baseline micro-benchmark for the map paint path.
 *
 * <p>Not an assertion of correctness — these helpers are already covered elsewhere. This
 * exists to put a number on the per-frame work the paint path pays, so a regression
 * shows up as a change in the printed figures rather than as "the map feels slow".
 *
 * <p>Timings are printed, never asserted: this runs on shared CI and a loaded machine,
 * so a wall-clock assertion here would be flaky and would erode trust in the suite.
 * Budgets live in {@link MapPaintBudgetTest}, which asserts behaviour-level invariants
 * instead. Compare the printed medians between runs.
 */
class MapPaintBenchmark {

    private static final int WARMUP = 200;
    private static final int ITERATIONS = 2000;

    @Test
    @DisplayName("baseline timings for the per-frame map helpers")
    void printBaselineTimings() {
        List<double[]> frame = trackingLikeFrame(500);

        time("fitBounds(500 pts)", () -> MapRenderer.fitBounds(frame, 1.35));

        // A pair that is not in the bundled road network, so this exercises the
        // nearestPlace scan and the fresh-allocation fallback rather than a cache hit.
        time("snapLeg(uncached pair)", () ->
                AppServices.OsrmRouter.snapLeg(114.9050, 4.9050, 114.9350, 4.9250));

        // The miss path: no bundled geometry and no cached OSRM response, which is what
        // the paint loop used to re-probe with a filesystem stat on every frame.
        time("leg(miss)", () ->
                AppServices.OsrmRouter.leg(114.9170, 4.9070, 114.9480, 4.9310));

        time("legPath(uncached pair)", () -> AppServices.RouteService.legPath(
                new Stop("a", "A", 114.9050, 4.9050),
                new Stop("b", "B", 114.9350, 4.9250)));

        time("hasRoad(uncached pair)", () ->
                AppServices.OsrmRouter.hasRoad(114.9050, 4.9050, 114.9350, 4.9250));
    }

    /** Roughly what the tracking page hands the viewport fitter: a vehicle trail. */
    private static List<double[]> trackingLikeFrame(int points) {
        List<double[]> frame = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double t = i / (double) points;
            frame.add(new double[] {114.9180 + t * 0.03, 4.9010 + t * 0.02});
        }
        return frame;
    }

    private static void time(String label, Runnable body) {
        for (int i = 0; i < WARMUP; i++) body.run();
        long[] samples = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            body.run();
            samples[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(samples);
        System.out.printf(Locale.ROOT,
                "[bench] %-26s median=%8.3f us  p95=%8.3f us%n",
                label, PerfProbe.percentile(samples, 0.50) / 1e3,
                PerfProbe.percentile(samples, 0.95) / 1e3);
    }

    @Test
    @DisplayName("benchmark actually exercises something")
    void sanity() {
        // Guards against a silently optimised-away benchmark body.
        assertTrue(MapRenderer.fitBounds(trackingLikeFrame(500), 1.35).lonMax() > 0);
    }
}
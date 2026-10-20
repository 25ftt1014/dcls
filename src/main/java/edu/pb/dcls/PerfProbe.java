package edu.pb.dcls;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opt-in frame-time probe for the map paint path.
 *
 * <p>Disabled unless the JVM is started with {@code -Ddcls.perf=true}, in which case
 * {@link #enabled()} short-circuits on a static final boolean so an enabled-off build
 * pays a predictable single branch per record and nothing else.
 *
 * <p>Usage: {@code mvn javafx:run -Ddcls.perf=true} (or set MAVEN_OPTS). Two phases are
 * recorded per paint — {@code tiles} for {@link MapRenderer#prepareMap} and
 * {@code overlay} for the caller-supplied painter — because a fix belongs to whichever
 * half dominates: tile work points at the tile loop, overlay work at route/marker
 * projection. A summary is printed every {@value #REPORT_EVERY} completed paints and
 * again on JVM shutdown.
 *
 * <p>This is a development tool, not a production feature: it has no effect unless the
 * property is set, and it never touches map state.
 */
final class PerfProbe {
    private PerfProbe() { }

    static final String PROPERTY = "dcls.perf";
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
    private static final int CAPACITY = 8192;
    private static final int REPORT_EVERY = 200;

    /** Ring buffers of per-phase nanosecond durations; -1 marks an unused slot. */
    private static final long[] TILES = new long[CAPACITY];
    private static final long[] OVERLAY = new long[CAPACITY];
    private static final AtomicInteger COUNT = new AtomicInteger();
    private static final AtomicInteger REPORTED = new AtomicInteger();

    static {
        if (ENABLED) {
            Arrays.fill(TILES, -1);
            Arrays.fill(OVERLAY, -1);
            Runtime.getRuntime().addShutdownHook(new Thread(PerfProbe::report, "dcls-perf-report"));
            System.out.println("[perf] enabled — recording map paint times");
        }
    }

    static boolean enabled() {
        return ENABLED;
    }

    /** Records one completed paint. Called once per frame with both phase totals. */
    static void record(long tilesNanos, long overlayNanos) {
        if (!ENABLED) return;
        int i = COUNT.getAndIncrement();
        if (i < CAPACITY) {
            TILES[i] = tilesNanos;
            OVERLAY[i] = overlayNanos;
        }
        if (i - REPORTED.get() >= REPORT_EVERY) report();
    }

    /** Percentiles over everything recorded so far, in milliseconds. */
    static synchronized void report() {
        if (!ENABLED) return;
        int total = Math.min(COUNT.get(), CAPACITY);
        if (total == 0) return;
        REPORTED.set(total);
        long[] tiles = Arrays.copyOf(TILES, total);
        long[] overlay = Arrays.copyOf(OVERLAY, total);
        print("tiles", tiles);
        print("overlay", overlay);
        long[] both = new long[total];
        for (int i = 0; i < total; i++) both[i] = tiles[i] + overlay[i];
        print("total", both);
    }

    private static void print(String label, long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        System.out.printf("[perf] %-7s n=%d p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms%n",
                label, sorted.length,
                percentile(sorted, 0.50) / 1e6, percentile(sorted, 0.95) / 1e6,
                percentile(sorted, 0.99) / 1e6, sorted[sorted.length - 1] / 1e6);
    }

    /** Nearest-rank percentile over an ascending array. */
    static double percentile(long[] sortedAscending, double fraction) {
        if (sortedAscending.length == 0) return 0;
        int rank = (int) Math.ceil(fraction * sortedAscending.length) - 1;
        return sortedAscending[Math.max(0, Math.min(rank, sortedAscending.length - 1))];
    }
}
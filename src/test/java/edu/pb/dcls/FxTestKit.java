package edu.pb.dcls;

import javafx.application.Platform;
import org.junit.jupiter.api.Assumptions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One JavaFX toolkit for the whole test JVM.
 *
 * <p>Two suites build real pages (Orders and Live tracking), and the toolkit can only
 * be started once per JVM — a second {@code Platform.startup} throws, and a toolkit
 * that has been exited never comes back. So startup is tracked here and the second
 * caller simply queues its work on the running toolkit.
 *
 * <p>The toolkit is deliberately never exited: a JavaFX application thread that has
 * been shut down would lock out whichever suite runs next, and leaving it running
 * costs nothing because Surefire forks are torn down at the end of the build.
 */
final class FxTestKit {

    private static boolean started;

    private FxTestKit() { }

    /** Runs {@code action} on the FX thread and rethrows anything it throws on the
     *  caller's thread. Skips the calling test when no toolkit can be started at all
     *  (a headless machine without a display). */
    static void run(Runnable action) throws Exception {
        ensureStarted();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { action.run(); }
            catch (Throwable thrown) { failure.set(thrown); }
            finally { done.countDown(); }
        });
        if (!done.await(120, TimeUnit.SECONDS)) throw new IllegalStateException("FX action timed out");
        if (failure.get() instanceof AssertionError assertion) throw assertion;
        if (failure.get() != null) throw new AssertionError("FX action failed", failure.get());
    }

    private static synchronized void ensureStarted() {
        if (started) return;
        try {
            Platform.startup(() -> { });
            started = true;
        } catch (IllegalStateException alreadyRunning) {
            // Another suite in this JVM got here first; reuse its toolkit.
            started = true;
        } catch (Throwable unavailable) {
            Assumptions.abort("no JavaFX toolkit available in this environment: " + unavailable);
        }
    }
}
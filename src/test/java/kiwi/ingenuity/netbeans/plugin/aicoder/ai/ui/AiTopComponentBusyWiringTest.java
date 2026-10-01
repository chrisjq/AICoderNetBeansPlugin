package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Source-level contract tests for AiTopComponent's shared busy foundation.
 *
 * <p>
 * The top component eagerly creates a real backend, so these tests pin the control-flow invariants at the
 * wiring boundary. Each assertion checks an ordering or exact guard that a mutation would break.</p>
 */
class AiTopComponentBusyWiringTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/kiwi/ingenuity/netbeans/plugin/aicoder/ai/ui/AiTopComponent.java");

    private static String source() throws IOException {
        return Files.readString(SOURCE);
    }

    private static int countOf(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static String method(String source, String signature, String nextSignature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing method: " + signature);
        int end = source.indexOf(nextSignature, start);
        assertTrue(end > start, "missing method boundary after: " + signature);
        return source.substring(start, end);
    }

    @Test
    void readyStartsTheSessionClockOnlyOnce() throws IOException {
        String source = source();
        int ready = source.indexOf("case READY -> {");
        assertTrue(ready >= 0, "missing READY branch");
        int next = source.indexOf("case STOPPED ->", ready);
        assertTrue(next > ready, "missing STOPPED branch after READY");
        String branch = source.substring(ready, next);

        assertEquals(1, countOf(branch, "if (!sessionClockStarted)"),
                "READY must have one guarded clock-start decision");
        int guard = branch.indexOf("if (!sessionClockStarted)");
        int startClock = branch.indexOf("infoBar.startSessionClock()", guard);
        int markStarted = branch.indexOf("sessionClockStarted = true", startClock);
        assertTrue(startClock > guard, "clock start must be inside the guard");
        assertTrue(markStarted > startClock, "the started marker must follow clock start");
    }

    @Test
    void stopUsesManagerBusyAsTheOnlyReleaseGuard() throws IOException {
        String source = source();
        String cancel = method(source, "private void cancelCurrentRequest()", "private void drainPendingInteractions()");
        assertTrue(cancel.contains("boolean backendWasBusy = aiBackend != null && aiBackend.isBusy();"),
                "Stop must use the manager busy state as its escape hatch when a send never reaches processing");
        int guard = cancel.indexOf("if (!backendWasBusy)");
        int leave = cancel.indexOf("leaveBusy()", guard);
        assertTrue(guard >= 0 && leave > guard,
                "Stop may release busy only when the combined busy predicate is false");
        assertEquals(1, countOf(cancel, "leaveBusy()"),
                "the cancel path must have one conditional release, not an unconditional release");
    }

    @Test
    void turnCompletionDoesNotUnlockInFlightWork() throws IOException {
        String source = source();
        String ordinary = method(source, "else if (event instanceof TurnCompleteEvent)", "else if (event instanceof PolicyRefusalEvent");
        String guard = "(aiBackend == null || !aiBackend.isWorkInFlight())";
        assertTrue(ordinary.contains(guard), "turn completion must keep in-flight work (e.g. compaction) busy");
        assertTrue(ordinary.indexOf(guard) < ordinary.indexOf("flushPendingNotifications()"),
                "turn completion must check work-in-flight before consuming notices");
        int explain = ordinary.indexOf("explainInboxInterruptIfNeeded()");
        assertTrue(explain >= 0, "turn completion must offer the empty-queue explanation");
        assertTrue(ordinary.indexOf(guard) < explain,
                "turn completion must check work-in-flight before explaining an empty inbox: the notice is a submitted turn");
    }

    @Test
    void readyAfterWorkFlushesOrExplainsQueuedNotices() throws IOException {
        String source = source();
        int ready = source.indexOf("case READY -> {");
        int next = source.indexOf("case STOPPED ->", ready);
        assertTrue(ready >= 0 && next > ready, "missing READY/STOPPED boundaries");
        String branch = source.substring(ready, next);
        assertTrue(branch.contains("if (closesWork && (flushPendingNotifications() || explainInboxInterruptIfNeeded()))"),
                "work-closing READY must flush or explain notices after compaction");
    }

    @Test
    void busyStatusPassesCancellableToTheBusyEntryPoint() throws IOException {
        String source = source();
        int busy = source.indexOf("case BUSY ->");
        assertTrue(busy >= 0, "missing BUSY status branch");
        int ready = source.indexOf("case READY ->", busy);
        assertTrue(ready > busy, "missing READY branch after BUSY");
        String branch = source.substring(busy, ready);
        assertTrue(branch.contains("enterBusy(se.cancellable(), se.text())"),
                "BUSY must carry its cancellable flag into the UI busy state");
    }

    @Test
    void pendingNotificationsGuardOnBusyRatherThanProcessing() throws IOException {
        String source = source();
        String flush = method(source, "private boolean flushPendingNotifications(", "private void");
        assertTrue(flush.contains("aiBackend != null && aiBackend.isBusy()"),
                "notification flush must defer during turns and non-turn work");
        assertEquals(0, countOf(flush, "aiBackend.isProcessing()"),
                "flush must not use the turn-only processing flag");
    }
}

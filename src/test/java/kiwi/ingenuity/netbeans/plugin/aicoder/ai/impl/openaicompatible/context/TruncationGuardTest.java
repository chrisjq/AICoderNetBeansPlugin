package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.TruncationGuard.Action;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.TruncationGuard.Report;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TruncationGuardTest {

    private static final String REMEDY = "increase the context length in LM Studio";

    private static Report report(int window, boolean override, int reported, Integer previous, int estimate,
                                 int previousEstimate) {
        return new Report(window, override, reported, previous, estimate, previousEstimate, REMEDY);
    }

    private static Report nearWindow() {
        return report(8192, true, 8100, null, 8000, 7900);
    }

    @Test
    @Timeout(5)
    void aNormalReportIsCalibrated() {
        TruncationGuard guard = new TruncationGuard(0.02);
        guard.beginTurn();

        assertEquals(Action.CALIBRATE, guard.onReport(report(8192, true, 4000, null, 3000, 0)).action());
    }

    @Test
    @Timeout(5)
    void aReportNearTheWindowCompactsOnceInTheTurn() {
        TruncationGuard guard = new TruncationGuard(0.02);
        guard.beginTurn();

        assertEquals(Action.COMPACT_ONCE, guard.onReport(nearWindow()).action());
        assertEquals(Action.IGNORE_CALIBRATION, guard.onReport(nearWindow()).action(),
                     "a second suspicious report in the same turn must not compact again");
        assertFalse(guard.isPaused());
    }

    @Test
    @Timeout(5)
    void aRecurrenceInALaterTurnPausesWithTheMessage() {
        TruncationGuard guard = new TruncationGuard(0.02);
        guard.beginTurn();
        guard.onReport(nearWindow());
        guard.beginTurn();

        TruncationGuard.Decision decision = guard.onReport(nearWindow());

        assertEquals(Action.PAUSE_WITH_MESSAGE, decision.action());
        assertTrue(decision.message().contains("The server seems to cut the prompt at about 8100 tokens"),
                   decision.message());
        assertTrue(decision.message().contains("context-window override (8192)"), decision.message());
        assertTrue(decision.message().contains(REMEDY), decision.message());
        assertTrue(guard.isPaused());
    }

    @Test
    @Timeout(5)
    void whilePausedSuspiciousReportsAreIgnoredAndNormalOnesStillCalibrate() {
        TruncationGuard guard = new TruncationGuard(0.02);
        guard.beginTurn();
        guard.onReport(nearWindow());
        guard.beginTurn();
        guard.onReport(nearWindow());
        guard.beginTurn();

        assertEquals(Action.IGNORE_CALIBRATION, guard.onReport(nearWindow()).action());
        assertEquals(Action.CALIBRATE, guard.onReport(report(8192, true, 4000, null, 3000, 0)).action());
    }

    @Test
    @Timeout(5)
    void changingTheModelLiftsThePauseAndStartsOver() {
        TruncationGuard guard = new TruncationGuard(0.02);
        guard.resetOn("", "model-a", "http://localhost:1234");
        guard.beginTurn();
        guard.onReport(nearWindow());
        guard.beginTurn();
        guard.onReport(nearWindow());
        assertTrue(guard.isPaused());

        guard.resetOn("", "model-b", "http://localhost:1234");
        guard.beginTurn();

        assertFalse(guard.isPaused());
        assertEquals(Action.COMPACT_ONCE, guard.onReport(nearWindow()).action());
    }

    @Test
    @Timeout(5)
    void resettingToTheSameIdentityKeepsThePause() {
        TruncationGuard guard = new TruncationGuard(0.02);
        guard.resetOn("", "model-a", "http://localhost:1234");
        guard.beginTurn();
        guard.onReport(nearWindow());
        guard.beginTurn();
        guard.onReport(nearWindow());

        guard.resetOn("", "model-a", "http://localhost:1234");

        assertTrue(guard.isPaused());
    }

    @Test
    @Timeout(5)
    void aReportThatShrankWhileTheEstimateGrewIsSuspicious() {
        TruncationGuard guard = new TruncationGuard(0.02);
        guard.beginTurn();

        assertEquals(Action.COMPACT_ONCE,
                     guard.onReport(report(8192, true, 4000, 5000, 6000, 5500)).action());
    }

    @Test
    @Timeout(5)
    void aReportThatShrankWhileTheEstimateDidNotGrowIsNotSuspicious() {
        TruncationGuard guard = new TruncationGuard(0.02);
        guard.beginTurn();

        assertEquals(Action.CALIBRATE,
                     guard.onReport(report(8192, true, 4000, 5000, 4000, 4500)).action());
    }

    @Test
    @Timeout(5)
    void theMessageWithoutAnOverrideNamesTheDetectedWindow() {
        TruncationGuard guard = new TruncationGuard(0.02);
        guard.resetOn("", "model-a", "http://localhost:1234");
        guard.beginTurn();
        guard.onReport(report(8192, false, 8100, null, 8000, 7900));
        guard.beginTurn();

        TruncationGuard.Decision decision = guard.onReport(report(8192, false, 8100, null, 8000, 7900));

        assertEquals(Action.PAUSE_WITH_MESSAGE, decision.action());
        assertTrue(decision.message().contains("less than the detected window (8192)"), decision.message());
        assertFalse(decision.message().contains("your context-window override"), decision.message());
    }
}

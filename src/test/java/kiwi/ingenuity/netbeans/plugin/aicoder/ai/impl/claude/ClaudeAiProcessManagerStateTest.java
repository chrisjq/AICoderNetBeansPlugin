package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.session.ClaudePersistentSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClaudeAiProcessManagerStateTest {

    private RecordingEventListener events;
    private TestableClaudeAiProcessManager manager;
    private File workDir;

    private static void awaitTrue(BooleanSupplier cond, String desc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timeout waiting for " + desc);
            }
            Thread.sleep(20);
        }
    }

    @BeforeEach
    void setup() throws IOException {
        events = new RecordingEventListener();
        manager = new TestableClaudeAiProcessManager(events);
        workDir = Files.createTempDirectory("claude-test").toFile();
        manager.setupForTest();
    }

    @AfterEach
    void teardown() {
        manager.stop();
    }

    @Test
    void awaitingCancelResultGate() throws InterruptedException {
        manager.cancelWatchdogMillis = 10000;
        manager.sendPrompt("first prompt", workDir, List.of());
        assertTrue(manager.isProcessing());

        manager.interrupt(InterruptTypeEnum.Cancel);
        assertTrue(manager.isAwaitingCancelResult());
        assertFalse(manager.isProcessing());

        manager.getSession().process().destroyForcibly();
        awaitTrue(() -> !manager.isAwaitingCancelResult(), "cancel gate clear");

        assertFalse(manager.isAwaitingCancelResult());
        manager.sendPrompt("third prompt", workDir, List.of());
        assertTrue(manager.isProcessing());
    }

    @Test
    void suppressionFlagReset() throws InterruptedException {
        manager.sendPrompt("turn 1", workDir, List.of());
        assertTrue(manager.isProcessing());

        manager.interrupt(InterruptTypeEnum.Mail);
        assertTrue(manager.isTurnInterrupted());

        events.clear();
        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"error_during_execution\"}");
        awaitTrue(() -> !manager.isTurnInterrupted(), "turn interrupt flag clear");

        assertFalse(manager.isTurnInterrupted());
        Thread.sleep(100); // Fixed short sleep: asserting absence of INTERRUPTED event; parser already processed
        assertFalse(events.hasEvent(StatusEvent.class, e -> ((StatusEvent) e).type() == StatusEventTypeEnum.INTERRUPTED));
        events.clear();

        manager.sendPrompt("turn 2", workDir, List.of());
        assertTrue(manager.isProcessing());
        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"success\"}");
        awaitTrue(() -> events.hasEvent(TurnCompleteEvent.class), "TurnCompleteEvent");
    }

    @Test
    void failedSendRetry() throws InterruptedException {
        manager.setFirstSessionDead(true);

        manager.sendPrompt("prompt", workDir, List.of());
        assertEquals(2, manager.getLaunchCount());
        assertTrue(manager.isProcessing());
    }

    @Test
    void compactUsesOneBusyReadyLifecycleAndHidesItsTurn() throws InterruptedException {
        assertTrue(manager.compact(workDir));
        assertTrue(manager.isWorkInFlight());
        assertTrue(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.BUSY));

        manager.getSession().sendRawLine(
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hidden compact output\"}]}}");
        manager.getSession().sendRawLine("{\"type\":\"system\",\"subtype\":\"compact_boundary\"}");
        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"success\"}");
        awaitTrue(() -> !manager.isWorkInFlight(), "compaction complete");

        assertFalse(events.hasEvent(TextDeltaEvent.class), "the /compact turn must not leak chat text");
        assertFalse(events.hasEvent(TurnCompleteEvent.class), "runWork's READY, not TurnCompleteEvent, closes compaction");
        assertTrue(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.READY
                     && "Conversation compacted".equals(((StatusEvent) e).text())));
    }

    @Test
    void compactWithoutBoundaryIsAReadyNoOp() throws InterruptedException {
        assertTrue(manager.compact(workDir));
        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"success\"}");
        awaitTrue(() -> !manager.isWorkInFlight(), "no-op compaction complete");

        assertTrue(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.READY
                     && "Nothing to compact".equals(((StatusEvent) e).text())),
                "a completed /compact turn without compact_boundary must stay green as Nothing to compact");
        assertFalse(events.hasEvent(TurnCompleteEvent.class),
                "a no-op compaction must still be closed only by READY");
    }

    @Test
    void stopDuringCompactionClosesOnlyWithFailed() {
        assertTrue(manager.compact(workDir));
        events.clear();

        manager.stop();

        assertTrue(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.FAILED),
                "stop during compaction must emit one FAILED work closer");
        assertEquals(1, events.countStatusClosers(),
                "stop during compaction must emit exactly one work closer");
        assertFalse(events.hasEvent(TurnCompleteEvent.class),
                "stop during compaction must not leak the internal turn completion");
    }

    @Test
    void timedOutCompactionDoesNotSuppressTheNextSessionsTurn() throws InterruptedException {
        assertTrue(manager.compact(workDir, 1));
        awaitTrue(() -> !manager.isWorkInFlight(), "compaction timeout");

        // The CLI never answers /compact: Stop, and the cancel watchdog recycles the session. No late compact result
        // ever arrives to consume the stale suppression, so only the timeout can have released it.
        manager.cancelWatchdogMillis = 200;
        manager.interrupt(InterruptTypeEnum.Cancel);
        awaitTrue(() -> !manager.isAwaitingCancelResult(), "watchdog recycles the session");
        events.clear();

        manager.sendPrompt("next real turn", workDir, List.of());
        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"success\"}");
        awaitTrue(() -> events.hasEvent(TurnCompleteEvent.class),
                "a timed-out compaction must not suppress the next real turn's TurnCompleteEvent");
    }

    @Test
    void nonZeroExitDuringCompactionHasExactlyOneFailedCloser() throws InterruptedException {
        assertTrue(manager.compact(workDir));
        events.clear();

        manager.getSession().process().destroyForcibly();
        awaitTrue(() -> !manager.isWorkInFlight(), "exit closes compaction");

        assertEquals(1, events.countStatusClosers(),
                "a non-zero process exit during compaction must emit exactly one work closer");
        assertTrue(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.FAILED),
                "the sole closer for a non-zero compaction exit must be FAILED, not EXITED");
        assertFalse(events.hasEvent(TurnCompleteEvent.class),
                "an exiting internal compact turn must not leak TurnCompleteEvent");
    }

    @Test
    void zeroExitDuringCompactionHasExactlyOneFailedCloser() throws Exception {
        assertTrue(manager.compact(workDir));
        events.clear();

        manager.getSession().process().getOutputStream().close();
        awaitTrue(() -> !manager.isWorkInFlight(), "clean exit closes compaction");
        Thread.sleep(100); // Fixed short sleep: asserting absence of a second closer; none is expected

        assertEquals(1, events.countStatusClosers(),
                "a zero-code process exit during compaction must emit exactly one work closer");
        assertTrue(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.FAILED),
                "the sole closer for a zero-code compaction exit must be FAILED");
        assertFalse(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.EXITED),
                "a zero-code exit must not add an EXITED closer to the compaction's FAILED");
        assertFalse(events.hasEvent(TurnCompleteEvent.class),
                "an exiting internal compact turn must not leak TurnCompleteEvent");
    }

    @Test
    void parserReportedCompactFailureHasOneFailedCloserAndNoTurnComplete() throws Exception {
        assertTrue(manager.compact(workDir));
        events.clear();

        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"api_error\"}");
        awaitTrue(() -> !manager.isWorkInFlight(), "parser-reported failure closes compaction");
        Thread.sleep(100); // Fixed short sleep: asserting absence of a second closer; none is expected

        assertEquals(1, events.countStatusClosers(),
                "a parser-reported compact failure must emit exactly one work closer");
        assertTrue(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.FAILED
                     && ((StatusEvent) e).text().startsWith("Compact failed: ")),
                "the closer must be runWork's FAILED built from the parser's failure");
        assertFalse(events.hasEvent(TurnCompleteEvent.class),
                "a failed compact must not leak TurnCompleteEvent");
        assertFalse(manager.isProcessing(), "the failure must release the turn");
    }

    @Test
    void abnormalResultEndingACompactIsReportedAsFailureNotNothingToCompact() throws Exception {
        assertTrue(manager.compact(workDir));
        events.clear();

        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"error_during_execution\"}");
        awaitTrue(() -> !manager.isWorkInFlight(), "abnormal result closes compaction");
        Thread.sleep(100); // Fixed short sleep: asserting absence of a second closer; none is expected

        assertEquals(1, events.countStatusClosers(),
                "an abnormal compact result must emit exactly one work closer");
        assertTrue(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.FAILED
                     && ((StatusEvent) e).text().contains("error_during_execution")),
                "the closer must be FAILED and carry the result subtype");
        assertFalse(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.READY),
                "a failed compaction must never be reported as READY 'Nothing to compact'");
        assertFalse(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.INTERRUPTED),
                "the compact turn's INTERRUPTED must stay backend-local");
        assertFalse(events.hasEvent(TurnCompleteEvent.class),
                "a failed compact must not leak TurnCompleteEvent");
        assertFalse(manager.isProcessing(), "the failure must release the turn");
    }

    @Test
    void sendStartFailureWhileCompactingHasExactlyOneFailedCloser() throws Exception {
        manager.setLaunchFails(true);

        assertTrue(manager.compact(workDir));
        awaitTrue(() -> events.countStatusClosers() > 0, "send-start failure closes compaction");
        Thread.sleep(100); // Fixed short sleep: asserting absence of a second closer; none is expected

        assertEquals(1, events.countStatusClosers(),
                "a send-start failure while compacting must emit exactly one work closer");
        assertTrue(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.FAILED
                     && ((StatusEvent) e).text().contains("launch failed")),
                "the closer must be FAILED carrying the launch error");
        assertFalse(events.hasEvent(TurnCompleteEvent.class),
                "a failed compact start must not leak TurnCompleteEvent");
        assertFalse(manager.isWorkInFlight());
        assertFalse(manager.isProcessing());
    }

    @Test
    void interruptDuringCompactionIsIgnored() {
        assertTrue(manager.compact(workDir));
        events.clear();

        manager.interrupt(InterruptTypeEnum.Cancel);
        manager.interrupt(InterruptTypeEnum.Mail);

        assertTrue(manager.isWorkInFlight(), "Stop and Mail must not cancel non-cancellable compaction");
        assertFalse(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.STOPPED),
                "Stop must not emit STOPPED while compaction owns the lifecycle");
    }

    @Test
    void sendPromptRefusalWhilePendingDiffIsInfoOnly() {
        manager.setPendingDiff(true);
        events.clear();

        manager.sendPrompt("blocked by diff", workDir, List.of());

        assertTrue(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.INFO
                     && ((StatusEvent) e).text().contains("pending diff")),
                "pending diff refusal must explain the INFO-only guard");
        assertTrue(events.hasEvent(TurnCompleteEvent.class),
                "pending diff refusal must hand control back so the user can prompt again");
        assertFalse(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.BUSY
                     || ((StatusEvent) e).type() == StatusEventTypeEnum.FAILED),
                "pending diff refusal must not claim a work lifecycle");
        assertFalse(manager.isProcessing());
    }

    @Test
    void sendPromptRefusalWhileAwaitingCancelResultIsInfoOnly() {
        manager.sendPrompt("first prompt", workDir, List.of());
        assertTrue(manager.isProcessing());
        manager.interrupt(InterruptTypeEnum.Cancel);
        assertTrue(manager.isAwaitingCancelResult());
        events.clear();

        manager.sendPrompt("blocked while stopping", workDir, List.of());

        assertTrue(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.INFO
                     && ((StatusEvent) e).text().contains("still stopping")),
                "cancel-result refusal must explain the INFO-only guard");
        assertTrue(events.hasEvent(TurnCompleteEvent.class),
                "cancel-result refusal must hand control back so the user can prompt again");
        assertFalse(events.hasEvent(StatusEvent.class,
                e -> ((StatusEvent) e).type() == StatusEventTypeEnum.BUSY
                     || ((StatusEvent) e).type() == StatusEventTypeEnum.FAILED),
                "cancel-result refusal must not claim a work lifecycle");
    }

    @Test
    void handleProcessExitStaleSuppression() throws InterruptedException {
        manager.sendPrompt("turn 1", workDir, List.of());
        ClaudePersistentSession sessionA = manager.getPersistentSession();

        // Turn 1 must finish before recycling. This used to recycle mid-turn, which
        // stranded the manager as processing-with-no-session and made everything below
        // vacuous: sendPrompt("turn 2") early-returned on its own processing guard,
        // sessionB came back NULL, and assertNotSame(sessionA, null) passed without ever
        // proving turn 2 got a fresh session. The stale-exit half of the test was real;
        // the "different session" half was a false confirmation masked by that bug.
        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"success\"}");
        awaitTrue(() -> !manager.isProcessing(), "turn 1 complete");

        manager.recycleForModelChange();

        manager.sendPrompt("turn 2", workDir, List.of());
        ClaudePersistentSession sessionB = manager.getPersistentSession();
        assertNotNull(sessionB, "turn 2 must have launched a genuinely new session, not returned null");
        assertNotSame(sessionA, sessionB);

        events.clear();
        sessionA.process().destroyForcibly();
        Thread.sleep(200); // Fixed short sleep: asserting absence of EXITED event; no event expected

        assertFalse(events.hasEvent(StatusEvent.class, e -> ((StatusEvent) e).type() == StatusEventTypeEnum.EXITED));
    }

    /**
     * REACHABILITY half. Uses only the real public API, so it proves the stranded state can occur in
     * production — not merely that we can recover from one we manufactured ourselves.
     *
     * <p>
     * Asserts the invariant rather than the mechanism: it does not care whether the fix declines the recycle
     * or ends the turn first, only that "in flight with no session" never survives the call.
     */
    @Test
    void recycleMidTurnMustNotStrandTheTurn() {
        manager.sendPrompt("turn 1", workDir, List.of());
        assertTrue(manager.isProcessing(), "precondition: a turn must be in flight");
        assertNotNull(manager.getPersistentSession(), "precondition: a session must be attached");

        manager.recycleForModelChange();

        assertFalse(manager.isProcessing() && manager.getPersistentSession() == null,
                "recycleForModelChange() must not leave the manager processing with no session: interrupt() checks "
                + "the session before processing, so Stop becomes a silent no-op and the input never re-enables");
    }

    /**
     * RECOVERY half. Deliberately manufactures the stranded state rather than reaching it through the API,
     * because once the reachability fix lands the real path can no longer produce it. Recovery still has to
     * work — any future path that strands a turn must not cost the user their session.
     */
    @Test
    void cancelStillNotifiesWhenSessionIsMissing() {
        manager.sendPrompt("turn 1", workDir, List.of());
        assertTrue(manager.isProcessing(), "precondition: a turn must be in flight");

        manager.orphanSessionForTest();
        events.clear();

        manager.interrupt(InterruptTypeEnum.Cancel);

        assertTrue(events.hasEvent(StatusEvent.class, e -> ((StatusEvent) e).type() == StatusEventTypeEnum.STOPPED),
                "Cancel must fire STOPPED even with no live session to send the interrupt to — the UI re-enables its "
                + "input on this event alone, so no event means a permanently disabled chat");
        assertFalse(manager.isProcessing(), "Cancel must clear processing");
        // Guards the trap in the obvious fix: awaitingCancelResult gates sendPrompt, and the watchdog clears it only
        // by comparing against the session it captured. Setting it with no session means that comparison never
        // matches, the flag is never cleared, and the user is locked out of sending for good.
        assertFalse(manager.isAwaitingCancelResult(),
                "awaitingCancelResult must NOT be set when no interrupt was actually sent");
    }

    @Test
    void cancelWatchdog() throws InterruptedException {
        manager.cancelWatchdogMillis = 200;
        manager.sendPrompt("prompt", workDir, List.of());

        manager.interrupt(InterruptTypeEnum.Cancel);
        assertTrue(manager.isAwaitingCancelResult());

        awaitTrue(() -> !manager.isAwaitingCancelResult(), "watchdog clears cancel gate");

        assertFalse(manager.isAwaitingCancelResult());
        assertNull(manager.getPersistentSession());

        manager.sendPrompt("new prompt", workDir, List.of());
        assertTrue(manager.isProcessing());
        assertEquals(2, manager.getLaunchCount());
    }

    @Test
    void addDirRecycle() throws InterruptedException, IOException {
        File dirA = Files.createTempDirectory("dirA").toFile();
        File dirB = Files.createTempDirectory("dirB").toFile();

        manager.sendPrompt("turn 1", workDir, List.of(dirA));
        assertEquals(1, manager.getLaunchCount());
        ClaudePersistentSession session1 = manager.getPersistentSession();

        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"success\"}");
        awaitTrue(() -> !manager.isProcessing(), "turn 1 complete");

        manager.sendPrompt("turn 2", workDir, List.of(dirA, dirB));
        assertEquals(2, manager.getLaunchCount());
        ClaudePersistentSession session2 = manager.getPersistentSession();
        assertNotSame(session1, session2);

        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"success\"}");
        awaitTrue(() -> !manager.isProcessing(), "turn 2 complete");

        manager.sendPrompt("turn 3", workDir, List.of(dirA, dirB));
        assertEquals(2, manager.getLaunchCount());
        assertSame(session2, manager.getPersistentSession());
    }

    @Test
    void modelChangeRecycle() throws InterruptedException {
        manager.sendPrompt("turn 1", workDir, List.of());
        assertEquals(1, manager.getLaunchCount());

        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"success\"}");
        awaitTrue(() -> !manager.isProcessing(), "turn 1 complete");

        manager.recycleForModelChange();

        manager.sendPrompt("turn 2", workDir, List.of());
        assertEquals(2, manager.getLaunchCount());
    }

    static class RecordingEventListener implements AiProcessEventListener {

        private final List<AiProcessEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onAiProcessEvent(AiProcessEvent event) {
            events.add(event);
        }

        boolean hasEvent(Class<?> type) {
            return new ArrayList<>(events).stream().anyMatch(e -> type.isInstance(e));
        }

        boolean hasEvent(Class<?> type, Predicate<Object> predicate) {
            return new ArrayList<>(events).stream()
                    .filter(type::isInstance)
                    .anyMatch(e -> predicate.test(e));
        }

        long countStatusClosers() {
            return new ArrayList<>(events).stream()
                    .filter(StatusEvent.class::isInstance)
                    .map(StatusEvent.class::cast)
                    .filter(e -> e.type() == StatusEventTypeEnum.READY
                                 || e.type() == StatusEventTypeEnum.FAILED
                                 || e.type() == StatusEventTypeEnum.EXITED)
                    .count();
        }

        void clear() {
            events.clear();
        }
    }

    static class TestableClaudeAiProcessManager extends ClaudeAiProcessManager {

        private boolean firstSessionDead = false;
        private boolean launchFails = false;

        TestableClaudeAiProcessManager(AiProcessEventListener listener) {
            super(listener);
        }

        void setLaunchFails(boolean fails) {
            this.launchFails = fails;
        }

        void setupForTest() {
            running = true;
            sessionId = UUID.randomUUID().toString();
            model = "test-model";
            executablePath = "/bin/cat";
        }

        void setFirstSessionDead(boolean dead) {
            this.firstSessionDead = dead;
        }

        @Override
        protected ClaudePersistentSession launchPersistentSession(List<String> cmd, File workDir,
                                                                  Consumer<String> stdoutLine, Consumer<String> stderrLine) throws IOException {
            if (launchFails) {
                throw new IOException("launch failed");
            }
            if (firstSessionDead && getLaunchCount() == 0) {
                firstSessionDead = false;
                ClaudePersistentSession dead = ClaudePersistentSession.launch(
                        List.of("/bin/cat"), workDir, stdoutLine, stderrLine);
                dead.close();
                return dead;
            }
            return ClaudePersistentSession.launch(
                    List.of("/bin/cat"), workDir, stdoutLine, stderrLine);
        }

        ClaudePersistentSession getSession() {
            return persistentSession;
        }

        /**
         * Drops the session WITHOUT clearing {@code processing} — the stranded state, manufactured directly.
         * Only for the recovery test; the reachability test uses the real {@code recycleForModelChange()}.
         */
        void orphanSessionForTest() {
            persistentSession = null;
        }
    }
}

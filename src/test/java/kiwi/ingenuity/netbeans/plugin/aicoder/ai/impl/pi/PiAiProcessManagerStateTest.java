package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiAvailableThinkingLevelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiContextUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiModelChangedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiSessionModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiThinkingLevelChangedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiToolResultEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiVersionCheckedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.session.PiPersistentSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Threading/lifecycle behaviour of {@link PiAiProcessManager} against a real (but fake-command) {@link
 * PiPersistentSession} — mirrors {@code ClaudeAiProcessManagerStateTest}'s shape: {@code /bin/cat} stands in
 * for the {@code pi} CLI (PiPersistentSession, like ClaudePersistentSession, is a {@code final} class with a
 * private constructor, so it can only be produced via its own {@code launch()}, not mocked). {@link
 * PiAiProcessManager#extensionPathForTests} substitutes for a real, on-disk-generating
 * {@code PiAiMcpRegistrar} call — see the class's own javadoc on that seam.
 */
class PiAiProcessManagerStateTest {

    private RecordingEventListener events;
    private TestablePiAiProcessManager manager;
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
        manager = new TestablePiAiProcessManager(events);
        workDir = Files.createTempDirectory("pi-test").toFile();
        manager.setupForTest();
    }

    private AtomicInteger recordDeleteExtensionFileCalls() {
        AtomicInteger calls = new AtomicInteger();
        manager.deleteExtensionFile = reg -> calls.incrementAndGet();
        return calls;
    }

    @AfterEach
    void teardown() {
        manager.stop();
    }

    @Test
    void resumeSessionAdoptsTheGivenIdAsThePiSessionId() {
        manager.resumeSession("stored-pi-session-id");
        assertEquals("stored-pi-session-id", manager.getPiSessionId());
    }

    @Test
    void resumeSessionIgnoresNullOrBlank() {
        manager.resumeSession("original-id");
        manager.resumeSession(null);
        assertEquals("original-id", manager.getPiSessionId());
        manager.resumeSession("   ");
        assertEquals("original-id", manager.getPiSessionId());
    }

    @Test
    void sendPromptSpawnsASessionAndMarksProcessing() {
        manager.sendPrompt("hello", workDir, List.of());
        assertTrue(manager.isProcessing());
        assertEquals(1, manager.getLaunchCount());
    }

    @Test
    void sendPromptIsIgnoredWhileAlreadyProcessing() {
        manager.sendPrompt("first", workDir, List.of());
        int launchesAfterFirst = manager.getLaunchCount();
        manager.sendPrompt("second", workDir, List.of());
        assertEquals(launchesAfterFirst, manager.getLaunchCount());
    }

    @Test
    void sendPromptRefusal_pendingDiffEmitsInfoThenTurnComplete() {
        manager.setPendingDiff(true);
        assertPromptRefusal("A file diff is awaiting review");
    }

    @Test
    void sendPromptRefusal_notRunningEmitsInfoThenTurnComplete() {
        manager.setRunningForTests(false);
        assertPromptRefusal("Pi session is not running");
    }

    @Test
    void sendPromptRefusal_processingEmitsInfoThenTurnComplete() {
        manager.setProcessingForTests(true);
        assertPromptRefusal("Wait for pi to finish before sending");
    }

    @Test
    void sendPromptRefusal_workInFlightEmitsInfoThenTurnComplete() {
        manager.startWorkForTest();
        events.clear();
        assertPromptRefusal("Wait for pi to finish before sending");
    }

    private void assertPromptRefusal(String message) {
        manager.sendPrompt("refused", workDir, List.of());
        assertEquals(2, events.events().size());
        assertEquals(StatusEventTypeEnum.INFO, ((StatusEvent) events.events().get(0)).type());
        assertEquals(message, ((StatusEvent) events.events().get(0)).text());
        assertTrue(events.events().get(1) instanceof kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent);
    }

    @Test
    void cancelWithNoTurnInFlightIsIgnored() {
        manager.interrupt(InterruptTypeEnum.Cancel);
        assertFalse(events.hasEvent(StatusEvent.class, e -> ((StatusEvent) e).type() == StatusEventTypeEnum.STOPPED));
        assertFalse(manager.isAwaitingCancelResult());
    }

    @Test
    void cancelSendsAbortAndFiresStopped() throws InterruptedException {
        manager.cancelWatchdogMillis = 10000;
        manager.sendPrompt("first prompt", workDir, List.of());
        assertTrue(manager.isProcessing());

        manager.interrupt(InterruptTypeEnum.Cancel);

        assertFalse(manager.isProcessing());
        assertTrue(manager.isAwaitingCancelResult());
        awaitTrue(() -> events.hasEvent(StatusEvent.class, e -> ((StatusEvent) e).type() == StatusEventTypeEnum.STOPPED),
                "STOPPED event");
    }

    @Test
    void cancelWatchdogForceClosesWhenPiNeverAnswersAbort() throws InterruptedException {
        manager.setRegistrarForTests(new PiAiMcpRegistrar("test-session", "/bin/cat"));
        var calls = recordDeleteExtensionFileCalls();
        manager.cancelWatchdogMillis = 50;
        manager.sendPrompt("first prompt", workDir, List.of());
        manager.interrupt(InterruptTypeEnum.Cancel);
        assertTrue(manager.isAwaitingCancelResult());

        awaitTrue(() -> !manager.isAwaitingCancelResult(), "cancel watchdog to clear the gate");
        assertNull(manager.getPersistentSession());
        // Review A: nulling persistentSession above makes handleProcessExit's own guard skip its delete once the
        // killed process actually exits, so the watchdog must delete the extension file itself.
        awaitTrue(() -> calls.get() > 0, "deleteExtensionFile called by the cancel watchdog");
        assertEquals(1, calls.get());
    }

    // ---- Live bug (Boss 2026-09-18): aborting DURING a tool call reports message_end stopReason:"error" (not
    // "aborted") plus a tool_execution_end isError:true — pressing Stop must not repaint as a red failure. ----
    @Test
    void cancelDuringATool_doesNotSurfaceTheAbortedTurnsOwnFailureOrToolError() throws InterruptedException {
        manager.cancelWatchdogMillis = 10000;
        manager.scriptOverride = TestablePiAiProcessManager.ABORT_REPORTS_ERROR_TAIL_SCRIPT;
        manager.sendPrompt("first prompt", workDir, List.of());
        assertTrue(manager.isProcessing());

        manager.interrupt(InterruptTypeEnum.Cancel);

        awaitTrue(() -> events.hasEvent(StatusEvent.class, e -> ((StatusEvent) e).type() == StatusEventTypeEnum.STOPPED),
                "STOPPED event fired synchronously by interrupt(Cancel)");
        // The fake process's abort-triggered tail (tool_execution_end isError:true, message_end stopReason:"error",
        // agent_settled) arrives asynchronously afterwards — its TurnCompleteEvent marks it fully landed.
        awaitTrue(() -> events.hasEvent(kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent.class, e -> true),
                "TurnCompleteEvent once the aborted turn settles");

        assertFalse(events.hasEvent(StatusEvent.class, e -> ((StatusEvent) e).type() == StatusEventTypeEnum.FAILED),
                "a FAILED from the aborted turn's own tail must not repaint the clean Stop as a red failure");
        assertFalse(events.hasEvent(PiToolResultEvent.class, e -> ((PiToolResultEvent) e).isError()),
                "the in-flight tool's isError:true tail must not surface as a tool failure either");
    }

    @Test
    void sameFailedTailWithoutACancel_stillSurfacesFailedAndToolError() throws InterruptedException {
        // Negative control for the test above: the very same event shapes, with no interrupt(Cancel) in flight, must
        // still render normally — proving the new guard is keyed on cancelledByUser and not on the event shapes
        // themselves swallowing everything unconditionally.
        manager.scriptOverride = """
                printf '%s\\n' '{"type":"tool_execution_end","toolCallId":"tc1","toolName":"Bash","result":{"content":[{"text":"boom"}]},"isError":true}'
                printf '%s\\n' '{"type":"message_end","message":{"role":"assistant","stopReason":"error","errorMessage":"This operation was aborted"}}'
                printf '%s\\n' '{"type":"agent_settled"}'
                cat >/dev/null
                """;

        manager.sendPrompt("hello", workDir, List.of());

        awaitTrue(() -> events.hasEvent(StatusEvent.class, e -> ((StatusEvent) e).type() == StatusEventTypeEnum.FAILED),
                "a genuine FAILED with no cancel in progress must still surface");
        assertTrue(events.hasEvent(PiToolResultEvent.class, e -> ((PiToolResultEvent) e).isError()),
                "a genuine tool error with no cancel in progress must still surface");
    }

    @Test
    void mailInterruptWhileIdleIsIgnored() {
        // No turn running — the generic idle-delivery path (sendPrompt) owns idle mail delivery, not interrupt().
        manager.interrupt(InterruptTypeEnum.Mail);
        assertFalse(manager.isProcessing());
        assertEquals(0, manager.getLaunchCount());
    }

    @Test
    void stopClearsRunningAndClosesTheSession() {
        manager.sendPrompt("hello", workDir, List.of());
        assertTrue(manager.isProcessing());

        manager.stop();

        assertFalse(manager.isRunning());
        assertFalse(manager.isProcessing());
        assertNull(manager.getPersistentSession());
        assertNull(manager.getSessionId());
    }

    @Test
    void stopCallsDeleteExtensionFileWhenARegistrarIsPresent() {
        manager.setRegistrarForTests(new PiAiMcpRegistrar("test-session", "/bin/cat"));
        var calls = recordDeleteExtensionFileCalls();

        manager.stop();

        assertEquals(1, calls.get());
    }

    @Test
    void stopIsANoOpForDeleteExtensionFileWithNoRegistrar() {
        // No registrar was ever assigned (start() was never called) — nothing to clean up, and no NPE either.
        var calls = recordDeleteExtensionFileCalls();

        manager.stop();

        assertEquals(0, calls.get());
    }

    @Test
    void unexpectedProcessExitFiresExited() throws InterruptedException {
        manager.sendPrompt("hello", workDir, List.of());
        PiPersistentSession session = manager.getPersistentSession();
        assertTrue(session.isAlive());

        session.process().destroyForcibly();

        awaitTrue(() -> events.hasEvent(StatusEvent.class, e -> ((StatusEvent) e).type() == StatusEventTypeEnum.EXITED),
                "EXITED event");
        awaitTrue(() -> manager.getPersistentSession() == null, "persistentSession cleared");
        assertFalse(manager.isProcessing());
    }

    @Test
    void unexpectedProcessExitCallsDeleteExtensionFile() throws InterruptedException {
        manager.setRegistrarForTests(new PiAiMcpRegistrar("test-session", "/bin/cat"));
        var calls = recordDeleteExtensionFileCalls();

        manager.sendPrompt("hello", workDir, List.of());
        manager.getPersistentSession().process().destroyForcibly();

        awaitTrue(() -> calls.get() > 0, "deleteExtensionFile called after unexpected exit");
        assertEquals(1, calls.get());
    }

    @Test
    void launchThrowingAfterExtensionFileGenerationDeletesTheFileAndReportsFailed() {
        manager.setRegistrarForTests(new PiAiMcpRegistrar("test-session", "/bin/cat"));
        var calls = recordDeleteExtensionFileCalls();
        manager.throwOnLaunch = true;

        manager.sendPrompt("hello", workDir, List.of());

        // Review C: getExtensionFilePath() (stood in for by extensionPathForTests) had already "generated" the file
        // by the time launchPersistentSession throws — that file must not survive the failed launch.
        assertEquals(1, calls.get());
        assertFalse(manager.isProcessing());
        assertNull(manager.getPersistentSession());
        assertTrue(events.hasEvent(StatusEvent.class, e -> ((StatusEvent) e).type() == StatusEventTypeEnum.FAILED));
    }

    @Test
    void stoppedProcessExitDoesNotFireExited() throws InterruptedException {
        manager.sendPrompt("hello", workDir, List.of());
        manager.stop();
        events.clear();
        // stop() already closed the session; nothing further to assert beyond "no crash and no stray EXITED"
        // arriving after teardown — give the (already-dead) process a moment to finish exiting.
        Thread.sleep(100);
        assertFalse(events.hasEvent(StatusEvent.class, e -> ((StatusEvent) e).type() == StatusEventTypeEnum.EXITED));
    }

    // ---- compact() must fail rather than silently succeed ----
    @Test
    void compactFailsWhenNoSessionIsRunning() {
        CompletableFuture<Void> result = manager.compact();
        assertTrue(result.isCompletedExceptionally(), "compact() must fail rather than silently succeed with no session");
    }

    @Test
    void compactFailsWhenTheSendItselfFails() {
        manager.sendPrompt("hello", workDir, List.of());
        manager.getPersistentSession().close();

        CompletableFuture<Void> result = manager.compact();

        assertTrue(result.isCompletedExceptionally(), "a send failure (closed session) must propagate, not silently succeed");
    }

    @Test
    void compactFailsWhenPiReportsSuccessFalse() throws InterruptedException {
        manager.scriptOverride = TestablePiAiProcessManager.ECHO_FAILURE_SCRIPT;
        manager.sendPrompt("hello", workDir, List.of());
        awaitTrue(() -> manager.getPersistentSession() != null, "session spawned");

        CompletableFuture<Void> result = manager.compact();

        awaitTrue(result::isDone, "compact() to complete");
        assertTrue(result.isCompletedExceptionally(), "success:false from pi must fail the future, not silently resolve");
    }

    // ---- a code-0 exit DURING a turn must still report EXITED ----
    @Test
    void codeZeroExitDuringATurnStillReportsExited() throws Exception {
        // Must actually ACK the prompt (unlike the discard-only sink other tests use): with that sink the prompt's
        // send() future only ever resolves when the stream ends, via PiPersistentSession's own failAllPending() —
        // which races THIS test's forced exit against sendPrompt's own ack-failure handler (PiAiProcessManager.java's
        // sendPrompt().whenComplete, which also clears `processing` on an exceptional ack). Depending on which of the
        // two wins that race, handleProcessExit could capture wasProcessing as already-false, exactly the flake build-12
        // caught. Acking success:true first (and waiting for it to land) leaves `processing` untouched by anything but
        // handleProcessExit below, matching a real turn where the ack resolves almost instantly, long before any
        // later mid-turn death.
        manager.scriptOverride = TestablePiAiProcessManager.IMMEDIATE_ECHO_SUCCESS_SCRIPT;
        manager.sendPrompt("hello", workDir, List.of());
        PiPersistentSession session = manager.getPersistentSession();
        assertTrue(manager.isProcessing());
        Thread.sleep(300); // let the prompt's ack (and fetchInitialPickers' housekeeping acks ahead of it) land

        // Exits 0 on EOF — closing our side of stdin is a clean exit(0), not a kill, exactly the case that used to
        // stay silent because exit reporting was gated on code != 0.
        session.process().getOutputStream().close();

        awaitTrue(() -> events.hasEvent(StatusEvent.class, e -> ((StatusEvent) e).type() == StatusEventTypeEnum.EXITED),
                "EXITED event even on a clean exit(0) because a turn was in flight");
        awaitTrue(() -> !manager.isProcessing(), "processing cleared");
    }

    // ---- exit mid-compact: the work's own failure is the single closer; EXITED must be folded away ----
    @Test
    void nonZeroExitMidCompactGivesExactlyOneClosingStatus() throws Exception {
        manager.scriptOverride = TestablePiAiProcessManager.EXIT_3_ON_COMPACT_SCRIPT;
        manager.sendPrompt("hello", workDir, List.of());
        PiPersistentSession session = manager.getPersistentSession();
        Thread.sleep(300); // let the prompt's ack (and fetchInitialPickers' housekeeping acks ahead of it) land
        manager.setProcessingForTests(false); // the turn is over: only the compaction is in flight
        events.clear();

        boolean started = manager.runWork("Compacting conversation…", false, 60_000L, manager::compactUnlessSessionEnds,
                ignored -> new StatusEvent(StatusEventTypeEnum.READY, "Conversation compacted."),
                error -> new StatusEvent(StatusEventTypeEnum.FAILED, "Compact failed: " + error.getMessage()));
        assertTrue(started, "nothing else was in flight, so the compaction must start");

        awaitTrue(() -> manager.getPersistentSession() == null,
                "handleProcessExit to run past its persistentSession guard, so a stray EXITED could have been emitted");
        Thread.sleep(300); // give a wrongly emitted EXITED time to arrive before asserting its absence

        // Non-vacuity: EXITED is otherwise suppressed by cancelledByUser (stop()/Cancel set it), which would make the
        // "no EXITED" assertion below pass whatever the fold does.
        assertFalse(manager.cancelledByUserForTests(), "a user cancel would suppress EXITED and hide the fold");
        assertEquals(3, session.process().exitValue(), "the exit must be non-zero, or code == 0 alone would hide EXITED");
        assertFalse(manager.isWorkInFlight());
        assertEquals(List.of(StatusEventTypeEnum.BUSY, StatusEventTypeEnum.FAILED), statusTypes(),
                "exit during work must give exactly one closing status (the work's FAILED), not FAILED plus EXITED");
    }

    private List<StatusEventTypeEnum> statusTypes() {
        return new ArrayList<>(events.events()).stream()
                .filter(StatusEvent.class::isInstance)
                .map(e -> ((StatusEvent) e).type())
                .toList();
    }

    // ---- thinking_level_changed must update the info bar's picker ----
    @Test
    void thinkingLevelChangedEventUpdatesThePickerSelection() throws Exception {
        // Pushes the event frame unprompted (no command needed to trigger thinking_level_changed — pi can change the
        // level on its own, e.g. normalising an unsupported one), then behaves like the standard discard sink so
        // fetchInitialPickers' own commands don't matter for this test.
        manager.scriptOverride = """
                printf '%s\\n' '{"type":"thinking_level_changed","level":"high"}'
                cat >/dev/null
                """;

        manager.sendPrompt("hello", workDir, List.of());

        awaitTrue(() -> events.hasEvent(PiThinkingLevelChangedEvent.class, e -> "high".equals(((PiThinkingLevelChangedEvent) e).level())),
                "a PiThinkingLevelChangedEvent carrying the level pi reported to reach the listener");
    }

    // ---- every per-session fact the info bar shows must reach it as a pi event on the normal listener ----
    private void sendPromptAgainstDataAnsweringPi() {
        manager.scriptOverride = TestablePiAiProcessManager.DATA_ANSWERING_SCRIPT;
        manager.sendPrompt("hello", workDir, List.of());
    }

    @Test
    void getAvailableModelsResultIsEmittedAsASessionModelsEvent() throws Exception {
        sendPromptAgainstDataAnsweringPi();

        awaitTrue(() -> events.hasEvent(PiSessionModelsEvent.class,
                e -> List.of("p1/m1", "p2/m2").equals(((PiSessionModelsEvent) e).models())),
                "a PiSessionModelsEvent listing provider/id for every model pi reported");
    }

    @Test
    void getAvailableThinkingLevelsResultIsEmittedAsAnAvailableThinkingLevelsEvent() throws Exception {
        sendPromptAgainstDataAnsweringPi();

        awaitTrue(() -> events.hasEvent(PiAvailableThinkingLevelsEvent.class,
                e -> List.of("low", "high").equals(((PiAvailableThinkingLevelsEvent) e).levels())),
                "a PiAvailableThinkingLevelsEvent carrying the levels pi reported");
    }

    @Test
    void getStateModelIsEmittedAsAModelChangedEvent() throws Exception {
        sendPromptAgainstDataAnsweringPi();

        awaitTrue(() -> events.hasEvent(PiModelChangedEvent.class, e -> "p1/m1".equals(((PiModelChangedEvent) e).model())),
                "a PiModelChangedEvent for get_state's current model");
    }

    @Test
    void getStateThinkingLevelIsEmittedAsAThinkingLevelChangedEvent() throws Exception {
        sendPromptAgainstDataAnsweringPi();

        awaitTrue(() -> events.hasEvent(PiThinkingLevelChangedEvent.class, e -> "high".equals(((PiThinkingLevelChangedEvent) e).level())),
                "a PiThinkingLevelChangedEvent for get_state's current thinking level");
    }

    @Test
    void getSessionStatsContextUsageIsEmittedAsAContextUsageEvent() throws Exception {
        sendPromptAgainstDataAnsweringPi();

        awaitTrue(() -> events.hasEvent(PiContextUsageEvent.class, e -> {
            PiContextUsageEvent usage = (PiContextUsageEvent) e;
            return usage.usedTokens() == 1234 && usage.contextWindowTokens() == 200000;
        }), "a PiContextUsageEvent with get_session_stats' token counts");
    }

    @Test
    void successfulSetModelIsEmittedAsAModelChangedEvent() throws Exception {
        manager.scriptOverride = TestablePiAiProcessManager.IMMEDIATE_ECHO_SUCCESS_SCRIPT;
        manager.sendPrompt("hello", workDir, List.of());

        manager.setModel("providerA", "modelA").get(10, TimeUnit.SECONDS);

        awaitTrue(() -> events.hasEvent(PiModelChangedEvent.class, e -> "providerA/modelA".equals(((PiModelChangedEvent) e).model())),
                "a PiModelChangedEvent for the model pi accepted");
    }

    @Test
    void successfulSetThinkingLevelIsEmittedAsAThinkingLevelChangedEvent() throws Exception {
        manager.scriptOverride = TestablePiAiProcessManager.IMMEDIATE_ECHO_SUCCESS_SCRIPT;
        manager.sendPrompt("hello", workDir, List.of());

        manager.setThinkingLevel("medium").get(10, TimeUnit.SECONDS);

        awaitTrue(() -> events.hasEvent(PiThinkingLevelChangedEvent.class, e -> "medium".equals(((PiThinkingLevelChangedEvent) e).level())),
                "a PiThinkingLevelChangedEvent for the level pi accepted");
    }

    // ---- compaction shrinks the context, so the gauge must be re-read once a compact succeeds ----
    @Test
    void successfulCompactRefreshesTheContextGaugeAndEmitsNoClosingStatus() throws Exception {
        manager.scriptOverride = TestablePiAiProcessManager.USAGE_SHRINKS_AFTER_COMPACT_SCRIPT;
        manager.sendPrompt("hello", workDir, List.of());
        awaitTrue(() -> events.hasEvent(PiContextUsageEvent.class, e -> ((PiContextUsageEvent) e).usedTokens() == 1234),
                "the initial gauge reading");
        events.clear();

        manager.compact().get(10, TimeUnit.SECONDS);

        awaitTrue(() -> events.hasEvent(PiContextUsageEvent.class, e -> ((PiContextUsageEvent) e).usedTokens() == 321),
                "a PiContextUsageEvent with the post-compact token count, so the gauge does not keep showing the pre-compact fill");
        assertFalse(events.hasEvent(StatusEvent.class, e -> true),
                "compact() must not add a status of its own: its caller emits the single closing status");
    }

    @Test
    void failedCompactDoesNotRefreshTheContextGauge() throws Exception {
        manager.scriptOverride = TestablePiAiProcessManager.ECHO_FAILURE_SCRIPT;
        manager.sendPrompt("hello", workDir, List.of());
        awaitTrue(() -> manager.getPersistentSession() != null, "session spawned");
        events.clear();

        CompletableFuture<Void> result = manager.compact();
        awaitTrue(result::isDone, "compact() to complete");
        Thread.sleep(200);

        assertFalse(events.hasEvent(PiContextUsageEvent.class, e -> true),
                "a compact pi refused changed nothing, so it must not trigger a gauge refresh");
    }

    // ---- a bar opened mid-session is replayed from the latest of each per-session fact ----
    @Test
    void sessionSnapshotHoldsTheLatestValueOfEveryPerSessionFact() throws Exception {
        manager.scriptOverride = TestablePiAiProcessManager.USAGE_SHRINKS_AFTER_COMPACT_SCRIPT;
        manager.sendPrompt("hello", workDir, List.of());
        awaitTrue(() -> events.hasEvent(PiContextUsageEvent.class, e -> ((PiContextUsageEvent) e).usedTokens() == 1234),
                "the initial reading of every fact");
        manager.setModel("providerA", "modelA").get(10, TimeUnit.SECONDS);
        manager.setThinkingLevel("medium").get(10, TimeUnit.SECONDS);
        manager.compact().get(10, TimeUnit.SECONDS);
        awaitTrue(() -> events.hasEvent(PiContextUsageEvent.class, e -> ((PiContextUsageEvent) e).usedTokens() == 321),
                "the post-compact reading");

        List<AiProcessImplEvent> snapshot = manager.sessionSnapshot();

        assertEquals(List.of("p1/m1", "p2/m2"), snapshot.stream()
                .filter(PiSessionModelsEvent.class::isInstance).map(e -> ((PiSessionModelsEvent) e).models())
                .findFirst().orElse(null), snapshot.toString());
        assertEquals(List.of("low", "high"), snapshot.stream()
                .filter(PiAvailableThinkingLevelsEvent.class::isInstance).map(e -> ((PiAvailableThinkingLevelsEvent) e).levels())
                .findFirst().orElse(null), snapshot.toString());
        assertEquals("providerA/modelA", snapshot.stream()
                .filter(PiModelChangedEvent.class::isInstance).map(e -> ((PiModelChangedEvent) e).model())
                .findFirst().orElse(null), "the model set live must replace the one get_state first reported: " + snapshot);
        assertEquals("medium", snapshot.stream()
                .filter(PiThinkingLevelChangedEvent.class::isInstance).map(e -> ((PiThinkingLevelChangedEvent) e).level())
                .findFirst().orElse(null), "the level set live must replace the one get_state first reported: " + snapshot);
        assertEquals(321, snapshot.stream()
                .filter(PiContextUsageEvent.class::isInstance).map(e -> ((PiContextUsageEvent) e).usedTokens())
                .findFirst().orElse(-1), "the usage after compaction must replace the earlier reading: " + snapshot);
        assertEquals(5, snapshot.size(), "one entry per fact, no stale duplicates: " + snapshot);
    }

    @Test
    void sessionSnapshotIsEmptyBeforeAnythingWasReported() {
        assertTrue(manager.sessionSnapshot().isEmpty());
    }

    @Test
    void checkVersionEmitsAVersionCheckedEventCarryingTheInstalledVersion() throws Exception {
        File fakePi = fakePiExecutable("echo 0.1.0");

        manager.checkVersion(fakePi.getAbsolutePath());

        assertTrue(events.hasEvent(PiVersionCheckedEvent.class,
                e -> ((PiVersionCheckedEvent) e).check() != null
                     && "0.1.0".equals(((PiVersionCheckedEvent) e).check().installedVersion())),
                "a PiVersionCheckedEvent with the version `pi --version` printed");
        assertEquals("0.1.0", manager.getVersionCheck().installedVersion());
    }

    @Test
    void checkVersionEmitsANullCheckWhenThePiVersionProbeFails() throws Exception {
        File brokenPi = fakePiExecutable("exit 1");

        manager.checkVersion(brokenPi.getAbsolutePath());

        assertTrue(events.hasEvent(PiVersionCheckedEvent.class, e -> ((PiVersionCheckedEvent) e).check() == null),
                "a failed probe must still be reported, as a PiVersionCheckedEvent with no check, so a bar holding a stale check clears it");
        assertNull(manager.getVersionCheck());
    }

    private File fakePiExecutable(String body) throws IOException {
        File script = new File(workDir, "fake-pi-" + UUID.randomUUID());
        Files.writeString(script.toPath(), "#!/bin/sh\n" + body + "\n");
        assertTrue(script.setExecutable(true));
        return script;
    }

    // ---- a late setModel response for a replaced session must not overwrite state ----
    @Test
    void setModelLateSuccessResponseAfterSessionReplacedIsDiscarded() throws Exception {
        manager.scriptOverride = TestablePiAiProcessManager.DELAYED_ECHO_SUCCESS_SCRIPT;
        manager.sendPrompt("hello", workDir, List.of());
        PiPersistentSession first = manager.getPersistentSession();
        assertTrue(first.isAlive());

        CompletableFuture<Void> setModelResult = manager.setModel("providerA", "modelA");
        // Simulate the session having been replaced before that response lands, WITHOUT going through stop()/close()
        // (which would fail the pending future itself and mask the bug) — this isolates the manager-level
        // persistentSession-identity guard from PiPersistentSession's own internal pending-map race.
        manager.persistentSession = null;

        setModelResult.get(10, TimeUnit.SECONDS); // pi genuinely answered success:true — must not throw
        assertFalse(events.hasEvent(PiModelChangedEvent.class, e -> true),
                "a stale setModel response for a session that's no longer current must not emit a PiModelChangedEvent");
    }

    // ---- a set_model / set_thinking_level pi refuses must put the bar back on what pi confirmed, and only say INFO ----
    private static final String REJECT_EVERY_COMMAND_SCRIPT = """
            while IFS= read -r line; do
                printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":false,"error":"model not found"/'
            done
            """;

    private static final String NEVER_ANSWER_SCRIPT = "cat >/dev/null";

    private PiPersistentSession attachFakePi(String script) throws IOException {
        PiPersistentSession fake = PiPersistentSession.launch(List.of("sh", "-c", script), null, line -> {
        }, line -> {
        });
        manager.setRunningForTests(true);
        manager.persistentSession = fake;
        return fake;
    }

    @Test
    void rejectedSetModelRevertsToTheLastConfirmedModelWithAnInfoAndNoClosingStatus() throws Exception {
        PiPersistentSession fake = attachFakePi(REJECT_EVERY_COMMAND_SCRIPT);
        try {
            manager.report(new PiModelChangedEvent("p1/m1"));
            events.clear();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> manager.setModel("providerA", "modelA").get(10, TimeUnit.SECONDS));

            assertEquals("model not found", failure.getCause().getMessage());
            List<AiProcessEvent> seen = new ArrayList<>(events.events());
            assertEquals(2, seen.size(), "exactly an INFO then the revert, no READY/FAILED/EXITED: " + seen);
            StatusEvent info = assertInstanceOf(StatusEvent.class, seen.get(0));
            assertEquals(StatusEventTypeEnum.INFO, info.type());
            assertEquals("pi did not change model providerA/modelA: model not found", info.text());
            assertEquals(new PiModelChangedEvent("p1/m1"), seen.get(1),
                    "the bar shows the rejected pick, so it must be told what pi is actually running");
            assertEquals(List.of(new PiModelChangedEvent("p1/m1")),
                    manager.sessionSnapshot().stream().filter(PiModelChangedEvent.class::isInstance).toList());
        }
        finally {
            fake.close();
        }
    }

    @Test
    void rejectedSetThinkingLevelRevertsToTheLastConfirmedLevelWithAnInfoAndNoClosingStatus() throws Exception {
        PiPersistentSession fake = attachFakePi(REJECT_EVERY_COMMAND_SCRIPT);
        try {
            manager.report(new PiThinkingLevelChangedEvent("low"));
            events.clear();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> manager.setThinkingLevel("xhigh").get(10, TimeUnit.SECONDS));

            assertEquals("model not found", failure.getCause().getMessage());
            List<AiProcessEvent> seen = new ArrayList<>(events.events());
            assertEquals(2, seen.size(), "exactly an INFO then the revert, no READY/FAILED/EXITED: " + seen);
            StatusEvent info = assertInstanceOf(StatusEvent.class, seen.get(0));
            assertEquals(StatusEventTypeEnum.INFO, info.type());
            assertEquals("pi did not change thinking level xhigh: model not found", info.text());
            assertEquals(new PiThinkingLevelChangedEvent("low"), seen.get(1));
        }
        finally {
            fake.close();
        }
    }

    @Test
    void aSetModelThatFailsBecauseTheSessionEndedMidRequestAlsoRevertsWithAnInfo() throws Exception {
        PiPersistentSession fake = attachFakePi(NEVER_ANSWER_SCRIPT);
        try {
            manager.report(new PiModelChangedEvent("p1/m1"));
            events.clear();
            CompletableFuture<Void> pending = manager.setModel("providerA", "modelA");

            fake.close();

            assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
            List<AiProcessEvent> seen = new ArrayList<>(events.events());
            assertEquals(2, seen.size(), seen.toString());
            StatusEvent info = assertInstanceOf(StatusEvent.class, seen.get(0));
            assertEquals(StatusEventTypeEnum.INFO, info.type());
            assertTrue(info.text().startsWith("pi did not change model providerA/modelA: "), info.text());
            assertEquals(new PiModelChangedEvent("p1/m1"), seen.get(1));
        }
        finally {
            fake.close();
        }
    }

    @Test
    void aFailureForASessionThatIsNoLongerCurrentStaysSilentButStillFailsTheFuture() throws Exception {
        PiPersistentSession fake = attachFakePi(NEVER_ANSWER_SCRIPT);
        try {
            manager.report(new PiModelChangedEvent("p1/m1"));
            events.clear();
            CompletableFuture<Void> pending = manager.setModel("providerA", "modelA");
            manager.persistentSession = null;

            fake.close();

            assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
            assertTrue(events.events().isEmpty(),
                    "a stopped or replaced session must not speak to the user or re-point the bar: " + events.events());
        }
        finally {
            fake.close();
        }
    }

    // ---- pi spawns lazily, so a pick made before the first prompt must be what pi is launched with ----
    private static String flagValue(List<String> command, String flag) {
        int at = command.indexOf(flag);
        assertTrue(at >= 0 && at + 1 < command.size(), flag + " missing from " + command);
        return command.get(at + 1);
    }

    @Test
    void aModelPickedBeforePiSpawnsIsTheModelPiIsLaunchedWithAndIsReported() throws Exception {
        manager.setModel("providerZ", "modelZ").get(10, TimeUnit.SECONDS);

        assertEquals(List.of(new PiModelChangedEvent("providerZ/modelZ")), new ArrayList<>(events.events()),
                "the UI must be told the pick took effect, and nothing else");
        assertEquals(List.of(new PiModelChangedEvent("providerZ/modelZ")),
                manager.sessionSnapshot().stream().filter(PiModelChangedEvent.class::isInstance).toList());
        assertEquals("providerZ/modelZ", flagValue(manager.buildLaunchCommand("sid", "/ext"), "--model"));

        manager.sendPrompt("hello", workDir, List.of());

        assertEquals("providerZ/modelZ", flagValue(manager.lastLaunchCommand, "--model"),
                "the lazily spawned pi must start on the model picked before it existed");
    }

    @Test
    void aThinkingLevelPickedBeforePiSpawnsIsTheLevelPiIsLaunchedWithAndIsReported() throws Exception {
        manager.setThinkingLevel("xhigh").get(10, TimeUnit.SECONDS);

        assertEquals(List.of(new PiThinkingLevelChangedEvent("xhigh")), new ArrayList<>(events.events()));
        assertEquals(List.of(new PiThinkingLevelChangedEvent("xhigh")),
                manager.sessionSnapshot().stream().filter(PiThinkingLevelChangedEvent.class::isInstance).toList());
        assertEquals("xhigh", flagValue(manager.buildLaunchCommand("sid", "/ext"), "--thinking"));

        manager.sendPrompt("hello", workDir, List.of());

        assertEquals("xhigh", flagValue(manager.lastLaunchCommand, "--thinking"),
                "the lazily spawned pi must start on the thinking level picked before it existed");
    }

    // ---- a timed-out change says nothing about what pi did, so ask pi ----
    private static final String ANSWER_ONLY_GET_STATE_SCRIPT = """
            while IFS= read -r line; do
                case "$line" in
                    *'"type":"get_state"'*)
                        printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true,"data":{"model":{"id":"modelA","provider":"providerA"},"thinkingLevel":"medium"}/'
                        ;;
                esac
            done
            """;

    private static final String REJECT_EVERYTHING_BUT_GET_STATE_SCRIPT = """
            while IFS= read -r line; do
                case "$line" in
                    *'"type":"get_state"'*)
                        printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true,"data":{"model":{"id":"modelZ","provider":"providerZ"},"thinkingLevel":"high"}/'
                        ;;
                    *)
                        printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":false,"error":"model not found"/'
                        ;;
                esac
            done
            """;

    @Test
    void aTimedOutSetModelAsksPiWhatItIsRunningAndAdoptsTheAnswer() throws Exception {
        PiPersistentSession fake = attachFakePi(ANSWER_ONLY_GET_STATE_SCRIPT);
        try {
            manager.rpcTimeoutMillisForTests = 300;
            manager.report(new PiModelChangedEvent("p1/m1"));
            events.clear();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> manager.setModel("providerA", "modelA").get(10, TimeUnit.SECONDS));

            assertInstanceOf(TimeoutException.class, failure.getCause());
            awaitTrue(() -> events.hasEvent(PiThinkingLevelChangedEvent.class, e -> "medium".equals(((PiThinkingLevelChangedEvent) e).level())),
                    "the level pi really reports after the resync");
            List<AiProcessEvent> seen = new ArrayList<>(events.events());
            assertEquals("pi did not change model providerA/modelA: pi did not answer in time",
                    assertInstanceOf(StatusEvent.class, seen.get(0)).text());
            assertEquals(List.of(new PiModelChangedEvent("p1/m1"), new PiModelChangedEvent("providerA/modelA"),
                    new PiThinkingLevelChangedEvent("medium")), seen.subList(1, seen.size()),
                    "the revert, then what pi itself says it is running");
            List<String> launch = manager.buildLaunchCommand("sid", "/ext");
            assertEquals("providerA/modelA", flagValue(launch, "--model"),
                    "a later relaunch must start from what pi really runs, not from the stale pick");
            assertEquals("medium", flagValue(launch, "--thinking"));
        }
        finally {
            fake.close();
        }
    }

    @Test
    void aTimedOutSetThinkingLevelAsksPiWhatItIsRunningAndAdoptsTheAnswer() throws Exception {
        PiPersistentSession fake = attachFakePi(ANSWER_ONLY_GET_STATE_SCRIPT);
        try {
            manager.rpcTimeoutMillisForTests = 300;
            manager.report(new PiThinkingLevelChangedEvent("low"));
            events.clear();

            assertThrows(ExecutionException.class, () -> manager.setThinkingLevel("xhigh").get(10, TimeUnit.SECONDS));

            awaitTrue(() -> events.hasEvent(PiModelChangedEvent.class, e -> "providerA/modelA".equals(((PiModelChangedEvent) e).model())),
                    "the model pi really reports after the resync");
            List<AiProcessEvent> seen = new ArrayList<>(events.events());
            assertEquals("pi did not change thinking level xhigh: pi did not answer in time",
                    assertInstanceOf(StatusEvent.class, seen.get(0)).text());
            assertEquals(new PiThinkingLevelChangedEvent("low"), seen.get(1));
            assertEquals("medium", flagValue(manager.buildLaunchCommand("sid", "/ext"), "--thinking"));
        }
        finally {
            fake.close();
        }
    }

    @Test
    void aRefusedChangeIsNotResyncedBecauseOnlyATimeoutLeavesPiStateUnknown() throws Exception {
        PiPersistentSession fake = attachFakePi(REJECT_EVERYTHING_BUT_GET_STATE_SCRIPT);
        try {
            manager.report(new PiModelChangedEvent("p1/m1"));
            events.clear();

            assertThrows(ExecutionException.class, () -> manager.setModel("providerA", "modelA").get(10, TimeUnit.SECONDS));
            Thread.sleep(500);

            assertEquals(2, events.events().size(),
                    "pi answered, so its refusal is authoritative — no get_state, no extra events: " + events.events());
            assertFalse(events.hasEvent(PiModelChangedEvent.class, e -> "providerZ/modelZ".equals(((PiModelChangedEvent) e).model())));
        }
        finally {
            fake.close();
        }
    }

    private static class RecordingEventListener implements AiProcessEventListener {

        private final List<AiProcessEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onAiProcessEvent(AiProcessEvent event) {
            events.add(event);
        }

        boolean hasEvent(Class<?> type, Predicate<Object> predicate) {
            return new ArrayList<>(events).stream()
                    .filter(type::isInstance)
                    .anyMatch(e -> predicate.test(e));
        }

        void clear() {
            events.clear();
        }

        List<AiProcessEvent> events() {
            return events;
        }
    }

    static class TestablePiAiProcessManager extends PiAiProcessManager {

        /**
         * Test seam for {@link #launchThrowingAfterExtensionFileGenerationDeletesTheFileAndReportsFailed}:
         * when true, {@link #launchPersistentSession} throws instead of spawning, simulating a launch failure
         * that happens after {@code getExtensionFilePath()} already generated the file.
         */
        boolean throwOnLaunch = false;

        /**
         * The argv the most recent {@link #launchPersistentSession} was asked to start.
         */
        volatile List<String> lastLaunchCommand;

        /**
         * Test seam: when set, {@link #launchPersistentSession} runs this shell script instead of the default
         * discard-only sink, so a test can control how (and whether) pi "answers" a command. Null in every
         * test that doesn't need a scripted response.
         */
        String scriptOverride;

        /**
         * Answers every command immediately with {@code success:false} — for compact()'s failure-path tests.
         * The sed pattern matches ANY lowercase/underscore {@code type} value, not a literal
         * {@code "command"} — pi has no generic "command" command, the command name IS the request's own
         * {@code type} (e.g. {@code "compact"}).
         */
        static final String ECHO_FAILURE_SCRIPT = """
                while IFS= read -r line; do
                    printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":false,"error":"cannot compact now"/'
                done
                """;

        /**
         * Answers every command with {@code success:true} after a short delay — long enough for a test to
         * reassign {@link #persistentSession} out from under the in-flight command before the response lands.
         */
        static final String DELAYED_ECHO_SUCCESS_SCRIPT = """
                while IFS= read -r line; do
                    sleep 0.2
                    printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
                done
                """;

        /**
         * Answers every command with {@code success:true} immediately (no delay) — for tests that need a real
         * ack so {@code processing} is not left to be raced/cleared by an unrelated ack-failure path when the
         * process later exits (see
         * {@link PiAiProcessManagerStateTest#codeZeroExitDuringATurnStillReportsExited}).
         */
        static final String IMMEDIATE_ECHO_SUCCESS_SCRIPT = """
                while IFS= read -r line; do
                    printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
                done
                """;

        /**
         * Ignores the initial prompt (never acks it — the discard-sink pattern other tests use), then, once
         * it sees the {@code abort} command interrupt(Cancel) sends, replies with the exact tail a real pi
         * 0.85.1 process was observed sending when an abort lands DURING a tool call (live finding, Boss
         * 2026-09-18): the in-flight tool's own {@code tool_execution_end isError:true}, a
         * {@code message_end stopReason:"error"} (NOT {@code "aborted"}), {@code agent_settled}, and finally
         * the {@code abort} command's own {@code success:true} response.
         */
        static final String ABORT_REPORTS_ERROR_TAIL_SCRIPT = """
                while IFS= read -r line; do
                    case "$line" in
                        *'"type":"abort"'*)
                            printf '%s\\n' '{"type":"tool_execution_end","toolCallId":"tc1","toolName":"Bash","result":{"content":[{"text":"aborted"}]},"isError":true}'
                            printf '%s\\n' '{"type":"message_end","message":{"role":"assistant","stopReason":"error","errorMessage":"This operation was aborted"}}'
                            printf '%s\\n' '{"type":"agent_settled"}'
                            printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
                            ;;
                    esac
                done
                """;

        /**
         * Answers each of pi's info-bar queries with realistic {@code data} (ids are kept by rewriting only
         * the {@code type} member of the request frame) and every other command with a bare
         * {@code success:true}.
         */
        static final String DATA_ANSWERING_SCRIPT = """
                while IFS= read -r line; do
                    case "$line" in
                        *'"type":"get_state"'*)
                            printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true,"data":{"model":{"id":"m1","provider":"p1"},"thinkingLevel":"high"}/'
                            ;;
                        *'"type":"get_available_models"'*)
                            printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true,"data":{"models":[{"id":"m1","provider":"p1"},{"id":"m2","provider":"p2"}]}/'
                            ;;
                        *'"type":"get_available_thinking_levels"'*)
                            printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true,"data":{"levels":["low","high"]}/'
                            ;;
                        *'"type":"get_session_stats"'*)
                            printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true,"data":{"contextUsage":{"tokens":1234,"contextWindow":200000}}/'
                            ;;
                        *)
                            printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
                            ;;
                    esac
                done
                """;

        /**
         * Like {@link #DATA_ANSWERING_SCRIPT}, but {@code get_session_stats} reports 1234 tokens until a
         * {@code compact} command has been seen and 321 afterwards, so a test can tell a fresh post-compact
         * query from the earlier one.
         */
        static final String USAGE_SHRINKS_AFTER_COMPACT_SCRIPT = """
                tokens=1234
                while IFS= read -r line; do
                    case "$line" in
                        *'"type":"get_state"'*)
                            printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true,"data":{"model":{"id":"m1","provider":"p1"},"thinkingLevel":"high"}/'
                            ;;
                        *'"type":"get_available_models"'*)
                            printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true,"data":{"models":[{"id":"m1","provider":"p1"},{"id":"m2","provider":"p2"}]}/'
                            ;;
                        *'"type":"get_available_thinking_levels"'*)
                            printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true,"data":{"levels":["low","high"]}/'
                            ;;
                        *'"type":"get_session_stats"'*)
                            printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true,"data":{"contextUsage":{"tokens":'"$tokens"',"contextWindow":200000}}/'
                            ;;
                        *'"type":"compact"'*)
                            tokens=321
                            printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
                            ;;
                        *)
                            printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
                            ;;
                    esac
                done
                """;

        /**
         * Acks every command with {@code success:true} except {@code compact}, which kills the process with a
         * non-zero code without ever answering it — pi dying mid-compaction.
         */
        static final String EXIT_3_ON_COMPACT_SCRIPT = """
                while IFS= read -r line; do
                    case "$line" in
                        *'"type":"compact"'*)
                            exit 3
                            ;;
                        *)
                            printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
                            ;;
                    esac
                done
                """;

        TestablePiAiProcessManager(AiProcessEventListener listener) {
            super(listener);
        }

        boolean cancelledByUserForTests() {
            return cancelledByUser;
        }

        void setRunningForTests(boolean value) {
            running = value;
        }

        void setProcessingForTests(boolean value) {
            processing = value;
        }

        void startWorkForTest() {
            runWork("test work", false, 60_000L, CompletableFuture::new,
                    ignored -> new StatusEvent(StatusEventTypeEnum.READY, "done"),
                    error -> new StatusEvent(StatusEventTypeEnum.FAILED, "failed"));
        }

        void setupForTest() {
            running = true;
            sessionId = UUID.randomUUID().toString();
            model = "test-model";
            executablePath = "/bin/cat";
            toolsRegisteredWaitMillis = 50L;
            extensionPathForTests = "/tmp/aicoder-pi-test-extension.ts";
            resumeSession(UUID.randomUUID().toString());
        }

        @Override
        protected PiPersistentSession launchPersistentSession(List<String> cmd, File workDir,
                                                              Consumer<String> stdoutLine, Consumer<String> stderrLine) throws IOException {
            lastLaunchCommand = cmd;
            if (throwOnLaunch) {
                throw new IOException("simulated launch failure");
            }
            // Deliberately NOT "/bin/cat": PiPersistentSession.dispatchFrame() completes a pending send() future for
            // ANY frame carrying a matching "id" — including cat's raw echo of the very command we just sent, which
            // still carries our own generated id but is not a real {type:"response"} frame. That would spuriously
            // resolve every command's future the instant it's sent (e.g. sendPrompt's ack, read as success:false
            // since the echoed frame has no "success" field, flips processing back to false before a test can
            // observe it mid-turn). A sink that reads and discards stdin without ever writing to stdout avoids that
            // race entirely — every future here stays genuinely pending, exactly like a real turn in progress.
            List<String> shellCmd = scriptOverride != null
                                    ? List.of("sh", "-c", scriptOverride)
                                    : List.of("sh", "-c", "cat >/dev/null");
            return PiPersistentSession.launch(shellCmd, workDir, stdoutLine, stderrLine);
        }
    }
}

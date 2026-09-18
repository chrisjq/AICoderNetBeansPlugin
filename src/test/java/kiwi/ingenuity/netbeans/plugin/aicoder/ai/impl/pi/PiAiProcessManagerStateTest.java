package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiToolResultEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.session.PiPersistentSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui.PiSessionControl;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Threading/lifecycle behaviour of {@link PiAiProcessManager} against a real (but fake-command) {@link
 * PiPersistentSession} — mirrors {@code ClaudeAiProcessManagerStateTest}'s shape: {@code /bin/cat} stands in for the
 * {@code pi} CLI (PiPersistentSession, like ClaudePersistentSession, is a {@code final} class with a private
 * constructor, so it can only be produced via its own {@code launch()}, not mocked). {@link
 * PiAiProcessManager#extensionPathForTests} substitutes for a real, on-disk-generating {@code PiAiMcpRegistrar} call —
 * see the class's own javadoc on that seam.
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

    private java.util.concurrent.atomic.AtomicInteger recordDeleteExtensionFileCalls() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
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
        // agent_settled) arrives asynchronously afterwards — READY marks it fully landed.
        awaitTrue(() -> events.hasEvent(StatusEvent.class, e -> ((StatusEvent) e).type() == StatusEventTypeEnum.READY),
                  "READY event once the aborted turn settles");

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

    // ---- Round-3 fixes (Codex_1): compact() must fail rather than silently succeed ----
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

    // ---- Round-3 fix (Codex_1): a code-0 exit DURING a turn must still report EXITED ----
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

    // ---- Round-5 fix (Sonet's wire-shape scan): thinking_level_changed must update the info bar's picker ----
    @Test
    void thinkingLevelChangedEventUpdatesThePickerSelection() throws Exception {
        List<String[]> selectionChanges = Collections.synchronizedList(new ArrayList<>());
        manager.setListener(new PiSessionControl.Listener() {
            @Override
            public void onAvailableModelsChanged(List<String> models) {
            }

            @Override
            public void onAvailableThinkingLevelsChanged(List<String> levels) {
            }

            @Override
            public void onCurrentSelectionChanged(String providerSlashId, String thinkingLevel) {
                selectionChanges.add(new String[]{providerSlashId, thinkingLevel});
            }

            @Override
            public void onContextUsageChanged(int usedTokens, int contextWindowTokens) {
            }

            @Override
            public void onTurnRunningChanged(boolean running) {
            }
        });
        // Pushes the event frame unprompted (no command needed to trigger thinking_level_changed — pi can change the
        // level on its own, e.g. normalising an unsupported one), then behaves like the standard discard sink so
        // fetchInitialPickers' own commands don't matter for this test.
        manager.scriptOverride = """
                printf '%s\\n' '{"type":"thinking_level_changed","level":"high"}'
                cat >/dev/null
                """;

        manager.sendPrompt("hello", workDir, List.of());

        awaitTrue(() -> selectionChanges.stream().anyMatch(sc -> "high".equals(sc[1])),
                  "onCurrentSelectionChanged to fire with the level pi reported");
    }

    // ---- Round-3 fix (Codex_1): a late setModel response for a replaced session must not overwrite state ----
    @Test
    void setModelLateSuccessResponseAfterSessionReplacedIsDiscarded() throws Exception {
        List<String> notifications = Collections.synchronizedList(new ArrayList<>());
        manager.setListener(new PiSessionControl.Listener() {
            @Override
            public void onAvailableModelsChanged(List<String> models) {
            }

            @Override
            public void onAvailableThinkingLevelsChanged(List<String> levels) {
            }

            @Override
            public void onCurrentSelectionChanged(String providerSlashId, String thinkingLevel) {
                notifications.add(providerSlashId);
            }

            @Override
            public void onContextUsageChanged(int usedTokens, int contextWindowTokens) {
            }

            @Override
            public void onTurnRunningChanged(boolean running) {
            }
        });
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
        assertTrue(notifications.isEmpty(),
                   "a stale setModel response for a session that's no longer current must not fire a selection-changed notification");
    }

    private static class RecordingEventListener implements AiProcessEventListener {

        private final List<AiProcessEvent> events = new ArrayList<>();

        @Override
        public void onAiProcessEvent(AiProcessEvent event) {
            events.add(event);
        }

        boolean hasEvent(Class<?> type, java.util.function.Predicate<Object> predicate) {
            return new ArrayList<>(events).stream()
                    .filter(type::isInstance)
                    .anyMatch(e -> predicate.test(e));
        }

        void clear() {
            events.clear();
        }
    }

    static class TestablePiAiProcessManager extends PiAiProcessManager {

        /**
         * Test seam for {@link #launchThrowingAfterExtensionFileGenerationDeletesTheFileAndReportsFailed}: when true,
         * {@link #launchPersistentSession} throws instead of spawning, simulating a launch failure that happens after
         * {@code getExtensionFilePath()} already generated the file.
         */
        boolean throwOnLaunch = false;

        /**
         * Test seam: when set, {@link #launchPersistentSession} runs this shell script instead of the default
         * discard-only sink, so a test can control how (and whether) pi "answers" a command. Null in every test that
         * doesn't need a scripted response.
         */
        String scriptOverride;

        /**
         * Answers every command immediately with {@code success:false} — for compact()'s failure-path tests. The sed
         * pattern matches ANY lowercase/underscore {@code type} value, not a literal {@code "command"} — pi has no
         * generic "command" command, the command name IS the request's own {@code type} (e.g. {@code "compact"},
         * Round-5 live-test finding).
         */
        static final String ECHO_FAILURE_SCRIPT = """
                while IFS= read -r line; do
                    printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":false,"error":"cannot compact now"/'
                done
                """;

        /**
         * Answers every command with {@code success:true} after a short delay — long enough for a test to reassign
         * {@link #persistentSession} out from under the in-flight command before the response lands.
         */
        static final String DELAYED_ECHO_SUCCESS_SCRIPT = """
                while IFS= read -r line; do
                    sleep 0.2
                    printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
                done
                """;

        /**
         * Answers every command with {@code success:true} immediately (no delay) — for tests that need a real ack so
         * {@code processing} is not left to be raced/cleared by an unrelated ack-failure path when the process later
         * exits (see {@link PiAiProcessManagerStateTest#codeZeroExitDuringATurnStillReportsExited}).
         */
        static final String IMMEDIATE_ECHO_SUCCESS_SCRIPT = """
                while IFS= read -r line; do
                    printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
                done
                """;

        /**
         * Ignores the initial prompt (never acks it — the discard-sink pattern other tests use), then, once it sees the
         * {@code abort} command interrupt(Cancel) sends, replies with the exact tail a real pi 0.85.1 process was
         * observed sending when an abort lands DURING a tool call (live finding, Boss 2026-09-18): the in-flight tool's
         * own {@code tool_execution_end isError:true}, a {@code message_end stopReason:"error"} (NOT
         * {@code "aborted"}), {@code agent_settled}, and finally the {@code abort} command's own {@code success:true}
         * response.
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

        TestablePiAiProcessManager(AiProcessEventListener listener) {
            super(listener);
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

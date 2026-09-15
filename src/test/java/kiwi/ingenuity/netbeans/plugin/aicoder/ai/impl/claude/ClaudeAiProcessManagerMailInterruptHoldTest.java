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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.session.ClaudePersistentSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #9 / F5: a Mail interrupt must never abort a tool call this plugin is itself servicing over the MCP HTTP endpoint —
 * the CLI treats {@code control_request(interrupt)} as "the user doesn't want to proceed" and cancels whatever it's
 * waiting on. These tests drive {@link ClaudeAiProcessManager} exactly like {@link ClaudeAiProcessManagerStateTest}
 * does: a real {@code /bin/cat} subprocess stands in for the CLI, and synthetic stream-json lines are fed to it via
 * {@link ClaudePersistentSession#sendRawLine}, which cat echoes straight back out as if the CLI had sent it — the same
 * line-consumer path (including {@code trackToolCallLifecycle}) that real CLI output would take. No real CLI is
 * launched.
 */
class ClaudeAiProcessManagerMailInterruptHoldTest {

    private static final String TOOL_USE_LINE
            = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":"
            + "[{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"Read\",\"input\":{}}]}}";
    private static final String TOOL_RESULT_LINE
            = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
            + "[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\",\"content\":\"ok\"}]}}";
    private static final String RESULT_LINE = "{\"type\":\"result\",\"subtype\":\"success\"}";

    private TestableClaudeAiProcessManager manager;
    private File workDir;

    private static void awaitTrue(BooleanSupplier cond, String desc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timeout waiting for " + desc);
            }
            Thread.sleep(20);
        }
    }

    @BeforeEach
    void setup() throws IOException {
        manager = new TestableClaudeAiProcessManager(new RecordingEventListener());
        workDir = Files.createTempDirectory("claude-mail-hold-test").toFile();
        manager.setupForTest();
    }

    @AfterEach
    void teardown() {
        manager.stop();
    }

    // ---- pure classifier coverage ----
    @Test
    void countToolUseStartsCountsEachBlockInAnAssistantMessage() {
        String twoToolUses = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":"
                + "[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"Read\",\"input\":{}},"
                + "{\"type\":\"text\",\"text\":\"thinking\"},"
                + "{\"type\":\"tool_use\",\"id\":\"b\",\"name\":\"Read\",\"input\":{}}]}}";
        assertEquals(2, ClaudeAiProcessManager.countToolUseStarts(twoToolUses));
        assertEquals(0, ClaudeAiProcessManager.countToolUseStarts(TOOL_RESULT_LINE), "wrong event type");
        assertEquals(0, ClaudeAiProcessManager.countToolUseStarts("not json"), "malformed line must not throw");
        assertEquals(0, ClaudeAiProcessManager.countToolUseStarts(null));
    }

    @Test
    void countToolResultsCountsOnlyUserToolResultBlocks() {
        assertEquals(1, ClaudeAiProcessManager.countToolResults(TOOL_RESULT_LINE));
        assertEquals(0, ClaudeAiProcessManager.countToolResults(TOOL_USE_LINE), "wrong event type");
        assertEquals(0, ClaudeAiProcessManager.countToolResults("{}"));
    }

    @Test
    void isTurnEndLineMatchesOnlyResultEvents() {
        assertTrue(ClaudeAiProcessManager.isTurnEndLine(RESULT_LINE));
        assertFalse(ClaudeAiProcessManager.isTurnEndLine(TOOL_USE_LINE));
        assertFalse(ClaudeAiProcessManager.isTurnEndLine("garbage"));
    }

    // ---- manager-level hold/flush behaviour ----
    @Test
    void mailInterruptIsSentImmediatelyWhenNoToolCallInFlight() {
        manager.sendPrompt("prompt", workDir, List.of());
        assertTrue(manager.isProcessing());

        manager.interrupt(InterruptTypeEnum.Mail);

        assertTrue(manager.isTurnInterrupted(), "idle turn (no tool call in flight) must interrupt immediately");
    }

    @Test
    void mailInterruptIsHeldWhileToolCallInFlightThenSentAfterResult() throws InterruptedException {
        manager.sendPrompt("prompt", workDir, List.of());
        manager.getSession().sendRawLine(TOOL_USE_LINE);
        awaitTrue(() -> manager.getInFlightToolCalls() == 1, "tool_use tracked as in flight");

        manager.interrupt(InterruptTypeEnum.Mail);

        // Give the (incorrect) immediate-send behaviour a chance to happen before asserting its absence.
        Thread.sleep(100);
        assertFalse(manager.isTurnInterrupted(), "must NOT interrupt while the tool call is still in flight");

        manager.getSession().sendRawLine(TOOL_RESULT_LINE);

        awaitTrue(manager::isTurnInterrupted, "interrupt flushed once the in-flight tool call's result arrived");
        assertEquals(0, manager.getInFlightToolCalls());
    }

    @Test
    void mailInterruptHoldIsClearedOnTurnEndWithoutSendingAMootInterrupt() throws InterruptedException {
        // Backstop path: a turn can end (error, cancellation) without every tool_use ever seeing its tool_result.
        // There is nothing left mid-turn to interrupt at that point, so turn end must clear the hold WITHOUT
        // sending — the mail was already delivered by the broker regardless, and will be visible in the session's
        // own context on its next turn either way.
        manager.sendPrompt("prompt", workDir, List.of());
        manager.getSession().sendRawLine(TOOL_USE_LINE);
        awaitTrue(() -> manager.getInFlightToolCalls() == 1, "tool_use tracked as in flight");

        manager.interrupt(InterruptTypeEnum.Mail);
        assertTrue(manager.isMailInterruptPending(), "must be held while the tool call is in flight");

        manager.getSession().sendRawLine(RESULT_LINE);

        awaitTrue(() -> !manager.isMailInterruptPending(), "turn end must clear the held interrupt");
        assertEquals(0, manager.getInFlightToolCalls());
        assertFalse(manager.isTurnInterrupted(), "turn end must not send a moot interrupt");
    }

    @Test
    void safetyValveDeliversTheInterruptIfTheToolCallNeverResolves() throws InterruptedException {
        manager.mailInterruptSafetyValveMillis = 150;
        manager.sendPrompt("prompt", workDir, List.of());
        manager.getSession().sendRawLine(TOOL_USE_LINE);
        awaitTrue(() -> manager.getInFlightToolCalls() == 1, "tool_use tracked as in flight");

        manager.interrupt(InterruptTypeEnum.Mail);
        assertFalse(manager.isTurnInterrupted(), "must be held immediately after the request");

        // No tool_result and no turn end ever arrives — only the safety valve can flush this.
        awaitTrue(manager::isTurnInterrupted, "safety valve must deliver the held interrupt");
    }

    @Test
    void secondMailInterruptWhileAlreadyHeldDoesNotStartASecondWatchdog() throws InterruptedException {
        // Two important messages arriving during the same in-flight call must still resolve to exactly one
        // control_request once the call completes — not fire the flush logic twice or leave state inconsistent.
        manager.sendPrompt("prompt", workDir, List.of());
        manager.getSession().sendRawLine(TOOL_USE_LINE);
        awaitTrue(() -> manager.getInFlightToolCalls() == 1, "tool_use tracked as in flight");

        manager.interrupt(InterruptTypeEnum.Mail);
        manager.interrupt(InterruptTypeEnum.Mail);
        Thread.sleep(100);
        assertFalse(manager.isTurnInterrupted());

        manager.getSession().sendRawLine(TOOL_RESULT_LINE);
        awaitTrue(manager::isTurnInterrupted, "interrupt flushed after result");
    }

    static class RecordingEventListener implements AiProcessEventListener {

        private final List<AiProcessEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onAiProcessEvent(AiProcessEvent event) {
            events.add(event);
        }
    }

    static class TestableClaudeAiProcessManager extends ClaudeAiProcessManager {

        TestableClaudeAiProcessManager(AiProcessEventListener listener) {
            super(listener);
        }

        void setupForTest() {
            running = true;
            sessionId = UUID.randomUUID().toString();
            model = "test-model";
            executablePath = "/bin/cat";
        }

        @Override
        protected ClaudePersistentSession launchPersistentSession(List<String> cmd, File workDir,
                                                                  Consumer<String> stdoutLine, Consumer<String> stderrLine) throws IOException {
            return ClaudePersistentSession.launch(List.of("/bin/cat"), workDir, stdoutLine, stderrLine);
        }

        ClaudePersistentSession getSession() {
            return persistentSession;
        }
    }
}

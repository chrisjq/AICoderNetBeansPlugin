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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.session.ClaudePersistentSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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
    void handleProcessExitStaleSuppression() throws InterruptedException {
        manager.sendPrompt("turn 1", workDir, List.of());
        ClaudePersistentSession sessionA = manager.getPersistentSession();

        manager.recycleForModelChange();

        manager.sendPrompt("turn 2", workDir, List.of());
        ClaudePersistentSession sessionB = manager.getPersistentSession();
        assertNotSame(sessionA, sessionB);

        events.clear();
        sessionA.process().destroyForcibly();
        Thread.sleep(200); // Fixed short sleep: asserting absence of EXITED event; no event expected

        assertFalse(events.hasEvent(StatusEvent.class, e -> ((StatusEvent) e).type() == StatusEventTypeEnum.EXITED));
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

        void clear() {
            events.clear();
        }
    }

    static class TestableClaudeAiProcessManager extends ClaudeAiProcessManager {
        private boolean firstSessionDead = false;

        TestableClaudeAiProcessManager(AiProcessEventListener listener) {
            super(listener);
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
    }
}

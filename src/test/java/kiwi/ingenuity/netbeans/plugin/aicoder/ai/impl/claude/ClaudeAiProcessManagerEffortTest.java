package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.session.ClaudePersistentSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code --effort} spawn flag in {@code buildLaunchCommand} (reached through the private
 * {@code ensureSession}, driven here via {@code sendPrompt} with a recording {@code launchPersistentSession}
 * sub-class): absent when no level is configured, present with the exact level when one is, blank/null normalized away,
 * and picked up at the next spawn across the model-change recycle path.
 */
class ClaudeAiProcessManagerEffortTest {

    private CapturingClaudeAiProcessManager manager;
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
        manager = new CapturingClaudeAiProcessManager(event -> {
        });
        manager.setupForTest();
        workDir = Files.createTempDirectory("claude-effort-test").toFile();
    }

    @AfterEach
    void teardown() {
        manager.stop();
    }

    @Test
    void omitsEffortFlagWhenNotConfigured() throws InterruptedException {
        manager.sendPrompt("prompt", workDir, List.of());
        List<String> cmd = manager.launchCommand(0);
        assertFalse(cmd.contains("--effort"), cmd.toString());
    }

    @Test
    void omitsEffortFlagWhenBlankConfigured() throws InterruptedException {
        manager.configureEffort("   ");
        manager.sendPrompt("prompt", workDir, List.of());
        List<String> cmd = manager.launchCommand(0);
        assertFalse(cmd.contains("--effort"), cmd.toString());
    }

    @Test
    void addsEffortFlagWithExactLevel() throws InterruptedException {
        manager.configureEffort("xhigh");
        manager.sendPrompt("prompt", workDir, List.of());
        List<String> cmd = manager.launchCommand(0);
        int idx = cmd.indexOf("--effort");
        assertTrue(idx >= 0, cmd.toString());
        assertEquals("xhigh", cmd.get(idx + 1));
    }

    /**
     * A mid-turn effort change must take the model-change recycle path. The relaunch itself must be deferred (recycle
     * refuses while a turn is in flight) but picked up by {@code ensureSession}'s
     * {@code launchedEffort}/{@code configuredEffort} comparison at the start of the next turn.
     */
    @Test
    void deferredEffortChangePicksUpAtNextTurn() throws InterruptedException {
        manager.configureEffort("high");
        manager.sendPrompt("turn 1", workDir, List.of());
        assertEquals(1, manager.getLaunchCount());
        List<String> cmd1 = manager.launchCommand(0);
        assertEquals("high", cmd1.get(cmd1.indexOf("--effort") + 1));
        awaitTrue(() -> manager.isProcessing(), "turn 1 in flight");

        manager.configureEffort("max");
        ClaudePersistentSession sessionA = manager.getPersistentSession();
        assertNotNull(sessionA);
        manager.recycleForModelChange();
        assertSame(sessionA, manager.getPersistentSession(),
                   "mid-turn recycle must be deferred, keeping the live session");

        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"success\"}");
        awaitTrue(() -> !manager.isProcessing(), "turn 1 complete");

        manager.sendPrompt("turn 2", workDir, List.of());
        assertEquals(2, manager.getLaunchCount());
        List<String> cmd2 = manager.launchCommand(1);
        assertEquals("max", cmd2.get(cmd2.indexOf("--effort") + 1));
    }

    @Test
    void idleConfigureEffortRecyclesImmediately() throws InterruptedException {
        manager.configureEffort("low");
        manager.sendPrompt("turn 1", workDir, List.of());
        assertEquals(1, manager.getLaunchCount());

        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"success\"}");
        awaitTrue(() -> !manager.isProcessing(), "turn 1 complete");

        manager.configureEffort("medium");
        manager.recycleForModelChange();

        manager.sendPrompt("turn 2", workDir, List.of());
        assertEquals(2, manager.getLaunchCount());
        List<String> cmd2 = manager.launchCommand(1);
        assertEquals("medium", cmd2.get(cmd2.indexOf("--effort") + 1));
    }

    @Test
    void sameSessionReusedWhenEffortUnchanged() throws InterruptedException {
        manager.configureEffort("medium");
        manager.sendPrompt("turn 1", workDir, List.of());
        assertEquals(1, manager.getLaunchCount());
        ClaudePersistentSession session1 = manager.getPersistentSession();
        assertNotNull(session1);

        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"success\"}");
        awaitTrue(() -> !manager.isProcessing(), "turn 1 complete");

        manager.sendPrompt("turn 2", workDir, List.of());
        assertEquals(1, manager.getLaunchCount());
        assertSame(session1, manager.getPersistentSession());
    }

    static class CapturingClaudeAiProcessManager extends ClaudeAiProcessManager {

        private final List<List<String>> launchCommands = new CopyOnWriteArrayList<>();

        CapturingClaudeAiProcessManager(AiProcessEventListener listener) {
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
            launchCommands.add(List.copyOf(cmd));
            return ClaudePersistentSession.launch(List.of("/bin/cat"), workDir, stdoutLine, stderrLine);
        }

        /**
         * The recorded launch command for the given index; blocks up to 5s if that turn's session has not launched yet.
         */
        List<String> launchCommand(int index) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (launchCommands.size() <= index) {
                if (System.currentTimeMillis() > deadline) {
                    fail("timeout waiting for launch command #" + (index + 1));
                }
                Thread.sleep(20);
            }
            return launchCommands.get(index);
        }

        ClaudePersistentSession getSession() {
            return persistentSession;
        }
    }
}

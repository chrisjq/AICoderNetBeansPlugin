package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeAiProcessManagerEffortTest.CapturingClaudeAiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ui.ClaudeAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A model or effort change updates the plain fields on the caller and hands the recycle to a background
 * executor, because {@code recycleForModelChange} takes the manager monitor that {@code start()} holds across
 * its MCP registration wait. Every task here is held in a queue the test releases, so no assertion depends on
 * timing.
 */
class ClaudeAiImplementationSessionControlTest {

    private static final String RESULT_LINE = "{\"type\":\"result\",\"subtype\":\"success\"}";

    private final AtomicReference<CountDownLatch> turnComplete = new AtomicReference<>(new CountDownLatch(1));
    private final HeldExecutor held = new HeldExecutor();
    private CapturingClaudeAiProcessManager manager;
    private ClaudeAiImplementation impl;
    private File workDir;

    @BeforeEach
    void setup() throws IOException {
        manager = new CapturingClaudeAiProcessManager(event -> {
            if (event instanceof TurnCompleteEvent) {
                turnComplete.get().countDown();
            }
        });
        manager.setupForTest();
        workDir = Files.createTempDirectory("claude-session-control-test").toFile();
        impl = new ClaudeAiImplementation(event -> {
        }, null) {
            @Override
            protected ClaudeAiProcessManager delegate() {
                return manager;
            }
        };
        impl.sessionControl = held;
    }

    @AfterEach
    void teardown() {
        manager.stop();
    }

    private void completeTurn() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        turnComplete.set(latch);
        manager.getSession().sendRawLine(RESULT_LINE);
        assertTrue(latch.await(30, TimeUnit.SECONDS), "turn did not complete");
    }

    private static String flagValue(List<String> cmd, String flag) {
        int idx = cmd.indexOf(flag);
        assertTrue(idx >= 0, flag + " missing from " + cmd);
        return cmd.get(idx + 1);
    }

    @Test
    void setModelReturnsWithoutRunningTheRecycle() throws Exception {
        manager.sendPrompt("turn 1", workDir, List.of());
        completeTurn();
        var session1 = manager.getPersistentSession();
        assertNotNull(session1);

        impl.setModel("new-model");

        assertEquals(1, held.size(), "the recycle must be queued for the background executor");
        assertSame(session1, manager.getPersistentSession(), "the recycle must not have run on the caller");
    }

    @Test
    void sendThatBeatsTheRecycleLaunchesWithTheNewModelAndTheLateRecycleChangesNothing() throws Exception {
        manager.sendPrompt("turn 1", workDir, List.of());
        completeTurn();
        assertEquals("test-model", flagValue(manager.launchCommand(0), "--model"));

        impl.setModel("new-model");
        manager.sendPrompt("turn 2", workDir, List.of());

        assertEquals(2, manager.getLaunchCount(), "the send must relaunch on its own, without the recycle");
        assertEquals("new-model", flagValue(manager.launchCommand(1), "--model"));
        var session2 = manager.getPersistentSession();

        held.runAll();

        assertSame(session2, manager.getPersistentSession(), "the late recycle must leave the running turn alone");
        assertEquals(2, manager.getLaunchCount());
    }

    @Test
    void recycleThatRunsFirstThenSendLaunchesWithTheNewModel() throws Exception {
        manager.sendPrompt("turn 1", workDir, List.of());
        completeTurn();

        impl.setModel("new-model");
        held.runAll();
        assertNull(manager.getPersistentSession(), "the idle recycle drops the old session");

        manager.sendPrompt("turn 2", workDir, List.of());

        assertEquals(2, manager.getLaunchCount());
        assertEquals("new-model", flagValue(manager.launchCommand(1), "--model"));
    }

    @Test
    void effortChangeSendBeatingTheRecycleLaunchesWithTheNewEffort() throws Exception {
        manager.sendPrompt("turn 1", workDir, List.of());
        completeTurn();

        impl.setEffort("max");
        assertEquals(1, held.size());
        manager.sendPrompt("turn 2", workDir, List.of());

        assertEquals(2, manager.getLaunchCount());
        assertEquals("max", flagValue(manager.launchCommand(1), "--effort"));
        var session2 = manager.getPersistentSession();
        held.runAll();
        assertSame(session2, manager.getPersistentSession());
    }

    @Test
    void modelChangeReturnsPromptlyWhileTheManagerMonitorIsHeldByStart() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread starter = new Thread(() -> {
            synchronized (manager) {
                holding.countDown();
                try {
                    release.await();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "monitor-holder");
        starter.start();
        try {
            assertTrue(holding.await(30, TimeUnit.SECONDS));

            CompletableFuture<Void> call = CompletableFuture.runAsync(() -> impl.setModel("new-model"));
            try {
                call.get(30, TimeUnit.SECONDS);
            }
            catch (java.util.concurrent.TimeoutException e) {
                fail("setModel blocked on the manager monitor held by start()");
            }
            assertEquals(1, held.size(), "the recycle waits in the queue instead of blocking the caller");
        }
        finally {
            release.countDown();
            starter.join();
        }
    }

    @Test
    void stoppedBeforeTheHeldRecycleRunsLeavesNothingRunning() throws Exception {
        manager.sendPrompt("turn 1", workDir, List.of());
        completeTurn();
        impl.setModel("new-model");

        manager.stop();
        held.runAll();

        assertNull(manager.getPersistentSession(), "no session may be resurrected after stop");
        assertEquals(0, manager.getLaunchCount(), "stop resets the count, so any launch by the late recycle shows");
        assertFalse(manager.isRunning());
    }

    @Test
    void compactClickIsDispatchedToTheExecutorAndStartsOnlyWhenItRuns() throws Exception {
        ClaudeSessionSettings settings = new ClaudeSessionSettings();
        AiSession session = new AiSession("claude-control-compact", "Test", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE, null, settings,
                java.time.Instant.now(), java.time.Instant.now());
        AiSessionHost host = new AiSessionHost() {
            @Override
            public File resolveWorkDir() {
                return workDir;
            }

            @Override
            public AiSessionSettings getSessionSettings() {
                return settings;
            }

            @Override
            public void updateSessionSettings(AiSessionSettings newSettings) {
            }
        };
        ClaudeAiInfoBarExtension bar = (ClaudeAiInfoBarExtension) impl.createInfoBarExtension(session, host);

        ((JButton) bar.createComponents().get(2)).doClick();

        assertEquals(1, held.size(), "Compact must be queued, not run on the click thread");
        assertEquals(0, manager.getLaunchCount(), "nothing may have launched on the click thread");
        assertFalse(manager.isWorkInFlight());

        held.runAll();

        assertEquals(1, manager.getLaunchCount(), "the queued task starts the compaction turn");
        assertNotNull(manager.getPersistentSession());
    }

    private static final class HeldExecutor implements Executor {

        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable task) {
            tasks.add(task);
        }

        synchronized int size() {
            return tasks.size();
        }

        void runAll() {
            Runnable next;
            while ((next = poll()) != null) {
                next.run();
            }
        }

        private synchronized Runnable poll() {
            return tasks.poll();
        }
    }
}

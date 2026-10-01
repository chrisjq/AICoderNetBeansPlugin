package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.rpc.SessionHistoryCompactResult;
import com.github.copilot.rpc.SessionConfig;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.ui.GithubCopilotAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A Copilot session binds its model and reasoning effort when it is opened. A change updates the plain fields
 * on the caller and queues the recycle on a background executor, because the recycle takes the manager
 * monitor that {@code start()} holds across its MCP wait; a send that gets in before the recycle replaces the
 * stale session itself. Recycles are held in a queue the test releases, so no assertion depends on timing.
 */
class GithubCopilotSessionControlTest {

    private final HeldExecutor held = new HeldExecutor();
    private final CopyOnWriteArrayList<String> created = new CopyOnWriteArrayList<>();
    private ScriptedManager manager;
    private GithubCopilotAiImplementation impl;

    @BeforeEach
    void setup() throws Exception {
        manager = new ScriptedManager(created);
        manager.seedLiveSession("gpt-a", null);
        impl = new GithubCopilotAiImplementation(event -> {
        }, null, manager);
        impl.sessionControl = held;
    }

    @AfterEach
    void teardown() {
        manager.stop();
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
    }

    @Test
    void setModelUpdatesTheModelOnTheCallerAndQueuesTheRecycle() throws Exception {
        CopilotSession live = liveSession();

        impl.setModel("gpt-b");

        assertTrue(manager.liveSessionIsStale(), "the new model must already be in force when setModel returns");
        assertEquals(1, held.size(), "the recycle must be queued for the background executor");
        assertSame(live, liveSession(), "the recycle must not have run on the caller");
    }

    @Test
    void sendThatBeatsTheRecycleReestablishesWithTheNewModel() throws Exception {
        impl.setModel("gpt-b");

        manager.sendPrompt("hello", null, List.of());
        manager.awaitReestablished();

        assertEquals(List.of("gpt-b|null"), created, "the replacement session must be opened with the new model");
        assertFalse(manager.liveSessionIsStale(), "the replacement records the model it was opened with");

        held.runAll();

        assertEquals(1, created.size(), "the late recycle must not open another session");
    }

    @Test
    void recycleThatRunsFirstThenSendReestablishesWithTheNewModel() throws Exception {
        impl.setModel("gpt-b");
        held.runAll();
        assertNull(liveSession(), "the idle recycle drops the old session");

        manager.sendPrompt("hello", null, List.of());
        manager.awaitReestablished();

        assertEquals(List.of("gpt-b|null"), created);
    }

    @Test
    void effortChangeSendBeatingTheRecycleReestablishesWithTheNewEffort() throws Exception {
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of("gpt-a", List.of("low", "high")), Map.of());

        impl.setReasoningEffort("high");
        assertTrue(manager.liveSessionIsStale());
        assertEquals(1, held.size());

        manager.sendPrompt("hello", null, List.of());
        manager.awaitReestablished();

        assertEquals(List.of("gpt-a|high"), created);
    }

    @Test
    void anEffortTheModelDoesNotSupportIsNotAChangeOnEveryTurn() throws Exception {
        manager.setReasoningEffort("high", false);
        manager.seedLiveSession("gpt-a", "high");
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of("gpt-a", List.of("low", "medium")), Map.of());

        assertFalse(manager.liveSessionIsStale(),
                "a global-sourced effort the model omits is unchanged, so the live session stays");
    }

    @Test
    void anUnchangedModelAndEffortLeaveTheLiveSessionAlone() throws Exception {
        manager.setReasoningEffort("high");
        manager.seedLiveSession("gpt-a", "high");

        assertFalse(manager.liveSessionIsStale());
    }

    @Test
    void aSessionWithNoRecordedLaunchIsNeverStale() throws Exception {
        set(manager, "launchRecorded", false);
        manager.setModelForTest("gpt-b");

        assertFalse(manager.liveSessionIsStale());
    }

    @Test
    void stopClearsTheLaunchRecordSoARestartedManagerStartsClean() throws Exception {
        manager.setModelForTest("gpt-b");
        assertTrue(manager.liveSessionIsStale());

        manager.stop();

        assertFalse(manager.liveSessionIsStale());
    }

    @Test
    void stoppedBeforeTheHeldRecycleRunsLeavesNothingRunning() throws Exception {
        impl.setModel("gpt-b");

        manager.stop();
        held.runAll();

        assertNull(liveSession(), "no session may be resurrected after stop");
        assertEquals(List.of(), created, "the late recycle must not open anything");
        assertFalse(manager.isRunning());
    }

    @Test
    void stopDuringTheReestablishDiscardsTheNewSession() throws Exception {
        CountDownLatch inHook = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScriptedManager slow = new ScriptedManager(created) {
            @Override
            CompletableFuture<CopilotSession> createSessionHook(CopilotClient client, SessionConfig config) {
                inHook.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test never released the create");
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.createSessionHook(client, config);
            }
        };
        slow.seedLiveSession("gpt-a", null);
        slow.setModelForTest("gpt-b");
        try {
            slow.sendPrompt("hello", null, List.of());
            assertTrue(inHook.await(30, TimeUnit.SECONDS));

            slow.stop();
            release.countDown();

            joinRecycleThreads();
            assertNull(get(slow, "copilotSession"), "a session opened after stop must not be published");
            assertFalse(slow.liveSessionIsStale(), "and no launch may be recorded for it");
        }
        finally {
            release.countDown();
        }
    }

    @Test
    void compactDoesNotWaitForTheManagerMonitor() throws Exception {
        CompletableFuture<SessionHistoryCompactResult> pending = new CompletableFuture<>();
        AtomicInteger compactCalls = new AtomicInteger();
        ScriptedManager compactable = new ScriptedManager(created) {
            @Override
            CompletableFuture<SessionHistoryCompactResult> compactHistory(String customInstructions) {
                compactCalls.incrementAndGet();
                return pending;
            }
        };
        compactable.seedLiveSession("gpt-a", null);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        AiSession session = new AiSession("gh-control-compact", "Test", null, AiTypeEnum.GitHubCoPilot, null,
                settings, Instant.now(), Instant.now());
        GithubCopilotAiImplementation compactImpl = new GithubCopilotAiImplementation(event -> {
        }, null, compactable) {
            {
                currentSession = session;
            }
        };
        AiSessionHost host = new AiSessionHost() {
            @Override
            public File resolveWorkDir() {
                return null;
            }

            @Override
            public AiSessionSettings getSessionSettings() {
                return settings;
            }

            @Override
            public void updateSessionSettings(AiSessionSettings newSettings) {
            }
        };
        GithubCopilotAiInfoBarExtension bar = compactImpl.createInfoBarExtension(session, host);
        JButton compact = (JButton) bar.createComponents().get(2);

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread starter = new Thread(() -> {
            synchronized (compactable) {
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
            try {
                CompletableFuture.runAsync(compact::doClick).get(30, TimeUnit.SECONDS);
            }
            catch (TimeoutException e) {
                fail("Compact blocked on the manager monitor held by start()");
            }
            assertEquals(1, compactCalls.get());
        }
        finally {
            release.countDown();
            starter.join();
            pending.complete(null);
        }
    }

    private static void joinRecycleThreads() throws InterruptedException {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("copilot-model-recycle".equals(t.getName())) {
                t.join(30_000);
                assertFalse(t.isAlive(), "the re-establish thread did not finish");
            }
        }
    }

    private CopilotSession liveSession() throws Exception {
        return (CopilotSession) get(manager, "copilotSession");
    }

    private static Object get(Object target, String fieldName) throws Exception {
        Field f = GithubCopilotProcessManager.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field f = GithubCopilotProcessManager.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static CopilotSession newDetachedSession() throws Exception {
        @SuppressWarnings("unchecked")
        Constructor<CopilotSession> ctor = (Constructor<CopilotSession>) Arrays
                .stream(CopilotSession.class.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == 3 && c.getParameterTypes()[0] == String.class)
                .findFirst().orElseThrow();
        ctor.setAccessible(true);
        return ctor.newInstance("test-session", null, null);
    }

    /**
     * Opens "sessions" through the manager's create hook, recording the model and effort each was opened
     * with,
     * and reports when a background re-establish has finished its trailing send.
     */
    private static class ScriptedManager extends GithubCopilotProcessManager {

        private final List<String> created;
        private final CountDownLatch reestablished = new CountDownLatch(1);

        ScriptedManager(List<String> created) {
            this(created, event -> {
            });
        }

        ScriptedManager(List<String> created, AiProcessEventListener listener) {
            super(listener);
            this.created = created;
        }

        void seedLiveSession(String liveModel, String liveEffort) throws Exception {
            running = true;
            model = liveModel;
            set(this, "copilotSession", newDetachedSession());
            set(this, "launchedModel", liveModel);
            set(this, "launchedEffort", liveEffort);
            set(this, "launchRecorded", true);
        }

        void setModelForTest(String newModel) {
            setModel(newModel);
        }

        void awaitReestablished() throws InterruptedException {
            assertTrue(reestablished.await(30, TimeUnit.SECONDS), "the background re-establish did not finish");
        }

        @Override
        CompletableFuture<CopilotSession> createSessionHook(CopilotClient client, SessionConfig config) {
            created.add(config.getModel() + "|" + config.getReasoningEffort());
            try {
                return CompletableFuture.completedFuture(newDetachedSession());
            }
            catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
            boolean onRecycleThread = "copilot-model-recycle".equals(Thread.currentThread().getName());
            try {
                super.sendPrompt(text, workingDir, projectDirs);
            }
            catch (RuntimeException e) {
                if (!onRecycleThread) {
                    throw e;
                }
            }
            finally {
                if (onRecycleThread) {
                    reestablished.countDown();
                }
            }
        }
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
                try {
                    next.run();
                }
                catch (RuntimeException e) {
                    // the production executor logs and carries on; a detached test session cannot close cleanly
                }
            }
        }

        private synchronized Runnable poll() {
            return tasks.poll();
        }
    }
}

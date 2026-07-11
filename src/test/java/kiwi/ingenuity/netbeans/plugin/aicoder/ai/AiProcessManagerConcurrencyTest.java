package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeAiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.GithubCopilotProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.GrokAiProcessManager;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The AI tab UI (EDT) calls trivial accessors and resumeSession()/isMcpActive()
 * on the process managers while a background RequestProcessor thread may be
 * inside the manager's synchronized start() for seconds (CLI spawn, MCP
 * registration, session handshake). Those EDT-called methods must therefore
 * never contend on the monitor that start() holds — otherwise opening sessions
 * freezes the whole NetBeans UI until startup completes (observed as two
 * multi-second lockups when opening tabs from the session picker).
 *
 * Each test simulates a long-running start() by holding the manager's monitor
 * on another thread, then asserts the EDT-facing methods still return promptly.
 */
class AiProcessManagerConcurrencyTest {

    /** How long the simulated start() holds the manager's monitor. */
    private static final long HOLD_MILLIS = 3000;
    /** Budget for an EDT-facing call — generous, but far below HOLD_MILLIS. */
    private static final long CALL_BUDGET_MILLIS = 1000;

    private static ExecutorService pool;

    @BeforeAll
    static void setUp() {
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "concurrency-test");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterAll
    static void tearDown() {
        pool.shutdownNow();
    }

    /**
     * Runs {@code edtCalls} while another thread holds {@code manager}'s
     * monitor, failing if the calls do not complete within the budget.
     */
    private static void assertNonBlockingWhileMonitorHeld(Object manager, Runnable edtCalls) throws Exception {
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> {
            synchronized (manager) {
                monitorHeld.countDown();
                try {
                    release.await(HOLD_MILLIS, TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        if (!monitorHeld.await(5, TimeUnit.SECONDS)) {
            fail("test setup: monitor holder never started");
        }
        Future<?> caller = pool.submit(edtCalls);
        try {
            caller.get(CALL_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e) {
            fail("EDT-facing manager call blocked on the manager monitor "
                    + "(would freeze the NetBeans UI while start() runs)");
        }
        finally {
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void claudeManager_edtFacingCalls_doNotBlockWhileStartHoldsMonitor() throws Exception {
        ClaudeAiProcessManager mgr = new ClaudeAiProcessManager(event -> {
        });
        assertNonBlockingWhileMonitorHeld(mgr, () -> {
            mgr.getSessionId();
            mgr.getSessionWorkingDir();
            mgr.getMcpServer();
            mgr.isMcpActive();
            mgr.resumeSession("some-stored-session-id");
        });
    }

    @Test
    void copilotManager_edtFacingCalls_doNotBlockWhileStartHoldsMonitor() throws Exception {
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(event -> {
        });
        assertNonBlockingWhileMonitorHeld(mgr, () -> {
            mgr.getSessionId();
            mgr.getSessionWorkingDir();
            mgr.getMcpServer();
            mgr.isMcpActive();
            mgr.resumeSession("some-stored-session-id");
        });
    }

    @Test
    void grokManager_edtFacingCalls_doNotBlockWhileStartHoldsMonitor() throws Exception {
        GrokAiProcessManager mgr = new GrokAiProcessManager(event -> {
        });
        assertNonBlockingWhileMonitorHeld(mgr, () -> {
            mgr.getSessionId();
            mgr.getSessionWorkingDir();
            mgr.getMcpServer();
            mgr.isMcpActive();
            mgr.resumeSession("some-stored-session-id");
        });
    }
}

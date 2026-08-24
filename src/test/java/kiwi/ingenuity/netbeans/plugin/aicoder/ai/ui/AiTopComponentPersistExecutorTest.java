package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Shutdown behaviour of the shared session-persist pool. The pool's four core threads never time out, so a plugin
 * shutdown that skips {@link AiTopComponent#shutdownPersistExecutor()} leaves them alive pinning the module classloader
 * after a disable/uninstall without an IDE restart — while a shutdown that discards queued work would lose pending
 * history/session saves, which is worse. These tests pin both halves of that contract.
 */
class AiTopComponentPersistExecutorTest {

    private static final String WORKER_THREAD_NAME = "ai-session-persist";

    @AfterEach
    void tearDown() {
        // Later tests in this JVM must find a working pool again.
        AiTopComponent.resetPersistExecutorForTests();
    }

    private static boolean persistWorkerAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> WORKER_THREAD_NAME.equals(t.getName()) && t.isAlive());
    }

    private static boolean awaitNoPersistWorkers(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!persistWorkerAlive()) {
                return true;
            }
            Thread.sleep(20);
        }
        return !persistWorkerAlive();
    }

    @Test
    void shutdownCompletesQueuedWorkThenReleasesWorkerThreads() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        AiTopComponent.persistExecutor().execute(() -> {
            started.countDown();
            try {
                // Models an in-flight history save: still running when shutdown begins.
                Thread.sleep(300);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // an interrupt would mean the save was cut short — completed stays false
            }
            completed.set(true);
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "task must be running before shutdown starts");

        AiTopComponent.shutdownPersistExecutor();

        assertTrue(completed.get(), "in-flight persist work must complete during shutdown, not be discarded");
        assertTrue(AiTopComponent.persistExecutor().isTerminated(),
                "pool must be fully terminated when shutdown returns");
        assertTrue(awaitNoPersistWorkers(2000),
                "no '" + WORKER_THREAD_NAME + "' thread may survive shutdown (classloader pin)");
    }

    @Test
    void shutdownIsIdempotent() {
        AiTopComponent.shutdownPersistExecutor();
        AiTopComponent.shutdownPersistExecutor(); // second call must be a harmless no-op
        assertTrue(AiTopComponent.persistExecutor().isTerminated());
    }
}

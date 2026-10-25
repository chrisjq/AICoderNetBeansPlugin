package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AiProcessManagerToolExecutorTest {

    @Test
    @Timeout(10)
    void noExecutorUntilFirstUse() {
        var pm = new TestManager();
        assertFalse(pm.hasToolExecutor());
        pm.callToolExecutor().execute(() -> {
        });
        assertTrue(pm.hasToolExecutor());
        pm.callShutdownToolExecutorAsync();
    }

    @Test
    @Timeout(10)
    void shutdownAsyncReturnsImmediatelyEvenWithABlockedTool() throws Exception {
        var pm = new TestManager();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pm.callToolExecutor().execute(() -> {
            started.countDown();
            try {
                release.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        pm.callShutdownToolExecutorAsync();
        assertTrue(System.nanoTime() - t0 < TimeUnit.MILLISECONDS.toNanos(100));
        assertFalse(pm.hasToolExecutor());
        release.countDown();
    }

    @Test
    @Timeout(10)
    void runsToolsSequentially() throws Exception {
        var pm = new TestManager();
        var order = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var done = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            int n = i;
            pm.callToolExecutor().execute(() -> {
                order.add(n);
                done.countDown();
            });
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(0, 1, 2), order);
        pm.callShutdownToolExecutorAsync();
    }

    private static final class TestManager extends AiProcessManager {

        TestManager() {
            super((AiProcessEventListener) event -> {
            });
        }

        ExecutorService callToolExecutor() {
            return toolExecutor();
        }

        void callShutdownToolExecutorAsync() {
            shutdownToolExecutorAsync();
        }

        @Override
        public void start(String executableOrConfig, String modelOrConfig) {
        }

        @Override
        public void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        }

        @Override
        public void interrupt(InterruptTypeEnum type) {
        }

        @Override
        public void stop() {
        }

        @Override
        public void resumeSession(String existingSessionId) {
        }

        @Override
        public boolean isMcpActive() {
            return false;
        }
    }
}

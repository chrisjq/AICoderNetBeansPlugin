package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class BackendExecutorsTest {

    @AfterEach
    void tearDown() {
        BackendExecutors.shutdown();
    }

    @Test
    @Timeout(10)
    void schedulerRunsTasksAndRemovesCancelledOnes() throws Exception {
        var latch = new CountDownLatch(1);
        BackendExecutors.scheduler().schedule(latch::countDown, 10, TimeUnit.MILLISECONDS);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        ScheduledFuture<?> later = BackendExecutors.scheduler().schedule(() -> {
        }, 1, TimeUnit.HOURS);
        later.cancel(false);
        var pool = (ScheduledThreadPoolExecutor) BackendExecutors.scheduler();
        assertEquals(0, pool.getQueue().size());
    }

    @Test
    @Timeout(10)
    void recreatedLazilyAfterShutdown() throws Exception {
        var first = BackendExecutors.scheduler();
        BackendExecutors.shutdown();
        assertTrue(first.isShutdown());
        var second = BackendExecutors.scheduler();
        assertNotSame(first, second);
        var latch = new CountDownLatch(1);
        BackendExecutors.probePool().execute(latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(10)
    void threadsAreDaemonsAndNamed() throws Exception {
        var name = new String[1];
        var daemon = new boolean[1];
        var latch = new CountDownLatch(1);
        BackendExecutors.probePool().execute(() -> {
            name[0] = Thread.currentThread().getName();
            daemon[0] = Thread.currentThread().isDaemon();
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(name[0].startsWith("aicoder-probe-"));
        assertTrue(daemon[0]);
    }
}

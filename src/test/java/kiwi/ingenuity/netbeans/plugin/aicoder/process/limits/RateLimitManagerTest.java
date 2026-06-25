package kiwi.ingenuity.netbeans.plugin.aicoder.process.limits;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.limits.RateLimitManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class RateLimitManagerTest {

    @Test
    void runsImmediatelyWhenNotRateLimited() throws Exception {
        RateLimitManager rlm = new RateLimitManager();
        CountDownLatch ran = new CountDownLatch(1);
        boolean accepted = rlm.submitWhenClear("usage", ran::countDown);
        assertTrue(accepted);
        assertTrue(ran.await(1, TimeUnit.SECONDS), "task should run immediately when clear");
        rlm.shutdown();
    }

    @Test
    void coalescesSameKeyWhilePending() throws Exception {
        RateLimitManager rlm = new RateLimitManager();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger secondRuns = new AtomicInteger();
        boolean a = rlm.submitWhenClear("usage", () -> {
            firstStarted.countDown();
            try {
                gate.await(2, TimeUnit.SECONDS);
            }
            catch (InterruptedException ignored) {
            }
        });
        assertTrue(a);
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        boolean b = rlm.submitWhenClear("usage", secondRuns::incrementAndGet);
        assertFalse(b, "second submit for same pending key should be dropped");
        gate.countDown();
        assertEquals(0, secondRuns.get());
        rlm.shutdown();
    }

    @Test
    void differentKeysBothRun() throws Exception {
        RateLimitManager rlm = new RateLimitManager();
        CountDownLatch both = new CountDownLatch(2);
        assertTrue(rlm.submitWhenClear("usage", both::countDown));
        assertTrue(rlm.submitWhenClear("models", both::countDown));
        assertTrue(both.await(1, TimeUnit.SECONDS), "independent keys must not coalesce");
        rlm.shutdown();
    }

    @Test
    void keyClearedAfterCompletionAllowsResubmit() throws Exception {
        RateLimitManager rlm = new RateLimitManager();
        CountDownLatch first = new CountDownLatch(1);
        assertTrue(rlm.submitWhenClear("usage", first::countDown));
        assertTrue(first.await(1, TimeUnit.SECONDS));
        // give the wrapper's finally a moment to clear the key
        Thread.sleep(100);
        CountDownLatch second = new CountDownLatch(1);
        assertTrue(rlm.submitWhenClear("usage", second::countDown),
                "key should be free again after the first task completed");
        assertTrue(second.await(1, TimeUnit.SECONDS));
        rlm.shutdown();
    }

    @Test
    void deferredNotRunImmediatelyWhenRateLimited() throws Exception {
        RateLimitManager rlm = new RateLimitManager();
        rlm.setRateLimit(60_000); // long window; deadline is now + 60s + offset
        AtomicInteger runs = new AtomicInteger();
        boolean accepted = rlm.submitWhenClear("usage", runs::incrementAndGet);
        assertTrue(accepted, "rate-limited task must be accepted (scheduled), not dropped");
        Thread.sleep(300);
        assertEquals(0, runs.get(), "task must be deferred, not run immediately");
        rlm.shutdown();
    }

    @Test
    void submitAfterShutdownReturnsFalse() {
        RateLimitManager rlm = new RateLimitManager();
        rlm.shutdown();
        assertFalse(rlm.submitWhenClear("usage", () -> {
        }),
                "submitting after shutdown must not throw and must report not-accepted");
    }
}

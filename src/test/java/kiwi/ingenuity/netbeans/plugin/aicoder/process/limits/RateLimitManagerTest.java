package kiwi.ingenuity.netbeans.plugin.aicoder.process.limits;

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
    void burstOfDuplicateSubmissionsCoalescesToExactlyOne() throws Exception {
        // Simulates onTurnComplete() firing rapidly across many quick turns
        // while one usage fetch is already in flight — none of the duplicates
        // should be accepted or queued, so there is no unbounded backlog no
        // matter how many times the same key is resubmitted.
        RateLimitManager rlm = new RateLimitManager();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger totalRuns = new AtomicInteger();
        AtomicInteger acceptedCount = new AtomicInteger();

        boolean first = rlm.submitWhenClear("usage", () -> {
            firstStarted.countDown();
            try {
                gate.await(2, TimeUnit.SECONDS);
            }
            catch (InterruptedException ignored) {
            }
            totalRuns.incrementAndGet();
        });
        assertTrue(first);
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        int burstSize = 50;
        for (int i = 0; i < burstSize; i++) {
            if (rlm.submitWhenClear("usage", totalRuns::incrementAndGet)) {
                acceptedCount.incrementAndGet();
            }
        }
        assertEquals(0, acceptedCount.get(), "no duplicate in a burst should be accepted while one is pending");

        gate.countDown();
        Thread.sleep(200);
        assertEquals(1, totalRuns.get(), "exactly one run total — the burst must not queue up any backlog");
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
    void capsAbsurdlyLargeRateLimit() {
        RateLimitManager rlm = new RateLimitManager();
        // A hostile / malformed Retry-After that would otherwise brick the client
        // until the IDE restarts.
        rlm.setRateLimit(Long.MAX_VALUE / 2);
        assertTrue(rlm.isRateLimited());
        long remaining = rlm.getRetryAfterMs();
        // 15 min cap + 10s offset; allow a little slack for scheduling.
        long maxExpected = 15L * 60L * 1000L + 10_000L + 5_000L;
        assertTrue(remaining <= maxExpected,
                "rate-limit window must be capped, was " + remaining + "ms");
        rlm.shutdown();
    }

    @Test
    void clearRateLimitUnblocksAndAllowsImmediateRun() throws Exception {
        RateLimitManager rlm = new RateLimitManager();
        rlm.setRateLimit(60_000);
        assertTrue(rlm.isRateLimited(), "precondition: rate limited");
        // Simulates a re-auth clearing a stale lockout so a fresh token is retried.
        rlm.clearRateLimit();
        assertFalse(rlm.isRateLimited(), "clearRateLimit must lift the lockout");
        CountDownLatch ran = new CountDownLatch(1);
        assertTrue(rlm.submitWhenClear("usage", ran::countDown));
        assertTrue(ran.await(1, TimeUnit.SECONDS),
                "task must run immediately once the rate limit is cleared");
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

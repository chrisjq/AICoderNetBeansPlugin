package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The backing executor is a queue the test drives by hand, so ordering and the one-drain-at-a-time
 * guarantee are asserted directly rather than inferred from thread timing.
 */
class SerialBackgroundExecutorTest {

    private static class ManualBacking implements Executor {

        private final ArrayDeque<Runnable> submitted = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            submitted.add(task);
        }

        int pending() {
            return submitted.size();
        }

        void runNext() {
            submitted.poll().run();
        }
    }

    @Test
    void manyTasksShareOneDrainAndRunInSubmissionOrder() {
        ManualBacking backing = new ManualBacking();
        SerialBackgroundExecutor executor = new SerialBackgroundExecutor(backing);
        List<Integer> order = new ArrayList<>();

        executor.execute(() -> order.add(1));
        executor.execute(() -> order.add(2));
        executor.execute(() -> order.add(3));

        assertEquals(1, backing.pending(), "a second drain would let tasks run concurrently");
        assertEquals(List.of(), order, "nothing may run on the caller");

        backing.runNext();

        assertEquals(List.of(1, 2, 3), order);
    }

    @Test
    void aTaskSubmittedAfterTheQueueDrainedStartsANewDrain() {
        ManualBacking backing = new ManualBacking();
        SerialBackgroundExecutor executor = new SerialBackgroundExecutor(backing);
        List<Integer> order = new ArrayList<>();

        executor.execute(() -> order.add(1));
        backing.runNext();
        executor.execute(() -> order.add(2));

        assertEquals(1, backing.pending());
        backing.runNext();
        assertEquals(List.of(1, 2), order);
    }

    @Test
    void aFailingTaskDoesNotStopTheTasksAfterIt() {
        ManualBacking backing = new ManualBacking();
        SerialBackgroundExecutor executor = new SerialBackgroundExecutor(backing);
        List<Integer> order = new ArrayList<>();

        executor.execute(() -> {
            throw new IllegalStateException("expected by the test");
        });
        executor.execute(() -> order.add(2));
        backing.runNext();

        assertEquals(List.of(2), order);
    }

    @Test
    void aRejectedDrainLeavesNothingQueuedAndTheNextTaskStartsANewDrain() {
        boolean[] rejecting = {true};
        ManualBacking backing = new ManualBacking() {
            @Override
            public void execute(Runnable task) {
                if (rejecting[0]) {
                    throw new RejectedExecutionException("shutting down");
                }
                super.execute(task);
            }
        };
        SerialBackgroundExecutor executor = new SerialBackgroundExecutor(backing);
        List<Integer> order = new ArrayList<>();

        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> order.add(1)));
        rejecting[0] = false;
        executor.execute(() -> order.add(2));

        assertEquals(1, backing.pending(), "a rejected drain must not leave the executor thinking one is running");
        backing.runNext();
        assertEquals(List.of(2), order, "the rejected task must not run later");
    }

    @Test
    void theDefaultExecutorRunsTasksOffTheCallerThread() throws Exception {
        SerialBackgroundExecutor executor = new SerialBackgroundExecutor();
        AtomicReference<Thread> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.execute(() -> {
            ran.set(Thread.currentThread());
            done.countDown();
        });

        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), ran.get());
        assertTrue(ran.get().isDaemon(), "control threads must not keep the IDE alive");
    }
}

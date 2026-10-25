package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.BackendExecutors;

/**
 * Cancel token for one turn's requests. {@link #cancel()} is idempotent and returns at once: it stops the attempt that
 * is in flight (body, pending HTTP future) and wakes a backoff wait. A request opened after {@code cancel()} ends as
 * CANCELLED without touching the network.
 */
public final class RequestHandle {

    /**
     * Implemented by the client's in-flight attempt.
     */
    interface Cancellable {

        void cancelAttempt();
    }

    private final Object lock = new Object();
    private boolean cancelled;
    private Cancellable current;
    private CompletableFuture<Boolean> backoffWaiter;

    public void cancel() {
        Cancellable attempt;
        CompletableFuture<Boolean> waiter;
        synchronized (lock) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            attempt = current;
            waiter = backoffWaiter;
        }
        if (attempt != null) {
            attempt.cancelAttempt();
        }
        if (waiter != null) {
            waiter.complete(false);
        }
    }

    public boolean isCancelled() {
        synchronized (lock) {
            return cancelled;
        }
    }

    /**
     * @return false when the handle is already cancelled (the attempt must not start)
     */
    boolean attach(Cancellable attempt) {
        synchronized (lock) {
            if (cancelled) {
                return false;
            }
            current = attempt;
            return true;
        }
    }

    void detach(Cancellable attempt) {
        synchronized (lock) {
            if (current == attempt) {
                current = null;
            }
        }
    }

    /**
     * Waits {@code delay} on the scheduler without blocking a thread.
     *
     * @return a future that completes true after the delay, or false at once when the handle is cancelled
     */
    CompletableFuture<Boolean> backoff(Duration delay) {
        CompletableFuture<Boolean> waiter = new CompletableFuture<>();
        synchronized (lock) {
            if (cancelled) {
                waiter.complete(false);
                return waiter;
            }
            backoffWaiter = waiter;
        }
        ScheduledFuture<?> timer = BackendExecutors.scheduler()
                .schedule(() -> waiter.complete(true), Math.max(0L, delay.toMillis()), TimeUnit.MILLISECONDS);
        waiter.whenComplete((value, error) -> {
            timer.cancel(false);
            synchronized (lock) {
                if (backoffWaiter == waiter) {
                    backoffWaiter = null;
                }
            }
        });
        return waiter;
    }
}

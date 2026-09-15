package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Resolves a user-approval response after its bounded attention window expires. The response future remains the single
 * authority: a user answer cancels the deadline, and a deadline only runs its timeout action if it won that race.
 */
public final class ApprovalDeadline {

    public static final String TIMEOUT_REASON
            = "Approval not granted: timed out waiting for the user after 120s; please retry if approval is still needed.";

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ai-approval-deadline");
        thread.setDaemon(true);
        return thread;
    });

    private ApprovalDeadline() {
    }

    public static <T> void arm(CompletableFuture<T> response, long timeoutMillis,
                               T timeoutValue, Runnable onTimeout) {
        arm(response, timeoutMillis, timeoutValue, onTimeout, SCHEDULER);
    }

    static <T> void arm(CompletableFuture<T> response, long timeoutMillis,
                        T timeoutValue, Runnable onTimeout, ScheduledExecutorService scheduler) {
        ScheduledFuture<?> deadline = scheduler.schedule(() -> {
            if (response.complete(timeoutValue)) {
                onTimeout.run();
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        response.whenComplete((value, error) -> deadline.cancel(false));
    }
}

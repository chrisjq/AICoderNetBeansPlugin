package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ApprovalDeadlineTest {

    @Test
    void timeoutCompletesResponseAsNotApproved() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            CompletableFuture<String> response = new CompletableFuture<>();
            ApprovalDeadline.arm(response, 1L, "timed out", () -> {
                         }, scheduler);
            assertEquals("timed out", response.get(1, TimeUnit.SECONDS));
        }
        finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void doubleArmCompletesOnceWithTimeoutDecision() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            CompletableFuture<String> response = new CompletableFuture<>();
            ApprovalDeadline.arm(response, 1L, "timed out", () -> {
                         }, scheduler);
            ApprovalDeadline.arm(response, 5L, "second timeout", () -> {
                         }, scheduler);
            assertEquals("timed out", response.get(1, TimeUnit.SECONDS));
            assertTrue(response.complete("late answer") == false);
            assertEquals("timed out", response.get(1, TimeUnit.SECONDS));
        }
        finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void answeredResponseCancelsDeadline() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            CompletableFuture<String> response = new CompletableFuture<>();
            ApprovalDeadline.arm(response, 1000L, "timed out", () -> {
                         }, scheduler);
            assertTrue(response.complete("approved"));
            assertEquals("approved", response.get(1, TimeUnit.SECONDS));
        }
        finally {
            scheduler.shutdownNow();
        }
    }
}

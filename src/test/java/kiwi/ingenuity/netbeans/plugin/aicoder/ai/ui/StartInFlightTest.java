package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class StartInFlightTest {

    private final StartInFlight guard = new StartInFlight();
    private final AtomicInteger launches = new AtomicInteger();

    private CompletableFuture<Void> launch(CompletableFuture<Void> result) {
        launches.incrementAndGet();
        return result;
    }

    @Test
    void theFirstRequestLaunchesAStart() {
        CompletableFuture<Void> first = new CompletableFuture<>();

        assertSame(first, guard.start(() -> launch(first)));

        assertEquals(1, launches.get());
        assertTrue(guard.isInFlight());
    }

    @Test
    void aSecondRequestWhileTheFirstIsIncompleteJoinsItInsteadOfLaunchingAnother() {
        CompletableFuture<Void> first = new CompletableFuture<>();
        guard.start(() -> launch(first));

        CompletableFuture<Void> second = guard.start(() -> launch(new CompletableFuture<>()));

        assertSame(first, second, "the caller waits on the start that is already running");
        assertEquals(1, launches.get(), "no second start, so nothing stops the first process");
    }

    @Test
    void onceTheStartHasCompletedTheNextRequestLaunchesAgain() {
        CompletableFuture<Void> first = new CompletableFuture<>();
        guard.start(() -> launch(first));
        first.complete(null);
        assertFalse(guard.isInFlight());

        CompletableFuture<Void> next = new CompletableFuture<>();
        CompletableFuture<Void> second = guard.start(() -> launch(next));

        assertSame(next, second);
        assertNotSame(first, second);
        assertEquals(2, launches.get());
    }

    @Test
    void aFailedStartIsNoLongerInFlight() {
        CompletableFuture<Void> first = new CompletableFuture<>();
        guard.start(() -> launch(first));

        first.completeExceptionally(new IllegalStateException("boom"));

        assertFalse(guard.isInFlight());
        CompletableFuture<Void> replacement = new CompletableFuture<>();
        assertSame(replacement, guard.start(() -> launch(replacement)), "a failed start is replaced, not joined");
        assertEquals(2, launches.get());
    }

    @Test
    void aStartThatCompletesImmediatelyNeverCountsAsInFlight() {
        guard.start(() -> launch(CompletableFuture.completedFuture(null)));

        assertFalse(guard.isInFlight());
        guard.start(() -> launch(CompletableFuture.completedFuture(null)));
        assertEquals(2, launches.get());
    }

    @Test
    void nothingIsInFlightBeforeTheFirstRequest() {
        assertFalse(guard.isInFlight());
    }
}

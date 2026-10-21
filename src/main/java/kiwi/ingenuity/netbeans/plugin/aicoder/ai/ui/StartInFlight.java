package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Lets only one backend start run at a time. A second request while a start is still incomplete gets the running
 * start's future back instead of launching another: a second start would race the first one's history load, and a
 * backend whose {@code start()} stops the previous process would kill the one that is carrying the conversation.
 * Event-dispatch thread only.
 */
final class StartInFlight {

    private CompletableFuture<Void> current;

    /**
     * Returns the start already in flight, or launches a new one with {@code launcher} and remembers it until it
     * completes.
     */
    CompletableFuture<Void> start(Supplier<CompletableFuture<Void>> launcher) {
        if (current != null && !current.isDone()) {
            return current;
        }
        current = launcher.get();
        return current;
    }

    boolean isInFlight() {
        return current != null && !current.isDone();
    }
}

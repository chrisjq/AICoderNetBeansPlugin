package kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-session, non-blocking handle for a backend debug log.
 */
public final class BackendDebugLog {

    final Path file;
    final DebugLogBudget budget;
    final AtomicLong queuedBytes = new AtomicLong();
    final AtomicInteger queuedEntries = new AtomicInteger();
    final AtomicLong dropped = new AtomicLong();
    final CopyOnWriteArrayList<String> secrets = new CopyOnWriteArrayList<>();
    volatile boolean closed;
    volatile boolean failed;
    volatile boolean finished;

    BackendDebugLog(Path file, DebugLogBudget budget) {
        this.file = file;
        this.budget = budget;
    }

    /**
     * Queues a redacted diagnostic line. This method never blocks or throws.
     */
    public void log(DebugDirectionEnum direction, String kind, String payload) {
        BackendDebugLogWriter.log(this, direction, kind, payload);
    }

    /**
     * Registers an additional value that must not appear in this session's log.
     */
    public void registerSecret(String value) {
        if (value != null && !value.isEmpty()) {
            secrets.addIfAbsent(value);
        }
    }

    /**
     * Closes this log after all previously queued entries have been written.
     */
    public CompletableFuture<Void> close() {
        return BackendDebugLogWriter.close(this, false);
    }

    /**
     * Closes this log and removes its current and rolled files.
     */
    public CompletableFuture<Void> closeAndDelete() {
        return BackendDebugLogWriter.close(this, true);
    }

    /**
     * Number of bytes currently charged to the in-memory queue.
     */
    public long queuedBytes() {
        return queuedBytes.get();
    }
}

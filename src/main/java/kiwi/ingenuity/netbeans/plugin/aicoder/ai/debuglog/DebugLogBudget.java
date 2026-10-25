package kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog;

/**
 * Bounded queue and file limits for one backend debug log.
 */
public record DebugLogBudget(long maxQueueBytes, int maxQueueEntries, int maxEntryBytes, long fileCapBytes,
                             int rolledFiles) {

    /**
     * The four-limit form: keeps one rolled file, as it did before {@code rolledFiles} existed.
     */
    public DebugLogBudget(long maxQueueBytes, int maxQueueEntries, int maxEntryBytes, long fileCapBytes) {
        this(maxQueueBytes, maxQueueEntries, maxEntryBytes, fileCapBytes, 1);
    }

    public DebugLogBudget {
        rolledFiles = Math.max(0, rolledFiles);
    }

    public static DebugLogBudget defaults() {
        return new DebugLogBudget(8L << 20, 10_000, 256 << 10, 10L << 20);
    }

    /**
     * A budget from explicit limits, given in the record's own order. A {@code maxEntryBytes} above
     * {@link Integer#MAX_VALUE} is clamped to it; a negative value is treated as zero.
     */
    public static DebugLogBudget of(long maxQueueBytes, int maxQueueEntries, long maxEntryBytes, long fileCapBytes) {
        return of(maxQueueBytes, maxQueueEntries, maxEntryBytes, fileCapBytes, 1);
    }

    /**
     * A budget from explicit limits, including how many rolled files to keep (0 = none, truncate instead).
     */
    public static DebugLogBudget of(long maxQueueBytes, int maxQueueEntries, long maxEntryBytes, long fileCapBytes,
                                    int rolledFiles) {
        int entryBytes = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, maxEntryBytes));
        return new DebugLogBudget(maxQueueBytes, maxQueueEntries, entryBytes, fileCapBytes, rolledFiles);
    }
}

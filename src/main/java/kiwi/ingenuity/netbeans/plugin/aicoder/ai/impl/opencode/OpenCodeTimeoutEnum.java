package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

/**
 * Timeouts specific to the OpenCode implementation.
 */
public enum OpenCodeTimeoutEnum {
    OPENCODE_EXECUTABLE_TEST_MILLIS(10_000L, Kind.OPERATION),
    /**
     * Bound for one {@code session/close} round trip during {@code stop()} — a graceful-shutdown courtesy, not a
     * precondition: the wait runs on a background thread (never the caller), and {@code conn.close()}/
     * {@code proc.destroy()} run unconditionally once it returns or this expires.
     */
    SESSION_CLOSE_WAIT_MILLIS(5_000L, Kind.OPERATION);

    private final long millis;
    private final Kind kind;

    OpenCodeTimeoutEnum(long millis, Kind kind) {
        this.millis = millis;
        this.kind = kind;
    }

    public long millis() {
        return millis;
    }

    private enum Kind {
        OPERATION
    }
}

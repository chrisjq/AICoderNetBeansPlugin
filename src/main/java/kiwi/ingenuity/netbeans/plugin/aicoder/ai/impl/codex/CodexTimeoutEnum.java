package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;

/**
 * Timeouts specific to the Codex implementation.
 */
public enum CodexTimeoutEnum {
    CODEX_EXECUTABLE_TEST_MILLIS(10_000L, Kind.OPERATION),
    /**
     * Must exceed the longest supported MCP tool operation or wait; derives from the shared mutation-lock bound so it
     * rises with that duration.
     */
    MCP_TOOL_TIMEOUT_MILLIS(TimeoutEnum.MUTATION_LOCK_WAIT_MILLIS, Kind.OPERATION),
    /**
     * How long a fileChange approval waits for its {@code item/started} to be drained before giving up on the diff and
     * falling back to the blind confirm.
     *
     * <p>The two arrive on DIFFERENT executors — notifications on a single {@code codex-notify} thread, requests on the
     * {@code codex-dispatch} pool — so nothing orders them, and the approval can and does win. The notify thread also
     * carries every streaming text delta, so the wait is really "how long the notification queue might be backed up",
     * which is normally milliseconds.
     *
     * <p>Ten seconds is deliberately far beyond that: this is not a user-facing wait, the turn is already blocked on
     * the approval either way, and the cost of being too short is a blind Yes/No where a diff was available — the exact
     * defect this feature exists to remove. The cost of being too long is only a delayed fallback in the rare case
     * where the notification never arrives at all.
     */
    FILE_CHANGE_CACHE_WAIT_MILLIS(10_000L, Kind.OPERATION);

    private final long millis;
    private final Kind kind;

    CodexTimeoutEnum(long millis, Kind kind) {
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

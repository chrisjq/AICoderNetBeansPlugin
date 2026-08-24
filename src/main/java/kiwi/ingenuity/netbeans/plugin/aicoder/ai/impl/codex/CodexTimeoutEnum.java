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
    MCP_TOOL_TIMEOUT_MILLIS(TimeoutEnum.MUTATION_LOCK_WAIT_MILLIS, Kind.OPERATION);

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

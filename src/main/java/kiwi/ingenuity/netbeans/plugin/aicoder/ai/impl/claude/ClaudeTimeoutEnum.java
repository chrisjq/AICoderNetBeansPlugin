package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;

/**
 * Timeouts specific to the Claude implementation.
 */
public enum ClaudeTimeoutEnum {
    ANTHROPIC_API_CONNECT_READ_MILLIS(10_000L, Kind.OPERATION),
    CLAUDE_EXECUTABLE_TEST_MILLIS(10_000L, Kind.OPERATION),
    /**
     * Must exceed 60,000 ms or Claude keeps its HTTP MCP per-request limit at 60 seconds; derives from the shared
     * mutation-lock bound so it rises with the longest supported tool operation or wait.
     */
    MCP_TOOL_TIMEOUT_MILLIS(TimeoutEnum.MUTATION_LOCK_WAIT_MILLIS, Kind.OPERATION);

    private final long millis;
    private final Kind kind;

    ClaudeTimeoutEnum(long millis, Kind kind) {
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

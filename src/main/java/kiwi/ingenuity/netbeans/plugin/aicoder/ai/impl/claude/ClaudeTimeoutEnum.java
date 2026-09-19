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
    MCP_TOOL_TIMEOUT_MILLIS(TimeoutEnum.MUTATION_LOCK_WAIT_MILLIS, Kind.OPERATION),
    /**
     * Safety valve for a Mail interrupt HELD because a tool call was in flight when it arrived: if the in-flight count
     * never returns to zero — a lost/malformed tool_result line, or the CLI itself hanging — the interrupt would
     * otherwise wait forever. After this many milliseconds it is delivered anyway, on the reasoning that a stuck
     * session the user cannot even interrupt is worse than the rare case where this fires against a call that was
     * always going to finish a moment later.
     */
    MAIL_INTERRUPT_HOLD_MILLIS(180_000L, Kind.OPERATION);

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

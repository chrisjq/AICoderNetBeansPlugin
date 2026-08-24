package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;

/**
 * Timeouts specific to the GitHub Copilot implementation.
 */
public enum GithubCopilotTimeoutEnum {
    COPILOT_MODEL_DISCOVERY_MILLIS(30_000L, Kind.OPERATION),
    COPILOT_QUOTA_SERVICE_MILLIS(30_000L, Kind.OPERATION),
    COPILOT_EXECUTABLE_TEST_MILLIS(10_000L, Kind.OPERATION),
    /**
     * Must exceed the longest supported MCP tool operation or wait; derives from the shared mutation-lock bound so it
     * rises with that duration.
     */
    MCP_TOOL_TIMEOUT_MILLIS(TimeoutEnum.MUTATION_LOCK_WAIT_MILLIS, Kind.OPERATION);

    private final long millis;
    private final Kind kind;

    GithubCopilotTimeoutEnum(long millis, Kind kind) {
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

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;

/**
 * Timeouts specific to the OpenCode implementation. Values that are NOT OpenCode-specific belong in
 * {@link TimeoutEnum}; where OpenCode needs one of those, it is referenced through a constant here rather than used
 * directly at the call site, so every OpenCode timing is discoverable from this one enum.
 */
public enum OpenCodeTimeoutEnum {
    OPENCODE_EXECUTABLE_TEST_MILLIS(10_000L, Kind.OPERATION),
    /**
     * Bound for one {@code session/close} round trip during {@code stop()} — a graceful-shutdown courtesy, not a
     * precondition: the wait runs on a background thread (never the caller), and {@code conn.close()}/
     * {@code proc.destroy()} run unconditionally once it returns or this expires.
     */
    SESSION_CLOSE_WAIT_MILLIS(5_000L, Kind.OPERATION),
    /**
     * HTTP request timeout for the {@code /doc} capability probe and {@code POST /api/session/…/prompt} steer delivery
     * against the local OpenCode process. Both are single-request round-trips on 127.0.0.1, so five seconds is
     * generous; raise only if telemetry shows real regressions.
     */
    STEER_REQUEST_MILLIS(5_000L, Kind.OPERATION),
    /**
     * Bound for the steer POST itself, deliberately far longer than the probe's.
     *
     * <p>
     * {@code POST /api/session/…/prompt} does not answer while a turn is running — opencode's runner makes the request
     * await the current run's completion, which is minutes, not seconds. The request is dispatched asynchronously and
     * nothing waits on it, so this bound exists only to stop a connection lingering forever if the agent dies mid-turn.
     * It must NOT be short: timing out closes the connection, and a severed connection may discard a steer the agent
     * had already accepted — which is exactly what a 5 s bound appeared to do.
     */
    STEER_DELIVERY_MILLIS(300_000L, Kind.OPERATION),
    /**
     * Per-MCP-request bound handed to OpenCode as {@code experimental.mcp_timeout}. Derived from
     * {@link TimeoutEnum#MUTATION_LOCK_WAIT_MILLIS} rather than given its own number: an MCP tool call may sit behind
     * the plugin's mutation lock, so a bound shorter than that ceiling would abort work that was merely queued and
     * about to run. Every other backend routes the same shared value through its own enum this way — see
     * {@code ClaudeTimeoutEnum}, {@code GrokTimeoutEnum}, {@code CodexTimeoutEnum}, {@code GithubCopilotTimeoutEnum}.
     */
    MCP_TOOL_TIMEOUT_MILLIS(TimeoutEnum.MUTATION_LOCK_WAIT_MILLIS, Kind.OPERATION);

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

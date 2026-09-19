package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

/**
 * Timeouts specific to the pi implementation.
 */
public enum PiTimeoutEnum {
    /**
     * Response timeout for non-prompt RPC commands ({@code steer}, {@code abort}, {@code get_state},
     * {@code get_available_models}, ...). On timeout the session stays usable — an error line is raised and the wait is
     * abandoned rather than treating a slow command as fatal.
     */
    RPC_RESPONSE_TIMEOUT_MILLIS(30_000L, Kind.OPERATION),
    /**
     * Response timeout for {@code compact} specifically — deliberately longer than
     * {@link #RPC_RESPONSE_TIMEOUT_MILLIS}. Unlike the other non-prompt commands that constant covers, compacting a
     * very long transcript could plausibly take pi longer than 30s to ack, and a timeout here is user-visible as
     * "Compact failed" even when pi actually completed the compaction with no suppression run for it — worth a wider
     * margin than the generic housekeeping bound.
     */
    COMPACT_RESPONSE_TIMEOUT_MILLIS(120_000L, Kind.OPERATION),
    /**
     * Runtime bound for {@code pi --version} run by {@code PiExecutableLocator#testExecutable(String)}.
     */
    PI_EXECUTABLE_TEST_MILLIS(10_000L, Kind.OPERATION),
    /**
     * Whole-attempt budget for one {@code pi --list-models} discovery cycle, mirroring
     * {@code GrokTimeoutEnum.GROK_MODEL_DISCOVERY_MILLIS}.
     */
    PI_MODEL_DISCOVERY_MILLIS(15_000L, Kind.OPERATION),
    /**
     * Grace period between closing stdin and destroying the still-alive process on session close. An idle
     * {@code pi --mode rpc} exits immediately on stdin close with code 0 and no shutdown event (verified), so this only
     * bounds the case where the process ignores EOF; the destroy is the fallback.
     *
     */
    CLOSE_GRACE_MILLIS(3_000L, Kind.OPERATION),
    /**
     * Reply timeout for the initial MCP/ToolEvents session handshake, separate from the per-tool-call timeout below.
     */
    MCP_HANDSHAKE_TIMEOUT_MILLIS(30_000L, Kind.OPERATION),
    /**
     * Per tool-call/hook timeout pi's MCP client applies to this extension's tools. Must exceed 60,000 ms or pi keeps
     * whatever per-request cap its MCP client applies; the spec default is 3 hours, comfortably longer than the 2-hour
     * async build ceiling.
     */
    MCP_TOOL_TIMEOUT_MILLIS(10_800_000L, Kind.OPERATION);

    private final long millis;
    private final Kind kind;

    PiTimeoutEnum(long millis, Kind kind) {
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

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;

/**
 * Timeouts specific to the Grok implementation.
 */
public enum GrokTimeoutEnum {
    GROK_MODEL_DISCOVERY_MILLIS(15_000L, Kind.OPERATION),
    GROK_EXECUTABLE_TEST_MILLIS(10_000L, Kind.OPERATION),
    /**
     * Bound for one {@code grok mcp add} subprocess call (writes this plugin's server entry into
     * {@code ~/.grok/config.toml}).
     */
    GROK_CLI_CONFIG_WRITE_MILLIS(5_000L, Kind.OPERATION),
    /**
     * Bound for one {@code grok mcp remove} subprocess call (removes this plugin's server entry from
     * {@code ~/.grok/config.toml}).
     */
    GROK_CLI_CONFIG_REMOVE_MILLIS(3_000L, Kind.OPERATION),
    /**
     * Per-tool-call timeout handed to Grok as {@code mcp_servers.<id>.tool_timeout_sec} (SECONDS — converted at the
     * registration site). Must exceed the longest supported MCP tool operation or wait; derives from the shared
     * mutation-lock bound, so raising any tool duration raises this automatically.
     *
     * <p>
     * Deliberately far SHORTER than Grok's 6000 s (100 min) default. No tool this plugin exposes legitimately runs
     * beyond the shared bound — the longest are AskUserQuestion at 300 s and a build at 180 s — so the tighter value
     * loses no real capability, while a genuinely hung tool call fails within minutes instead of silently blocking the
     * session for an hour and a half. Do NOT "restore" Grok's default here.
     */
    MCP_TOOL_TIMEOUT_MILLIS(TimeoutEnum.MUTATION_LOCK_WAIT_MILLIS, Kind.OPERATION);

    private final long millis;
    private final Kind kind;

    GrokTimeoutEnum(long millis, Kind kind) {
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

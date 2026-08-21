package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

/**
 * JSON object keys in Codex app-server and JSON-RPC wire messages.
 */
public enum CodexJsonKeyEnum {
    /**
     * JSON-RPC protocol version field.
     */
    JSONRPC("jsonrpc"),
    /**
     * JSON-RPC correlation identifier.
     */
    ID("id"),
    /**
     * JSON-RPC method name.
     */
    METHOD("method"),
    /**
     * JSON-RPC request parameters.
     */
    PARAMS("params"),
    /**
     * JSON-RPC response result.
     */
    RESULT("result"),
    /**
     * JSON-RPC error object.
     */
    ERROR("error"),
    /**
     * JSON-RPC error code.
     */
    CODE("code"),
    /**
     * Message or error text.
     */
    MESSAGE("message"),
    /**
     * Initialize client information.
     */
    CLIENT_INFO("clientInfo"),
    /**
     * Client name.
     */
    NAME("name"),
    /**
     * Client display title.
     */
    TITLE("title"),
    /**
     * Client version.
     */
    VERSION("version"),
    /**
     * Client capabilities.
     */
    CAPABILITIES("capabilities"),
    /**
     * Working directory.
     */
    CWD("cwd"),
    /**
     * Sandbox policy.
     */
    SANDBOX("sandbox"),
    /**
     * Approval policy.
     */
    APPROVAL_POLICY("approvalPolicy"),
    /**
     * Model identifier.
     */
    MODEL("model"),
    /**
     * Codex thread identifier.
     */
    THREAD_ID("threadId"),
    /**
     * Codex turn identifier.
     */
    TURN_ID("turnId"),
    /**
     * Active-turn precondition.
     */
    EXPECTED_TURN_ID("expectedTurnId"),
    /**
     * Discriminator.
     */
    TYPE("type"),
    /**
     * Text input content.
     */
    TEXT("text"),
    /**
     * Turn input array.
     */
    INPUT("input"),
    /**
     * Thread response object.
     */
    THREAD("thread"),
    /**
     * Turn response object.
     */
    TURN("turn"),
    /**
     * App-server item object.
     */
    ITEM("item"),
    /**
     * File-change approval item id.
     */
    ITEM_ID("itemId"),
    /**
     * File-change entries.
     */
    CHANGES("changes"),
    /**
     * Turn or item status.
     */
    STATUS("status"),
    /**
     * Codex error discriminator.
     */
    CODEX_ERROR_INFO("codexErrorInfo"),
    /**
     * Changed file path.
     */
    PATH("path"),
    /**
     * Unified diff hunk.
     */
    DIFF("diff"),
    /**
     * Streaming text delta.
     */
    DELTA("delta"),
    /**
     * MCP tool name.
     */
    TOOL("tool"),
    /**
     * Token-usage update.
     */
    TOKEN_USAGE("tokenUsage"),
    /**
     * Model context limit.
     */
    MODEL_CONTEXT_WINDOW("modelContextWindow"),
    /**
     * Latest turn usage.
     */
    LAST("last"),
    /**
     * Token total.
     */
    TOTAL_TOKENS("totalTokens"),
    /**
     * Rate-limit update.
     */
    RATE_LIMITS("rateLimits"),
    /**
     * Primary rate-limit window.
     */
    PRIMARY("primary"),
    /**
     * Rate-limit usage percentage.
     */
    USED_PERCENT("usedPercent"),
    /**
     * Rate-limit window duration.
     */
    WINDOW_DURATION_MINS("windowDurationMins"),
    /**
     * Rate-limit reset timestamp.
     */
    RESETS_AT("resetsAt"),
    /**
     * Approval reason.
     */
    REASON("reason"),
    /**
     * Command awaiting approval.
     */
    COMMAND("command"),
    /**
     * Approval response decision.
     */
    DECISION("decision"),
    /**
     * Elicitation response action.
     */
    ACTION("action"),
    /**
     * MCP server name.
     */
    SERVER_NAME("serverName");

    private final String key;

    CodexJsonKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

/**
 * JSON object key names used when parsing GitHub Copilot CLI / SDK payloads
 * (the {@code copilot --server --stdio} JSON-RPC responses and SDK event
 * bodies). These are GitHub's wire contract — the values must never change.
 */
public enum GithubCopilotJsonKeyEnum {
    // JSON-RPC 2.0 envelope fields (copilot --server --stdio responses)
    ID("id"),
    RESULT("result"),
    // models.list result fields
    MODELS("models"),
    MODEL_ID("id"),
    // account.getQuota result fields
    QUOTA_SNAPSHOTS("quotaSnapshots"),
    PREMIUM_INTERACTIONS("premium_interactions"),
    IS_UNLIMITED_ENTITLEMENT("isUnlimitedEntitlement"),
    USED_REQUESTS("usedRequests"),
    ENTITLEMENT_REQUESTS("entitlementRequests"),
    REMAINING_PERCENTAGE("remainingPercentage"),
    RESET_DATE("resetDate"),
    // session.error payload fields (JSON-encoded error body carried in the event message)
    MESSAGE("message"),
    // Fields of the SDK's extensionData map on a tool-call event. Not gson keys,
    // but the same Copilot-defined names, so they share one source of truth.
    /**
     * Human-readable tool title, preferred over the raw kind in the transcript.
     */
    TOOL_TITLE("toolTitle"),
    /**
     * Name of the MCP server a tool call came from. Used to decide whether a
     * call is ours and may be auto-approved; a spelling mismatch here makes the
     * comparison fail, so our own tools stop being recognised.
     */
    SERVER_NAME("serverName");

    private final String key;

    GithubCopilotJsonKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

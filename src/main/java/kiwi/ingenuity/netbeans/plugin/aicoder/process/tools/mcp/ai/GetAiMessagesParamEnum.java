package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

/**
 * Parameter-name keys for the GetAiMessagesTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GetAiMessagesParamEnum {
    SESSION_ID("sessionId"),
    SECRET_KEY("secretKey");

    private final String key;

    GetAiMessagesParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

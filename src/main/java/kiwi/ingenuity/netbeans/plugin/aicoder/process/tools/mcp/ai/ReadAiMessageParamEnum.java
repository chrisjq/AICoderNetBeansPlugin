package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

/**
 * Parameter-name keys for the ReadAiMessageTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum ReadAiMessageParamEnum {
    SESSION_ID("sessionId"),
    SECRET_KEY("secretKey"),
    MESSAGE_ID("messageId");

    private final String key;

    ReadAiMessageParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

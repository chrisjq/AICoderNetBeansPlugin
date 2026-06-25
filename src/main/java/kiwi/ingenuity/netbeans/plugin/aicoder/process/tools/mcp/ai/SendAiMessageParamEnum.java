package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

/**
 * Parameter-name keys for the SendAiMessageTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum SendAiMessageParamEnum {
    SESSION_ID("sessionId"),
    SECRET_KEY("secretKey"),
    TARGET_SESSION_ID("targetSessionId"),
    SUBJECT("subject"),
    MESSAGE("message"),
    REPLY_TO_MESSAGE_ID("replyToMessageId"),
    IMPORTANT("important"),
    EXPECTS_REPLY("expectsReply"),
    REPLY_IMPORTANT("replyImportant");

    private final String key;

    SendAiMessageParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

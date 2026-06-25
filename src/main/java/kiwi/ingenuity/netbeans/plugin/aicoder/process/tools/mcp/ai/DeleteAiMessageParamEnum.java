package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

public enum DeleteAiMessageParamEnum {
    SESSION_ID("sessionId"),
    SECRET_KEY("secretKey"),
    MESSAGE_ID("messageId"),
    MESSAGE_IDS("messageIds");

    private final String key;

    DeleteAiMessageParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

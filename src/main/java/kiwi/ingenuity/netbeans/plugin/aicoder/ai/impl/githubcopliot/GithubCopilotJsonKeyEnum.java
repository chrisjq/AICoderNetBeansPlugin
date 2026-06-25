package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot;

public enum GithubCopilotJsonKeyEnum {
    TYPE("type"),
    DATA("data"),
    DELTA_CONTENT("deltaContent"),
    SESSION_ID("sessionId"),
    MESSAGE_ID("messageId"),
    USAGE("usage"),
    INPUT_TOKENS("inputTokens"),
    OUTPUT_TOKENS("outputTokens"),
    TOTAL_INPUT_TOKENS("totalInputTokens"),
    TOTAL_OUTPUT_TOKENS("totalOutputTokens"),
    MAX_TOKENS("maxTokens"),
    CURRENT_TOKENS("currentTokens"),
    CURRENT_MODEL("currentModel"),
    MESSAGE("message"),
    ERROR_MESSAGE("errorMessage");

    private final String key;

    GithubCopilotJsonKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

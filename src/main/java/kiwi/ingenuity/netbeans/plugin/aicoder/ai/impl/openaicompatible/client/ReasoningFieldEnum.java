package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

/**
 * Reasoning text fields on a chat-completion delta or message. The first non-empty field in declaration order wins for
 * each chunk.
 */
public enum ReasoningFieldEnum {
    REASONING_CONTENT("reasoning_content"),
    REASONING("reasoning"),
    THINKING("thinking"),
    REASONING_TEXT("reasoning_text");

    private final String key;

    ReasoningFieldEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

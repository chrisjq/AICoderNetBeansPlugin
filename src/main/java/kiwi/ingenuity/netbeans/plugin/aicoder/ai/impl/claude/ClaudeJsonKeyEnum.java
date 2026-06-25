package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

public enum ClaudeJsonKeyEnum {
    // Top-level event fields
    TYPE("type"),
    SUBTYPE("subtype"),
    MESSAGE("message"),
    USAGE("usage"),
    MODEL_USAGE("modelUsage"),
    MODEL("model"),
    // Message / content block fields
    CONTENT("content"),
    TURN_ID("id"),
    ROLE("role"),
    TEXT("text"),
    // Tool-use block fields
    TOOL_NAME("name"),
    INPUT("input"),
    PATH("path"),
    // Write/Edit tool input fields
    WRITE_CONTENT("content"),
    OLD_STRING("old_string"),
    NEW_STRING("new_string"),
    // Usage fields
    INPUT_TOKENS("input_tokens"),
    CACHE_READ_INPUT_TOKENS("cache_read_input_tokens"),
    CACHE_CREATION_INPUT_TOKENS("cache_creation_input_tokens"),
    CONTEXT_WINDOW("contextWindow"),
    // Control message fields (stream-json input, e.g. interrupt control_request)
    REQUEST_ID("request_id"),
    REQUEST("request"),
    // Session ID carried on every stream-json event (the Claude Code session UUID)
    SESSION_ID("session_id"),
    // System event subtypes (used as values, not keys, but kept here for locality)
    ESTIMATED_TOKENS("estimated_tokens");

    private final String key;

    ClaudeJsonKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

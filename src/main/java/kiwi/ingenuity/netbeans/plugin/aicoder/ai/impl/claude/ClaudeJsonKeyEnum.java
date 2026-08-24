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
    // stream_event fields (--include-partial-messages): the raw Messages-API SSE
    // event wrapped one level deep. event.delta.type "input_json_delta" is a
    // tool-input fragment (partial_json); "text_delta" is assistant text — both
    // nest under the same event.delta.type, which is why they must be told apart
    // by value, not by presence of the field.
    EVENT("event"),
    DELTA("delta"),
    // System event fields: estimated_tokens on the thinking_tokens subtype,
    // summary on the task_notification subtype. (The subtype names themselves
    // are VALUES and stay literals in the parser.)
    ESTIMATED_TOKENS("estimated_tokens"),
    SUMMARY("summary"),
    // api_error payload fields (result and system events): the error text may
    // arrive under any of result / error / message, probed in that order
    RESULT("result"),
    ERROR("error"),
    // Claude CLI settings JSON (passed on the command line via --settings)
    AUTO_MEMORY_DIRECTORY("autoMemoryDirectory"),
    // Claude CLI OAuth credentials (~/.claude/.credentials.json, or the macOS Keychain blob)
    CLAUDE_AI_OAUTH("claudeAiOauth"),
    ACCESS_TOKEN("accessToken");

    private final String key;

    ClaudeJsonKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

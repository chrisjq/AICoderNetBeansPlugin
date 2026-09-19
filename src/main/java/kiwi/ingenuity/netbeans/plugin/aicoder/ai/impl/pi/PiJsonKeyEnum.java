package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

/**
 * JSON field-name constants for the {@code pi --mode rpc} wire protocol. Covers the RPC request/response envelope
 * ({@code {type:"command", command, id, ...}} vs {@code {type:"response", command, success, data?, error?}}), the
 * per-event payloads verified live against pi 0.85.1, and the
 * {@code extension_ui_request}/{@code extension_ui_response} pair.
 *
 * <p>
 * Field names follow pi's own serializer (the {@code dist/modes/json-event.js} output verified against 0.85.1). The
 * exact event field paths captured in the spec are used verbatim ({@code assistantMessageEvent.delta}, {@code
 * toolCallId}, {@code willRetry}, {@code contextUsage}, ...).
 */
public enum PiJsonKeyEnum {
    // RPC envelope fields (request lines and response lines)
    TYPE("type"),
    ID("id"),
    COMMAND("command"),
    SUCCESS("success"),
    DATA("data"),
    ERROR("error"),
    MESSAGE("message"),
    // Command parameters (prompt/steer/get_state/...)
    SESSION_ID("sessionId"),
    MODEL("model"),
    PROVIDER("provider"),
    MODEL_ID("modelId"),
    LEVEL("level"),
    // Response payload fields
    CONTEXT_USAGE("contextUsage"),
    MODELS("models"),
    LEVELS("levels"),
    THINKING_LEVEL("thinkingLevel"),
    CONTEXT_WINDOW("contextWindow"),
    TOKENS("tokens"),
    // assistantMessageEvent payload fields (message_update)
    ASSISTANT_MESSAGE_EVENT("assistantMessageEvent"),
    DELTA("delta"),
    // Tool execution event payload fields
    TOOL_CALL_ID("toolCallId"),
    TOOL_NAME("toolName"),
    ARGS("args"),
    PATH("path"),
    RESULT("result"),
    CONTENT("content"),
    TEXT("text"),
    IS_ERROR("isError"),
    STOP_REASON("stopReason"),
    ERROR_MESSAGE("errorMessage"),
    WILL_RETRY("willRetry"),
    ATTEMPT("attempt"),
    MAX_ATTEMPTS("maxAttempts"),
    DELAY_MS("delayMs"),
    // compaction_start / compaction_end payload fields
    REASON("reason"),
    ABORTED("aborted"),
    // extension_ui_request / extension_ui_response fields
    METHOD("method"),
    TITLE("title"),
    CONFIRMED("confirmed"),
    CANCELLED("cancelled");

    private final String key;

    PiJsonKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

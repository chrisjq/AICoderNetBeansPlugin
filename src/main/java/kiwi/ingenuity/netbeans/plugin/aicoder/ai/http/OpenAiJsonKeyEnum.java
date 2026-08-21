package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

/**
 * JSON object key names on the OpenAI-compatible wire: the chat-completion
 * request payload this plugin WRITES, the SSE response it READS back, and the
 * {@code response_format} JSON Schema / structured reply used by
 * {@link SchemaToolCalls}. These are the external contract of every
 * OpenAI-compatible endpoint (Ollama and friends) — the values must not change.
 * <p>
 * Direction matters: this enum is for what goes onto or comes off the OpenAI
 * wire. The plugin's OWN MCP tool definitions (name / description / inputSchema
 * as built by {@code McpToolSchemas}) are read with
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum},
 * never with this enum, even where the spellings coincide — one source of truth
 * per contract.
 */
public enum OpenAiJsonKeyEnum {
    // Chat completion request payload (top level)
    MODEL("model"),
    STREAM("stream"),
    STREAM_OPTIONS("stream_options"),
    INCLUDE_USAGE("include_usage"),
    MESSAGES("messages"),
    TOOLS("tools"),
    RESPONSE_FORMAT("response_format"),
    // Chat message fields
    ROLE("role"),
    CONTENT("content"),
    TOOL_CALL_ID("tool_call_id"),
    TOOL_CALLS("tool_calls"),
    // Tool / function definition and call fields
    TYPE("type"),
    FUNCTION("function"),
    NAME("name"),
    DESCRIPTION("description"),
    PARAMETERS("parameters"),
    ARGUMENTS("arguments"),
    ID("id"),
    INDEX("index"),
    // SSE streaming response fields
    CHOICES("choices"),
    DELTA("delta"),
    FINISH_REASON("finish_reason"),
    USAGE("usage"),
    PROMPT_TOKENS("prompt_tokens"),
    COMPLETION_TOKENS("completion_tokens"),
    // SchemaToolCalls structured reply contract: the response_format JSON Schema
    // this plugin emits, and the reply shape it reads back
    MESSAGE("message"),
    TOOL_NAME("tool_name"),
    TOOL_ARGUMENTS("tool_arguments"),
    // JSON Schema keywords WRITTEN into that emitted response_format schema only.
    // To READ the plugin's own MCP tool definitions use ToolSchemaKeyEnum.
    PROPERTIES("properties"),
    REQUIRED("required"),
    SCHEMA("schema"),
    JSON_SCHEMA("json_schema");

    private final String key;

    OpenAiJsonKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

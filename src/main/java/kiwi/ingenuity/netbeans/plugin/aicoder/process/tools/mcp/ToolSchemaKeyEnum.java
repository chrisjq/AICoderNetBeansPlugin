package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

/**
 * Structural JSON Schema vocabulary keys used when building MCP tool
 * definitions in each tool's {@code schema()} method. Centralises the fixed
 * schema keywords so they are not repeated as string literals across the tool
 * classes.
 * <p>
 * It is also the vocabulary for READING those definitions back: when
 * {@code OpenAiCompatibleClient} / {@code SchemaToolCalls} (ai.http) turn the
 * plugin's tool definitions into an OpenAI {@code tools} payload, they look up null {@link #NAME} / {@link #DESCRIPTION} / {@link #INPUT_SCHEMA} /
 * {@link #PROPERTIES} / {@link #REQUIRED} with these constants. What is then
 * WRITTEN onto the OpenAI wire is {@code OpenAiJsonKeyEnum}'s contract, not
 * this one's — same spellings, two sources of truth kept deliberately apart.
 * <p>
 * This is the JSON Schema vocabulary only. The argument names a tool accepts — null {@link kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum#FILE_PATH},
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum#LINE}
 * and the rest live in
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum},
 * reached through each tool's {@code *ParamEnum}. Keep the two apart:
 * {@link #TYPE} is the schema keyword, not
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum#TYPE}.
 */
public enum ToolSchemaKeyEnum {
    // Tool definition fields
    NAME("name"),
    DESCRIPTION("description"),
    INPUT_SCHEMA("inputSchema"),
    // JSON Schema keywords
    TYPE("type"),
    PROPERTIES("properties"),
    REQUIRED("required"),
    ITEMS("items"),
    DEFAULT("default");

    private final String key;

    ToolSchemaKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

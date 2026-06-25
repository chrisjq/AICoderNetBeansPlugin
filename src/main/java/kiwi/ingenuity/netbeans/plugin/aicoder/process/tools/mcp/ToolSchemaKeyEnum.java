package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

/**
 * Structural JSON Schema vocabulary keys used when building MCP tool
 * definitions in each tool's {@code schema()} method. Centralises the fixed
 * schema keywords so they are not repeated as string literals across the tool
 * classes. Per-tool argument names (e.g. "filePath", "line") are deliberately
 * NOT included here — they are domain-specific to each tool.
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

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help;

/**
 * Parameter-name keys for the GetTypeHierarchyTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GetTypeHierarchyParamEnum {
    CLASS_NAME("className");

    private final String key;

    GetTypeHierarchyParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

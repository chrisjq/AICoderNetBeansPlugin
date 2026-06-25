package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

/**
 * Parameter-name keys for the InlineVariableTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum InlineVariableParamEnum {
    FILE_PATH("filePath"),
    LINE("line");

    private final String key;

    InlineVariableParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

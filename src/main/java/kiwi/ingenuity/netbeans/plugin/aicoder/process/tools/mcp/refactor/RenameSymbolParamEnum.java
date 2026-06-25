package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

/**
 * Parameter-name keys for the RenameSymbolTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum RenameSymbolParamEnum {
    FILE_PATH("filePath"),
    LINE("line"),
    NEW_NAME("newName");

    private final String key;

    RenameSymbolParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

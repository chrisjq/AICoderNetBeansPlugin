package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

/**
 * Parameter-name keys for the FindDeclarationTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum FindDeclarationParamEnum {
    FILE_PATH("filePath"),
    LINE("line"),
    COLUMN("column");

    private final String key;

    FindDeclarationParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

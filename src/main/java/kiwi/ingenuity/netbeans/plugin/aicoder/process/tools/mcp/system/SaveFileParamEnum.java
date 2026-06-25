package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

/**
 * Parameter-name keys for the SaveFileTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum SaveFileParamEnum {
    FILE_PATH("filePath"),
    CONTENT("content");

    private final String key;

    SaveFileParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

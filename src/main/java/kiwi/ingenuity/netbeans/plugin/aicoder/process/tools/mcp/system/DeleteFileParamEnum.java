package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

/**
 * Parameter-name keys for the DeleteFileTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum DeleteFileParamEnum {
    FILE_PATH("filePath");

    private final String key;

    DeleteFileParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

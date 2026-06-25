package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file;

/**
 * Parameter-name keys for the CloseFileTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum CloseFileParamEnum {
    FILE_PATH("filePath");

    private final String key;

    CloseFileParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

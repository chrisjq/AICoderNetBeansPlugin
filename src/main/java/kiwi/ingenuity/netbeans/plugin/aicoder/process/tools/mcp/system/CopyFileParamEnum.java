package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

/**
 * Parameter-name keys for the CopyFileTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum CopyFileParamEnum {
    SOURCE_PATH("sourcePath"),
    TARGET_DIRECTORY("targetDirectory"),
    NEW_NAME("newName");

    private final String key;

    CopyFileParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

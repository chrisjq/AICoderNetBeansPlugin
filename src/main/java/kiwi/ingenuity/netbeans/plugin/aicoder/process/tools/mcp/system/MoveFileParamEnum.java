package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

/**
 * Parameter-name keys for the MoveFileTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum MoveFileParamEnum {
    SOURCE_PATH("sourcePath"),
    TARGET_DIRECTORY("targetDirectory");

    private final String key;

    MoveFileParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

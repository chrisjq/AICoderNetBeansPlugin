package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

/**
 * Parameter-name keys for the MoveClassTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum MoveClassParamEnum {
    FILE_PATH("filePath"),
    LINE("line"),
    TARGET_PACKAGE("targetPackage");

    private final String key;

    MoveClassParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

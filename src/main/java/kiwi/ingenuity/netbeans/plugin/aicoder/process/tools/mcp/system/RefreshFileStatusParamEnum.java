package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

/**
 * Parameter-name keys for the RefreshFileStatusTool MCP tool, shared between
 * its schema() definition and handle() argument extraction so the two cannot
 * drift.
 */
public enum RefreshFileStatusParamEnum {
    FILE_PATH("filePath");

    private final String key;

    RefreshFileStatusParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

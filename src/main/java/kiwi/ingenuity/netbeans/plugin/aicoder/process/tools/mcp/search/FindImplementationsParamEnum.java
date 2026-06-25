package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

/**
 * Parameter-name keys for the FindImplementationsTool MCP tool, shared between
 * its schema() definition and handle() argument extraction so the two cannot
 * drift.
 */
public enum FindImplementationsParamEnum {
    FILE_PATH("filePath"),
    LINE("line");

    private final String key;

    FindImplementationsParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

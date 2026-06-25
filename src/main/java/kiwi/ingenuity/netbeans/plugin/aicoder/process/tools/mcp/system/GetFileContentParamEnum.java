package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

/**
 * Parameter-name keys for the GetFileContentTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GetFileContentParamEnum {
    FILE_PATH("filePath"),
    START_LINE("startLine"),
    END_LINE("endLine");

    private final String key;

    GetFileContentParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

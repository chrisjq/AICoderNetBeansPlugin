package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source;

/**
 * Parameter-name keys for the OrganiseImportsTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum OrganiseImportsParamEnum {
    FILE_PATH("filePath");

    private final String key;

    OrganiseImportsParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

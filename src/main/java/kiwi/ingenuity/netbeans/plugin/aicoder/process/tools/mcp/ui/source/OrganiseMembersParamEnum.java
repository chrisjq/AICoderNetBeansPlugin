package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source;

/**
 * Parameter-name keys for the OrganiseMembersTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum OrganiseMembersParamEnum {
    FILE_PATH("filePath");

    private final String key;

    OrganiseMembersParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

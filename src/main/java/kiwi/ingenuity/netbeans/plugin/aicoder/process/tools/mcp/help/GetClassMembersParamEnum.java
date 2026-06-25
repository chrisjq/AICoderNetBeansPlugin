package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help;

/**
 * Parameter-name keys for the GetClassMembersTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GetClassMembersParamEnum {
    CLASS_NAME("className");

    private final String key;

    GetClassMembersParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

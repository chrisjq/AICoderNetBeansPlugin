package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help;

/**
 * Parameter-name keys for the GetJavadocTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GetJavadocParamEnum {
    CLASS_NAME("className"),
    MEMBER_NAME("memberName");

    private final String key;

    GetJavadocParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

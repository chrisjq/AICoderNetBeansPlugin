package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitCheckoutTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitCheckoutParamEnum {
    BRANCH("branch"),
    CREATE("create");

    private final String key;

    GitCheckoutParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

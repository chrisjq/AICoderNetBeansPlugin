package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitPushTool MCP tool, shared between its schema()
 * definition and handle() argument extraction so the two cannot drift.
 */
public enum GitPushParamEnum {
    REMOTE("remote"),
    BRANCH("branch");

    private final String key;

    GitPushParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

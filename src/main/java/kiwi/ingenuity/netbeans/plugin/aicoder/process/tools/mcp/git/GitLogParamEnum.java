package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitLogTool MCP tool, shared between its schema()
 * definition and handle() argument extraction so the two cannot drift.
 */
public enum GitLogParamEnum {
    LIMIT("limit");

    private final String key;

    GitLogParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

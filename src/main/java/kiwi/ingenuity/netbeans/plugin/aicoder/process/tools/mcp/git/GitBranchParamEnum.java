package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitBranchTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitBranchParamEnum {
    ALL("all"),
    CREATE("create");

    private final String key;

    GitBranchParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

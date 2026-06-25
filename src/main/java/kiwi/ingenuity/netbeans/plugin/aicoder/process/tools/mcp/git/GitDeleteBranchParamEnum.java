package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitDeleteBranchTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitDeleteBranchParamEnum {
    BRANCH("branch"),
    FORCE("force");

    private final String key;

    GitDeleteBranchParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

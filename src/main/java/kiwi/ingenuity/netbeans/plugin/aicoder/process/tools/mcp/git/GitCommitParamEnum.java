package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitCommitTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitCommitParamEnum {
    MESSAGE("message"),
    FILES("files");

    private final String key;

    GitCommitParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitRebaseTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitRebaseParamEnum {
    UPSTREAM("upstream"),
    OPERATION("operation");

    private final String key;

    GitRebaseParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitCherryPickTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitCherryPickParamEnum {
    REVISIONS("revisions"),
    OPERATION("operation");

    private final String key;

    GitCherryPickParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

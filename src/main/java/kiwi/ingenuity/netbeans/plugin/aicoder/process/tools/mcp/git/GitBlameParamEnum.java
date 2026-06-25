package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitBlameTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitBlameParamEnum {
    FILE("file");

    private final String key;

    GitBlameParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

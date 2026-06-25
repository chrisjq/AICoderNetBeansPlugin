package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitAddTool MCP tool, shared between its schema()
 * definition and handle() argument extraction so the two cannot drift.
 */
public enum GitAddParamEnum {
    FILES("files");

    private final String key;

    GitAddParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

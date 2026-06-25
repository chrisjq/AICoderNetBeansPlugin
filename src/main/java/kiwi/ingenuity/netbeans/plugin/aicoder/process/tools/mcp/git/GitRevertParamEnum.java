package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitRevertTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitRevertParamEnum {
    REVISION("revision");

    private final String key;

    GitRevertParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

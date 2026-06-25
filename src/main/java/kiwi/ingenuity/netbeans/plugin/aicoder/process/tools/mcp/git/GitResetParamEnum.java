package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitResetTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitResetParamEnum {
    FILES("files"),
    REVISION("revision"),
    TYPE("type");

    private final String key;

    GitResetParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

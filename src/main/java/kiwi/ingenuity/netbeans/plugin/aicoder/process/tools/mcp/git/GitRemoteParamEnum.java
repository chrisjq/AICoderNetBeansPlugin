package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitRemoteTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitRemoteParamEnum {
    ACTION("action"),
    NAME("name"),
    URL("url");

    private final String key;

    GitRemoteParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

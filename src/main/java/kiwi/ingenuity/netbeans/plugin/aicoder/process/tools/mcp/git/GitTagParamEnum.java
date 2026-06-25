package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitTagTool MCP tool, shared between its schema()
 * definition and handle() argument extraction so the two cannot drift.
 */
public enum GitTagParamEnum {
    ACTION("action"),
    NAME("name"),
    REVISION("revision"),
    MESSAGE("message");

    private final String key;

    GitTagParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GitStashTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitStashParamEnum {
    ACTION("action"),
    INDEX("index"),
    MESSAGE("message"),
    INCLUDE_UNTRACKED("includeUntracked");

    private final String key;

    GitStashParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

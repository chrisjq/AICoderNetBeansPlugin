package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

/**
 * Parameter-name keys for the IsAiSessionActiveTool MCP tool, shared between
 * its schema() definition and handle() argument extraction so the two cannot
 * drift.
 */
public enum IsAiSessionActiveParamEnum {
    TARGET_SESSION_ID("targetSessionId");

    private final String key;

    IsAiSessionActiveParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

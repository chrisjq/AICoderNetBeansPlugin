package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

/**
 * Parameter-name keys for the ListAiSessionsTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum ListAiSessionsParamEnum {
    SESSION_ID("sessionId");

    private final String key;

    ListAiSessionsParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

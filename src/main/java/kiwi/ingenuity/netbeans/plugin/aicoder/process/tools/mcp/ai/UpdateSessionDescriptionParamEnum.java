package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

/**
 * Parameter-name keys for the UpdateSessionDescriptionTool MCP tool, shared
 * between its schema() definition and handle() argument extraction so the two
 * cannot drift.
 */
public enum UpdateSessionDescriptionParamEnum {
    SESSION_ID("sessionId"),
    SECRET_KEY("secretKey"),
    DESCRIPTION("description");

    private final String key;

    UpdateSessionDescriptionParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

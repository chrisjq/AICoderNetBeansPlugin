package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

/**
 * Parameter-name keys for the WebRequestTool MCP tool.
 */
public enum WebRequestParamEnum {
    URL("url"),
    METHOD("method"),
    HEADERS("headers"),
    BODY("body"),
    TIMEOUT_SECONDS("timeoutSeconds"),
    MAX_CHARS("maxChars");

    private final String key;

    WebRequestParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

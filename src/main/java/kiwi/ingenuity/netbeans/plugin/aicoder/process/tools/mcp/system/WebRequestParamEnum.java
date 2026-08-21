package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the WebRequestTool MCP tool.
 */
public enum WebRequestParamEnum {
    URL(McpToolPropertyEnum.URL),
    METHOD(McpToolPropertyEnum.METHOD),
    HEADERS(McpToolPropertyEnum.HEADERS),
    BODY(McpToolPropertyEnum.BODY),
    TIMEOUT_SECONDS(McpToolPropertyEnum.TIMEOUT_SECONDS),
    MAX_CHARS(McpToolPropertyEnum.MAX_CHARS);

    private final McpToolPropertyEnum property;

    WebRequestParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the MarkAiMessageRepliedTool MCP tool.
 */
public enum MarkAiMessageRepliedParamEnum {
    SESSION_ID(McpToolPropertyEnum.SESSION_ID),
    SECRET_KEY(McpToolPropertyEnum.SECRET_KEY),
    MESSAGE_ID(McpToolPropertyEnum.MESSAGE_ID);

    private final McpToolPropertyEnum property;

    MarkAiMessageRepliedParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

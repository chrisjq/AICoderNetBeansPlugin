package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the ReadAiMessageTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum ReadAiMessageParamEnum {
    SESSION_ID(McpToolPropertyEnum.SESSION_ID),
    SECRET_KEY(McpToolPropertyEnum.SECRET_KEY),
    MESSAGE_ID(McpToolPropertyEnum.MESSAGE_ID);

    private final McpToolPropertyEnum property;

    ReadAiMessageParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GetAiMessagesTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GetAiMessagesParamEnum {
    SESSION_ID(McpToolPropertyEnum.SESSION_ID),
    SECRET_KEY(McpToolPropertyEnum.SECRET_KEY);

    private final McpToolPropertyEnum property;

    GetAiMessagesParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the SendAiMessageTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum SendAiMessageParamEnum {
    SESSION_ID(McpToolPropertyEnum.SESSION_ID),
    SECRET_KEY(McpToolPropertyEnum.SECRET_KEY),
    TARGET_SESSION_ID(McpToolPropertyEnum.TARGET_SESSION_ID),
    SUBJECT(McpToolPropertyEnum.SUBJECT),
    MESSAGE(McpToolPropertyEnum.MESSAGE),
    REPLY_TO_MESSAGE_ID(McpToolPropertyEnum.REPLY_TO_MESSAGE_ID),
    IMPORTANT(McpToolPropertyEnum.IMPORTANT),
    EXPECTS_REPLY(McpToolPropertyEnum.EXPECTS_REPLY),
    REPLY_IMPORTANT(McpToolPropertyEnum.REPLY_IMPORTANT);

    private final McpToolPropertyEnum property;

    SendAiMessageParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

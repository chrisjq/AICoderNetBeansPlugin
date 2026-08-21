package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

public enum DeleteAiMessageParamEnum {
    SESSION_ID(McpToolPropertyEnum.SESSION_ID),
    SECRET_KEY(McpToolPropertyEnum.SECRET_KEY),
    MESSAGE_ID(McpToolPropertyEnum.MESSAGE_ID),
    MESSAGE_IDS(McpToolPropertyEnum.MESSAGE_IDS);

    private final McpToolPropertyEnum property;

    DeleteAiMessageParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

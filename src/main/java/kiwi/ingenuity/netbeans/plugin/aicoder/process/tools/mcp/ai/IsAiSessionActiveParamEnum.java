package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the IsAiSessionActiveTool MCP tool, shared between
 * its schema() definition and handle() argument extraction so the two cannot
 * drift.
 */
public enum IsAiSessionActiveParamEnum {
    TARGET_SESSION_ID(McpToolPropertyEnum.TARGET_SESSION_ID);

    private final McpToolPropertyEnum property;

    IsAiSessionActiveParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

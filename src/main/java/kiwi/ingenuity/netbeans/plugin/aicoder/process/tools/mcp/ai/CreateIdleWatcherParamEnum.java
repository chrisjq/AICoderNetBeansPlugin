package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the CreateIdleWatcherTool MCP tool, shared between its schema() definition and handle()
 * argument extraction so the two cannot drift.
 */
public enum CreateIdleWatcherParamEnum {
    TARGET_SESSION_ID(McpToolPropertyEnum.TARGET_SESSION_ID),
    TIMEOUT_MINUTES(McpToolPropertyEnum.TIMEOUT_MINUTES),
    RECURRING(McpToolPropertyEnum.RECURRING),
    INTERRUPT(McpToolPropertyEnum.INTERRUPT),
    NOTE(McpToolPropertyEnum.NOTE);

    private final McpToolPropertyEnum property;

    CreateIdleWatcherParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

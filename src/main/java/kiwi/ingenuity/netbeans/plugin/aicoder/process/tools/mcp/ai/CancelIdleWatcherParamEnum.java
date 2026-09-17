package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the CancelIdleWatcherTool MCP tool, shared between its schema() definition and handle()
 * argument extraction so the two cannot drift.
 */
public enum CancelIdleWatcherParamEnum {
    WATCHER_ID(McpToolPropertyEnum.WATCHER_ID);

    private final McpToolPropertyEnum property;

    CancelIdleWatcherParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

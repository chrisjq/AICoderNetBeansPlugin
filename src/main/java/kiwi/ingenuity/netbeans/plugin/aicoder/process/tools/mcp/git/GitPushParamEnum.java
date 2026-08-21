package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GitPushTool MCP tool, shared between its schema()
 * definition and handle() argument extraction so the two cannot drift.
 */
public enum GitPushParamEnum {
    REMOTE(McpToolPropertyEnum.REMOTE),
    BRANCH(McpToolPropertyEnum.BRANCH);

    private final McpToolPropertyEnum property;

    GitPushParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GitLogTool MCP tool, shared between its schema()
 * definition and handle() argument extraction so the two cannot drift.
 */
public enum GitLogParamEnum {
    LIMIT(McpToolPropertyEnum.LIMIT),
    FILE(McpToolPropertyEnum.FILE),
    FOLLOW(McpToolPropertyEnum.FOLLOW);

    private final McpToolPropertyEnum property;

    GitLogParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

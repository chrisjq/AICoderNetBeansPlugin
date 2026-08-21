package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GitTagTool MCP tool, shared between its schema()
 * definition and handle() argument extraction so the two cannot drift.
 */
public enum GitTagParamEnum {
    ACTION(McpToolPropertyEnum.ACTION),
    NAME(McpToolPropertyEnum.NAME),
    REVISION(McpToolPropertyEnum.REVISION),
    MESSAGE(McpToolPropertyEnum.MESSAGE);

    private final McpToolPropertyEnum property;

    GitTagParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GitStashTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitStashParamEnum {
    ACTION(McpToolPropertyEnum.ACTION),
    INDEX(McpToolPropertyEnum.INDEX),
    MESSAGE(McpToolPropertyEnum.MESSAGE),
    INCLUDE_UNTRACKED(McpToolPropertyEnum.INCLUDE_UNTRACKED);

    private final McpToolPropertyEnum property;

    GitStashParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

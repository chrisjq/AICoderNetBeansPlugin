package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.memory;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the MemorySaveTool MCP tool, shared between its schema() definition and handle() argument
 * extraction so the two cannot drift.
 */
public enum MemorySaveParamEnum {
    NAME(McpToolPropertyEnum.NAME),
    DESCRIPTION(McpToolPropertyEnum.DESCRIPTION),
    CONTENT(McpToolPropertyEnum.CONTENT);

    private final McpToolPropertyEnum property;

    MemorySaveParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

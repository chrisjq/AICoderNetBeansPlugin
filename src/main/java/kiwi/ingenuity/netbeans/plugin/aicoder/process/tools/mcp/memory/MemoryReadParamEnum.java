package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.memory;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the MemoryReadTool MCP tool, shared between its schema() definition and handle() argument
 * extraction so the two cannot drift.
 */
public enum MemoryReadParamEnum {
    NAME(McpToolPropertyEnum.NAME);

    private final McpToolPropertyEnum property;

    MemoryReadParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

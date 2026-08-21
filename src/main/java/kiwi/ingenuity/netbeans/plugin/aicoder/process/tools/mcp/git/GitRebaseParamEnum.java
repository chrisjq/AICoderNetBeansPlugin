package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GitRebaseTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitRebaseParamEnum {
    UPSTREAM(McpToolPropertyEnum.UPSTREAM),
    OPERATION(McpToolPropertyEnum.OPERATION);

    private final McpToolPropertyEnum property;

    GitRebaseParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

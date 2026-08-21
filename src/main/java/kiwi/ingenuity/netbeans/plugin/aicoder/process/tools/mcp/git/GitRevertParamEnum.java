package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GitRevertTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitRevertParamEnum {
    REVISION(McpToolPropertyEnum.REVISION);

    private final McpToolPropertyEnum property;

    GitRevertParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

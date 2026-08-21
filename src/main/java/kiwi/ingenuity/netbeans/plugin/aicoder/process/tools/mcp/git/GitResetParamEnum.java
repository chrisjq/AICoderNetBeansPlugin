package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GitResetTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitResetParamEnum {
    FILES(McpToolPropertyEnum.FILES),
    REVISION(McpToolPropertyEnum.REVISION),
    TYPE(McpToolPropertyEnum.TYPE);

    private final McpToolPropertyEnum property;

    GitResetParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

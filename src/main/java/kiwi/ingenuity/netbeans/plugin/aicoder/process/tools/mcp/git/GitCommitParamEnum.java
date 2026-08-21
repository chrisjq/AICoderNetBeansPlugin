package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GitCommitTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitCommitParamEnum {
    MESSAGE(McpToolPropertyEnum.MESSAGE),
    FILES(McpToolPropertyEnum.FILES);

    private final McpToolPropertyEnum property;

    GitCommitParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GitDeleteBranchTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitDeleteBranchParamEnum {
    BRANCH(McpToolPropertyEnum.BRANCH),
    FORCE(McpToolPropertyEnum.FORCE);

    private final McpToolPropertyEnum property;

    GitDeleteBranchParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

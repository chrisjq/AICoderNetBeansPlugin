package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GitCheckoutTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitCheckoutParamEnum {
    BRANCH(McpToolPropertyEnum.BRANCH),
    CREATE(McpToolPropertyEnum.CREATE);

    private final McpToolPropertyEnum property;

    GitCheckoutParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

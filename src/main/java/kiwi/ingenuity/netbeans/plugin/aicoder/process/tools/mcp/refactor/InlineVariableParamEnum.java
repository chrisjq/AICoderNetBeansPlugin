package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the InlineVariableTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum InlineVariableParamEnum {
    FILE_PATH(McpToolPropertyEnum.FILE_PATH),
    LINE(McpToolPropertyEnum.LINE);

    private final McpToolPropertyEnum property;

    InlineVariableParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

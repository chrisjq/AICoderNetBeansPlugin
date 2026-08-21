package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.navigate;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the NavigateToLineTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum NavigateToLineParamEnum {
    FILE_PATH(McpToolPropertyEnum.FILE_PATH),
    LINE(McpToolPropertyEnum.LINE);

    private final McpToolPropertyEnum property;

    NavigateToLineParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

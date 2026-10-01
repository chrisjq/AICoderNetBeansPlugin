package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GetCurrentFileContentTool MCP tool, shared between its schema() definition and
 * handle() argument extraction so the two cannot drift.
 */
public enum GetCurrentFileContentParamEnum {
    RAW(McpToolPropertyEnum.RAW);

    private final McpToolPropertyEnum property;

    GetCurrentFileContentParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

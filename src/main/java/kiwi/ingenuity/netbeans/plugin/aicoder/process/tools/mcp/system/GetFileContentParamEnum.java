package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GetFileContentTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GetFileContentParamEnum {
    FILE_PATH(McpToolPropertyEnum.FILE_PATH),
    START_LINE(McpToolPropertyEnum.START_LINE),
    END_LINE(McpToolPropertyEnum.END_LINE);

    private final McpToolPropertyEnum property;

    GetFileContentParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

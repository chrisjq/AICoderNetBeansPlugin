package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the SaveFileTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum SaveFileParamEnum {
    FILE_PATH(McpToolPropertyEnum.FILE_PATH),
    CONTENT(McpToolPropertyEnum.CONTENT);

    private final McpToolPropertyEnum property;

    SaveFileParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the CopyFileTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum CopyFileParamEnum {
    SOURCE_PATH(McpToolPropertyEnum.SOURCE_PATH),
    TARGET_DIRECTORY(McpToolPropertyEnum.TARGET_DIRECTORY),
    NEW_NAME(McpToolPropertyEnum.NEW_NAME);

    private final McpToolPropertyEnum property;

    CopyFileParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

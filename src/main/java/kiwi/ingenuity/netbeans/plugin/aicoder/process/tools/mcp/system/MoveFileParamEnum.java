package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the MoveFileTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum MoveFileParamEnum {
    SOURCE_PATH(McpToolPropertyEnum.SOURCE_PATH),
    TARGET_DIRECTORY(McpToolPropertyEnum.TARGET_DIRECTORY);

    private final McpToolPropertyEnum property;

    MoveFileParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

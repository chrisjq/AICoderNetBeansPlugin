package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the MoveClassTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum MoveClassParamEnum {
    FILE_PATH(McpToolPropertyEnum.FILE_PATH),
    LINE(McpToolPropertyEnum.LINE),
    TARGET_PACKAGE(McpToolPropertyEnum.TARGET_PACKAGE);

    private final McpToolPropertyEnum property;

    MoveClassParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

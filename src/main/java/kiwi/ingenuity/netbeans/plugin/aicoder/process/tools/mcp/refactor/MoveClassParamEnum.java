package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the MoveClassTool MCP tool, shared between its schema() definition and handle() argument
 * extraction so the two cannot drift.
 */
public enum MoveClassParamEnum {
    FILE_PATH(McpToolPropertyEnum.FILE_PATH),
    FILE_PATHS(McpToolPropertyEnum.FILE_PATHS),
    LINE(McpToolPropertyEnum.LINE),
    TARGET_PACKAGE(McpToolPropertyEnum.TARGET_PACKAGE),
    COMMIT_WITH_WARNING(McpToolPropertyEnum.COMMIT_WITH_WARNING);

    private final McpToolPropertyEnum property;

    MoveClassParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

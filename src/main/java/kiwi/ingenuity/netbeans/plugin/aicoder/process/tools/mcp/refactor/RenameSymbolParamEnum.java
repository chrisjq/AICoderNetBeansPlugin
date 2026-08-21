package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the RenameSymbolTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum RenameSymbolParamEnum {
    FILE_PATH(McpToolPropertyEnum.FILE_PATH),
    LINE(McpToolPropertyEnum.LINE),
    NEW_NAME(McpToolPropertyEnum.NEW_NAME);

    private final McpToolPropertyEnum property;

    RenameSymbolParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

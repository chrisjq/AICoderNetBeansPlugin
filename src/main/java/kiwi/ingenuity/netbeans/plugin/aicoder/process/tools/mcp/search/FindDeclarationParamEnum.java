package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the FindDeclarationTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum FindDeclarationParamEnum {
    FILE_PATH(McpToolPropertyEnum.FILE_PATH),
    LINE(McpToolPropertyEnum.LINE),
    COLUMN(McpToolPropertyEnum.COLUMN);

    private final McpToolPropertyEnum property;

    FindDeclarationParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

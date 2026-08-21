package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the OrganiseImportsTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum OrganiseImportsParamEnum {
    FILE_PATH(McpToolPropertyEnum.FILE_PATH);

    private final McpToolPropertyEnum property;

    OrganiseImportsParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

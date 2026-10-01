package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the CreateDirectoryTool MCP tool, shared between its schema() definition and
 * handle() argument extraction so the two cannot drift.
 */
public enum CreateDirectoryParamEnum {
    FILE_PATH(McpToolPropertyEnum.FILE_PATH);

    private final McpToolPropertyEnum property;

    CreateDirectoryParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

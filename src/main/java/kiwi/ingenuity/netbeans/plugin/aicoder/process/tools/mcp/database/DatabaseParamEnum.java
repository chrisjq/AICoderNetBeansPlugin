package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.database;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Shared parameter-name keys for the database MCP tools.
 */
public enum DatabaseParamEnum {
    CONNECTION_NAME(McpToolPropertyEnum.CONNECTION_NAME),
    TABLE_NAME(McpToolPropertyEnum.TABLE_NAME),
    LIMIT(McpToolPropertyEnum.LIMIT),
    SQL(McpToolPropertyEnum.SQL);

    private final McpToolPropertyEnum property;

    DatabaseParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}

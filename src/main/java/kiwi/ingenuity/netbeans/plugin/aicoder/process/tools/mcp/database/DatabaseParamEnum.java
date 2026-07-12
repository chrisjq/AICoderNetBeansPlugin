package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.database;

/**
 * Shared parameter-name keys for the database MCP tools.
 */
public enum DatabaseParamEnum {
    CONNECTION_NAME("connectionName"),
    TABLE_NAME("tableName"),
    LIMIT("limit"),
    SQL("sql");

    private final String key;

    DatabaseParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

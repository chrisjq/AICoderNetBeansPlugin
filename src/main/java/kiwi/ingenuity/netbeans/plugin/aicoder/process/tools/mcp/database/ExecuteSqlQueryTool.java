package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.DatabaseProvider;

public class ExecuteSqlQueryTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.DATABASE;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        return McpToolEnum.EXECUTE_SQL_QUERY.toolName() + " -> runs a read-only SELECT query on a registered, connected Database "
                + "Explorer connection. Only SELECT is allowed — enforced twice (text prefix check + "
                + "read-only JDBC connection). Results are capped at the configured row limit; truncated "
                + "results are flagged in the output with a trailing 'row limit reached' note.";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.EXECUTE_SQL_QUERY.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Executes a read-only SELECT query on a registered, connected Database Explorer connection. "
                + "SELECT only. Rows are capped at the session's effective database row limit; truncation is "
                + "flagged in the output. Find the connection via "
                + McpToolEnum.LIST_DATABASE_CONNECTIONS.toolName() + ".");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject connectionName = new JsonObject();
        connectionName.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        connectionName.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Display name of a registered Database Explorer connection (see "
                + McpToolEnum.LIST_DATABASE_CONNECTIONS.toolName() + ").");
        props.add(DatabaseParamEnum.CONNECTION_NAME.key(), connectionName);
        JsonObject sql = new JsonObject();
        sql.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        sql.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The SELECT statement to run.");
        props.add(DatabaseParamEnum.SQL.key(), sql);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(DatabaseParamEnum.CONNECTION_NAME.key());
        required.add(DatabaseParamEnum.SQL.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        DatabaseAccessGuard.requireOption(session, DatabaseAccessOptionEnum.EXECUTE_SQL);
        return DatabaseProvider.executeSqlQuery(
                args.require(DatabaseParamEnum.CONNECTION_NAME.key()),
                args.require(DatabaseParamEnum.SQL.key()),
                session.getSettings().effectiveDatabaseRowLimit());
    }
}

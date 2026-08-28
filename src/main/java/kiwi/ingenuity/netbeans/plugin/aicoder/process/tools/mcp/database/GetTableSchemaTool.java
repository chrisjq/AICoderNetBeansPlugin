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

public class GetTableSchemaTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.DATABASE;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        return McpToolEnum.GET_TABLE_SCHEMA.toolName() + " -> returns column names/types/nullability/primary keys for a table on a "
                + "registered, connected Database Explorer connection.";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GET_TABLE_SCHEMA.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Returns a table's schema (columns, types, nullability, primary keys) on a registered, "
                + "connected Database Explorer connection. Find the connection via "
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
        JsonObject tableName = new JsonObject();
        tableName.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        tableName.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Name of the table to describe.");
        props.add(DatabaseParamEnum.TABLE_NAME.key(), tableName);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(DatabaseParamEnum.CONNECTION_NAME.key());
        required.add(DatabaseParamEnum.TABLE_NAME.key());
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
        DatabaseAccessGuard.requireOption(session, DatabaseAccessOptionEnum.SCHEMA);
        return DatabaseProvider.getTableSchema(
                args.require(DatabaseParamEnum.CONNECTION_NAME.key()),
                args.require(DatabaseParamEnum.TABLE_NAME.key()));
    }
}

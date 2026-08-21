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

public class ListTablesTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.DATABASE;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        return McpToolEnum.LIST_TABLES.toolName() + " -> lists all tables in a database schema accessible through a registered, "
                + "connected Database Explorer connection.";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.LIST_TABLES.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Returns a list of all tables in a database schema on a connection already registered and "
                + "connected in the IDE's Database Explorer. Use this to discover what tables are available, "
                + "then use GetTableSchema or GetTableData to explore them. Use ListDatabaseConnections first "
                + "to find the " + DatabaseParamEnum.CONNECTION_NAME.key() + ".");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject connectionName = new JsonObject();
        connectionName.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        connectionName.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Display name of a registered Database Explorer connection (see ListDatabaseConnections).");
        props.add(DatabaseParamEnum.CONNECTION_NAME.key(), connectionName);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(DatabaseParamEnum.CONNECTION_NAME.key());
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
        DatabaseAccessGuard.requireOption(session, DatabaseAccessOptionEnum.LIST_TABLES);
        return DatabaseProvider.listTables(args.require(DatabaseParamEnum.CONNECTION_NAME.key()));
    }
}

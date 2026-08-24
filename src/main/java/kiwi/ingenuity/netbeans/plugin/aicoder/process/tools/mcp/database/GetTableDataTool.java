package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
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

public class GetTableDataTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.DATABASE;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        return McpToolEnum.GET_TABLE_DATA.toolName() + " -> returns up to the configured row limit of a table's rows (SELECT * ... , "
                + "read-only) on a registered, connected Database Explorer connection. Truncated results "
                + "are flagged in the output with a trailing 'row limit reached' note.";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GET_TABLE_DATA.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Returns up to `limit` rows of a table's data (equivalent to SELECT * FROM <table>) on a "
                + "connection already registered and connected in the IDE's Database Explorer. Read-only: "
                + "enforced via a row cap (see `limit`) and a read-only JDBC connection. If more rows exist "
                + "than were returned, the output ends with a '... (row limit N reached, results may be "
                + "truncated)' note. Use ListDatabaseConnections first to find the " + DatabaseParamEnum.CONNECTION_NAME.key() + ".");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject connectionName = new JsonObject();
        connectionName.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        connectionName.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Display name of a registered Database Explorer connection (see ListDatabaseConnections).");
        props.add(DatabaseParamEnum.CONNECTION_NAME.key(), connectionName);
        JsonObject tableName = new JsonObject();
        tableName.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        tableName.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Name of the table to read.");
        props.add(DatabaseParamEnum.TABLE_NAME.key(), tableName);
        JsonObject limit = new JsonObject();
        limit.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        limit.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Maximum rows to return. Optional — defaults to, and is capped at, this session's "
                + "effective database row limit: a per-session override if one is configured, otherwise "
                + "the plugin default (currently " + PluginSettings.getDatabaseRowLimit() + ").");
        props.add(DatabaseParamEnum.LIMIT.key(), limit);
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
        DatabaseAccessGuard.requireOption(session, DatabaseAccessOptionEnum.SELECT);
        // The configured row limit is the ceiling here, not just the default —
        // otherwise an AI-supplied limit could ask for more rows than the admin
        // configured, making the setting advisory rather than an actual cap.
        int effectiveLimit = session.getSettings().effectiveDatabaseRowLimit();
        int limit = args.intOr(DatabaseParamEnum.LIMIT.key(), effectiveLimit, 1, effectiveLimit);
        return DatabaseProvider.getTableData(
                args.require(DatabaseParamEnum.CONNECTION_NAME.key()),
                args.require(DatabaseParamEnum.TABLE_NAME.key()),
                limit);
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * AUDIT 9 — ExecuteSqlQueryTool. Proves the SQL statement-type restriction fires at the tool boundary (before any
 * connection lookup), that both required parameters are enforced, and that the EXECUTE_SQL access gate really refuses.
 */
class ExecuteSqlQueryToolTest {

    @Test
    void schemaRequiresExactlyConnectionNameAndSql() {
        JsonObject tool = new ExecuteSqlQueryTool().schema(Set.of());
        JsonObject schema = tool.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertEquals(Set.of(DatabaseParamEnum.CONNECTION_NAME.key(), DatabaseParamEnum.SQL.key()),
                props.keySet(), "no parameter beyond connectionName and sql may be advertised");
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        Set<String> requiredNames = new LinkedHashSet<>();
        required.forEach(el -> requiredNames.add(el.getAsString()));
        assertEquals(Set.of(DatabaseParamEnum.CONNECTION_NAME.key(), DatabaseParamEnum.SQL.key()), requiredNames);
    }

    @Test
    void isMutatingIsFalse() {
        assertFalse(new ExecuteSqlQueryTool().isMutating());
    }

    @Test
    void handleRejectsNonSelectStatementBeforeAnyConnectionLookup() throws Exception {
        ExecuteSqlQueryTool tool = new ExecuteSqlQueryTool();
        JsonObject args = new JsonObject();
        args.addProperty(DatabaseParamEnum.CONNECTION_NAME.key(), "any-connection");
        args.addProperty(DatabaseParamEnum.SQL.key(), "DROP TABLE users");
        assertEquals("Rejected: only SELECT queries are allowed.",
                tool.handle(new ToolRequestArguments(args), enabledSession()));
    }

    @Test
    void handleRejectsStatementChainedAfterTheSelect() throws Exception {
        ExecuteSqlQueryTool tool = new ExecuteSqlQueryTool();
        JsonObject args = new JsonObject();
        args.addProperty(DatabaseParamEnum.CONNECTION_NAME.key(), "any-connection");
        args.addProperty(DatabaseParamEnum.SQL.key(), "SELECT 1; DROP TABLE users");
        assertEquals("Rejected: only a single SELECT statement is allowed — "
                + "remove the ';' and anything following it.",
                tool.handle(new ToolRequestArguments(args), enabledSession()));
    }

    @Test
    void handleAcceptsSelectTextAndReachesTheConnectionLookup() throws Exception {
        ExecuteSqlQueryTool tool = new ExecuteSqlQueryTool();
        JsonObject args = new JsonObject();
        args.addProperty(DatabaseParamEnum.CONNECTION_NAME.key(), "nonexistent-connection");
        args.addProperty(DatabaseParamEnum.SQL.key(), "SELECT * FROM users");
        String result = tool.handle(new ToolRequestArguments(args), enabledSession());
        assertTrue(result.startsWith("Database connection not found: nonexistent-connection"), result);
    }

    @Test
    void handleRequiresConnectionName() {
        JsonObject args = new JsonObject();
        args.addProperty(DatabaseParamEnum.SQL.key(), "SELECT 1");
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new ExecuteSqlQueryTool().handle(new ToolRequestArguments(args), enabledSession()));
        assertEquals(-32602, ex.getCode());
        assertEquals(DatabaseParamEnum.CONNECTION_NAME.key() + " is required", ex.getMessage());
    }

    @Test
    void handleRequiresSql() {
        JsonObject args = new JsonObject();
        args.addProperty(DatabaseParamEnum.CONNECTION_NAME.key(), "any-connection");
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new ExecuteSqlQueryTool().handle(new ToolRequestArguments(args), enabledSession()));
        assertEquals(-32602, ex.getCode());
        assertEquals(DatabaseParamEnum.SQL.key() + " is required", ex.getMessage());
    }

    @Test
    void handleRefusesWhenExecuteSqlOptionDisabled() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowDatabaseAccess(true);
        settings.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.EXECUTE_SQL, false);
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new ExecuteSqlQueryTool().handle(new ToolRequestArguments(new JsonObject()),
                        new FakeSession("execute-sql-session", settings)));
        assertEquals(-32602, ex.getCode());
        assertTrue(ex.getMessage().contains("disabled"), ex.getMessage());
    }

    @Test
    void handleRefusesWhenMasterDatabaseAccessDisabled() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowDatabaseAccess(false);
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new ExecuteSqlQueryTool().handle(new ToolRequestArguments(new JsonObject()),
                        new FakeSession("execute-sql-session", settings)));
        assertEquals(-32602, ex.getCode());
        assertTrue(ex.getMessage().contains("disabled"), ex.getMessage());
    }

    private static FakeSession enabledSession() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowDatabaseAccess(true);
        settings.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.EXECUTE_SQL, true);
        settings.setDatabaseRowLimit(50);
        return new FakeSession("execute-sql-session", settings);
    }

    private static final class FakeSession extends AbstractAiSession {

        private final String id;
        private final AiSessionSettings settings;

        FakeSession(String id, AiSessionSettings settings) {
            super(new AiSession(id, "Test", null, AiTypeEnum.CLAUDE, null, settings,
                    Instant.EPOCH, Instant.EPOCH));
            this.id = id;
            this.settings = settings;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return null;
        }

        @Override
        public AiSessionSettings getSettings() {
            return settings;
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }
}

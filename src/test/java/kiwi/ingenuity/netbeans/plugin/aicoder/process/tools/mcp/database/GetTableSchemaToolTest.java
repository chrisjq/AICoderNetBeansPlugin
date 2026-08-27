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
 * AUDIT 9 — GetTableSchemaTool. Proves connectionName and tableName are both required, that the tableName value is
 * actually read (an injection-shaped name is refused by the identifier guard before any connection lookup), and that
 * the SCHEMA access gate refuses when disabled.
 */
class GetTableSchemaToolTest {

    @Test
    void schemaRequiresConnectionNameAndTableName() {
        JsonObject tool = new GetTableSchemaTool().schema(Set.of());
        JsonObject schema = tool.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertEquals(Set.of(DatabaseParamEnum.CONNECTION_NAME.key(), DatabaseParamEnum.TABLE_NAME.key()),
                props.keySet());
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        Set<String> requiredNames = new LinkedHashSet<>();
        required.forEach(el -> requiredNames.add(el.getAsString()));
        assertEquals(Set.of(DatabaseParamEnum.CONNECTION_NAME.key(), DatabaseParamEnum.TABLE_NAME.key()),
                requiredNames);
    }

    @Test
    void isMutatingIsFalse() {
        assertFalse(new GetTableSchemaTool().isMutating());
    }

    @Test
    void invalidTableNameReturnsRejectionFromTheTool() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty(DatabaseParamEnum.CONNECTION_NAME.key(), "any-connection");
        args.addProperty(DatabaseParamEnum.TABLE_NAME.key(), "users; DROP TABLE x");
        assertEquals("Invalid tableName: users; DROP TABLE x",
                new GetTableSchemaTool().handle(new ToolRequestArguments(args), enabledSession()));
    }

    @Test
    void handleAcceptsValidNamesAndReachesTheConnectionLookup() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty(DatabaseParamEnum.CONNECTION_NAME.key(), "nonexistent-connection");
        args.addProperty(DatabaseParamEnum.TABLE_NAME.key(), "users");
        String result = new GetTableSchemaTool().handle(new ToolRequestArguments(args), enabledSession());
        assertTrue(result.startsWith("Database connection not found: nonexistent-connection"), result);
    }

    @Test
    void handleRequiresConnectionName() {
        JsonObject args = new JsonObject();
        args.addProperty(DatabaseParamEnum.TABLE_NAME.key(), "users");
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new GetTableSchemaTool().handle(new ToolRequestArguments(args), enabledSession()));
        assertEquals(-32602, ex.getCode());
        assertEquals(DatabaseParamEnum.CONNECTION_NAME.key() + " is required", ex.getMessage());
    }

    @Test
    void handleRequiresTableName() {
        JsonObject args = new JsonObject();
        args.addProperty(DatabaseParamEnum.CONNECTION_NAME.key(), "any-connection");
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new GetTableSchemaTool().handle(new ToolRequestArguments(args), enabledSession()));
        assertEquals(-32602, ex.getCode());
        assertEquals(DatabaseParamEnum.TABLE_NAME.key() + " is required", ex.getMessage());
    }

    @Test
    void handleRefusesWhenSchemaOptionDisabled() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowDatabaseAccess(true);
        settings.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.SCHEMA, false);
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new GetTableSchemaTool().handle(new ToolRequestArguments(new JsonObject()),
                        new FakeSession("table-schema-session", settings)));
        assertEquals(-32602, ex.getCode());
        assertTrue(ex.getMessage().contains("disabled"), ex.getMessage());
    }

    private static FakeSession enabledSession() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowDatabaseAccess(true);
        settings.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.SCHEMA, true);
        settings.setDatabaseRowLimit(50);
        return new FakeSession("table-schema-session", settings);
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

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
 * AUDIT 9 — GetTableDataTool. Focus points: LIMIT must be read and then CAPPED at the session's effective row limit
 * (the audit's accepted-but-not-applied defect shape), TABLE_NAME must actually select the table, and the SELECT gate
 * must refuse when disabled.
 */
class GetTableDataToolTest {

    @Test
    void schemaAdvertisesOnlyConnectionNameTableNameAndLimit() {
        JsonObject tool = new GetTableDataTool().schema(Set.of());
        JsonObject schema = tool.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertEquals(Set.of(DatabaseParamEnum.CONNECTION_NAME.key(),
                DatabaseParamEnum.TABLE_NAME.key(), DatabaseParamEnum.LIMIT.key()), props.keySet());
        assertEquals("integer", props.getAsJsonObject(DatabaseParamEnum.LIMIT.key())
                .get(ToolSchemaKeyEnum.TYPE.key()).getAsString());
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        Set<String> requiredNames = new LinkedHashSet<>();
        required.forEach(el -> requiredNames.add(el.getAsString()));
        assertEquals(Set.of(DatabaseParamEnum.CONNECTION_NAME.key(), DatabaseParamEnum.TABLE_NAME.key()),
                requiredNames, "limit is optional — the row cap is the effective ceiling either way");
    }

    @Test
    void isMutatingIsFalse() {
        assertFalse(new GetTableDataTool().isMutating());
    }

    @Test
    void limitIsReadAndCappedAtTheEffectiveRowLimit() throws Exception {
        // Mirrors the exact expression in handle(): a session override of 7 rows is the ceiling;
        // anything the AI asks for above it must be clamped down to 7, and requests at or below it pass through.
        AiSessionSettings settings = new AiSessionSettings();
        settings.setDatabaseRowLimit(7);
        FakeSession session = new FakeSession("table-data-session", settings);
        int effectiveLimit = session.getSettings().effectiveDatabaseRowLimit();
        assertEquals(7, new ToolRequestArguments(argsWithLimit(9999))
                .intOr(DatabaseParamEnum.LIMIT.key(), effectiveLimit, 1, effectiveLimit),
                "an over-limit request must be capped at the configured ceiling");
        assertEquals(3, new ToolRequestArguments(argsWithLimit(3))
                .intOr(DatabaseParamEnum.LIMIT.key(), effectiveLimit, 1, effectiveLimit),
                "a request within the ceiling must pass through honoured");
        assertEquals(1, new ToolRequestArguments(argsWithLimit(0))
                .intOr(DatabaseParamEnum.LIMIT.key(), effectiveLimit, 1, effectiveLimit),
                "a limit below the minimum must be raised to 1");
        assertEquals(effectiveLimit, new ToolRequestArguments(new JsonObject())
                .intOr(DatabaseParamEnum.LIMIT.key(), effectiveLimit, 1, effectiveLimit),
                "an omitted limit must default to the effective ceiling");
    }

    @Test
    void tableNameInvalidNameReturnsRejectionFromTheTool() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty(DatabaseParamEnum.CONNECTION_NAME.key(), "any-connection");
        args.addProperty(DatabaseParamEnum.TABLE_NAME.key(), "users; DROP TABLE x");
        assertEquals("Invalid tableName: users; DROP TABLE x",
                new GetTableDataTool().handle(new ToolRequestArguments(args), enabledSession()));
    }

    @Test
    void handleAcceptsValidNamesAndReachesTheConnectionLookup() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty(DatabaseParamEnum.CONNECTION_NAME.key(), "nonexistent-connection");
        args.addProperty(DatabaseParamEnum.TABLE_NAME.key(), "users");
        String result = new GetTableDataTool().handle(new ToolRequestArguments(args), enabledSession());
        assertTrue(result.startsWith("Database connection not found: nonexistent-connection"), result);
    }

    @Test
    void handleRequiresConnectionName() {
        JsonObject args = new JsonObject();
        args.addProperty(DatabaseParamEnum.TABLE_NAME.key(), "users");
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new GetTableDataTool().handle(new ToolRequestArguments(args), enabledSession()));
        assertEquals(-32602, ex.getCode());
        assertEquals(DatabaseParamEnum.CONNECTION_NAME.key() + " is required", ex.getMessage());
    }

    @Test
    void handleRequiresTableName() {
        JsonObject args = new JsonObject();
        args.addProperty(DatabaseParamEnum.CONNECTION_NAME.key(), "any-connection");
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new GetTableDataTool().handle(new ToolRequestArguments(args), enabledSession()));
        assertEquals(-32602, ex.getCode());
        assertEquals(DatabaseParamEnum.TABLE_NAME.key() + " is required", ex.getMessage());
    }

    @Test
    void handleRefusesWhenSelectOptionDisabled() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowDatabaseAccess(true);
        settings.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.SELECT, false);
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new GetTableDataTool().handle(new ToolRequestArguments(new JsonObject()),
                        new FakeSession("table-data-session", settings)));
        assertEquals(-32602, ex.getCode());
        assertTrue(ex.getMessage().contains("disabled"), ex.getMessage());
    }

    private static JsonObject argsWithLimit(int limit) {
        JsonObject args = new JsonObject();
        args.addProperty(DatabaseParamEnum.LIMIT.key(), limit);
        return args;
    }

    private static FakeSession enabledSession() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowDatabaseAccess(true);
        settings.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.SELECT, true);
        settings.setDatabaseRowLimit(50);
        return new FakeSession("table-data-session", settings);
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

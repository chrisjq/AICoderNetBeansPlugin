package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.database;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
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
 * AUDIT 9 — ListDatabaseConnectionsTool. Advertises no parameters; proves the schema stays empty, that the master
 * database-access gate refuses when disabled, and that the enabled path runs the real registry read (which in a plain
 * unit-test environment has nothing registered).
 */
class ListDatabaseConnectionsToolTest {

    @Test
    void schemaAdvertisesNoParameters() {
        JsonObject schema = new ListDatabaseConnectionsTool().schema(Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        assertEquals(0, schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key()).size());
        assertFalse(schema.has(ToolSchemaKeyEnum.REQUIRED.key()));
    }

    @Test
    void isMutatingIsFalse() {
        assertFalse(new ListDatabaseConnectionsTool().isMutating());
    }

    @Test
    void handleReadsTheRegistryWhenAccessIsEnabled() throws Exception {
        // Headless unit test: the NetBeans Database Explorer registers no connections, so listConnections
        // must fall through to its no-registrations message rather than throw.
        String result = new ListDatabaseConnectionsTool()
                .handle(new ToolRequestArguments(new JsonObject()), enabledSession());
        assertTrue(result.startsWith("No database connections registered."), result);
    }

    @Test
    void handleRefusesWhenMasterDatabaseAccessDisabled() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowDatabaseAccess(false);
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new ListDatabaseConnectionsTool().handle(new ToolRequestArguments(new JsonObject()),
                        new FakeSession("list-connections-session", settings)));
        assertEquals(-32602, ex.getCode());
        assertTrue(ex.getMessage().contains("disabled"), ex.getMessage());
    }

    private static FakeSession enabledSession() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowDatabaseAccess(true);
        return new FakeSession("list-connections-session", settings);
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

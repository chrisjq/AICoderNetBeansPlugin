package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Map;
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
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * AUDIT 3/6 — GetClipboardTool advertises NO parameters; the audit point is that the schema stays empty and the one
 * behavioural gate it does have — the clipboard-access setting — actually refuses to read when disabled.
 */
class GetClipboardToolTest {

    private static final String SESSION_ID = "clipboard-session";

    @Test
    void schemaAdvertisesNoParameters() {
        JsonObject schema = new GetClipboardTool().schema(java.util.Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertEquals(0, props.size(), "a clipboard read must not accept any parameters");
        assertFalse(schema.has(ToolSchemaKeyEnum.REQUIRED.key()), "nothing may be required");
    }

    @Test
    void refusesToReadWhenClipboardAccessDisabled() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setEnableClipboardAccess(false);
        GetClipboardTool tool = new GetClipboardTool();

        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> tool.handle(new ToolRequestArguments(new JsonObject()),
                        new FakeSession(SESSION_ID, settings)));
        assertEquals(-32602, ex.getCode(), "a disabled clipboard must be an invalid-argument refusal");
        assertTrue(ex.getMessage().contains("disabled"), ex.getMessage());
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

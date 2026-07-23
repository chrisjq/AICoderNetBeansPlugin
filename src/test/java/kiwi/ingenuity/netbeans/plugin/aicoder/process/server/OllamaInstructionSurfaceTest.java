package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeToolHandlerFactory;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.OllamaToolHandlerFactory;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end guard on everything an OLLAMA_LOCAL session can see.
 *
 * <p>Ollama has no built-in Read/Write/Bash/Grep tools, and reaches the plugin
 * through OllamaMcpBridge which injects sessionId/secretKey server-side. So the
 * assembled instruction text and every tool schema must be free of both
 * built-in-tool directives and credential parameters.
 *
 * <p>The per-tool unit tests check each tool in isolation; this checks the
 * fully assembled surface, which is where a leak actually reaches the model.
 * The schema half of this test is what catches a leak that lives in a tool's
 * schema description rather than its instruction() line.
 */
class OllamaInstructionSurfaceTest {

    private static Map<McpToolEnum, McpToolInterface> ollamaHandlers() {
        return OllamaToolHandlerFactory.build(() -> null, null);
    }

    private static String ollamaInstructions() {
        return McpInstructionRegistry.buildFullInstructions(
                AiTypeEnum.OLLAMA_LOCAL, ollamaHandlers());
    }

    /** Every tool schema Ollama receives, concatenated as JSON. */
    private static String ollamaSchemaJson() {
        StringBuilder sb = new StringBuilder();
        for (McpToolInterface h : ollamaHandlers().values()) {
            sb.append(h.schema(AiTypeEnum.OLLAMA_LOCAL.getMcpOptions())).append('\n');
        }
        return sb.toString();
    }

    /**
     * Declared parameter names for one tool. Checked structurally rather than by
     * scanning the JSON text: "targetSessionId" is a legitimate argument naming
     * the peer to act on, and a substring search for "sessionId" would flag it.
     */
    private static Set<String> paramNames(McpToolInterface handler, AiTypeEnum type) {
        JsonObject input = handler.schema(type.getMcpOptions())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        if (input == null || !input.has(ToolSchemaKeyEnum.PROPERTIES.key())) {
            return Set.of();
        }
        return input.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key()).keySet();
    }

    @Test
    void ollamaInstructionsNameNoBuiltInTools() {
        String text = ollamaInstructions();
        assertFalse(text.contains("INSTEAD OF"),
                "Ollama instructions must not redirect to built-in tools it does not have");
        assertFalse(text.contains("Bash"),
                "Ollama instructions must not reference Bash");
    }

    @Test
    void ollamaInstructionsCarryNoCredentialProse() {
        String text = ollamaInstructions();
        assertFalse(text.contains("sessionId"),
                "credentials are injected by the bridge; Ollama must not be told about them");
        assertFalse(text.contains("secretKey"),
                "credentials are injected by the bridge; Ollama must not be told about them");
    }

    @Test
    void ollamaToolSchemasNameNoBuiltInTools() {
        String json = ollamaSchemaJson();
        assertFalse(json.contains("INSTEAD OF"),
                "a tool description is redirecting Ollama to a built-in tool it does not have");
        assertFalse(json.contains("Bash"),
                "a tool description references Bash");
    }

    @Test
    void ollamaToolSchemasCarryNoCredentialParams() {
        for (Map.Entry<McpToolEnum, McpToolInterface> e : ollamaHandlers().entrySet()) {
            Set<String> params = paramNames(e.getValue(), AiTypeEnum.OLLAMA_LOCAL);
            assertFalse(params.contains("sessionId"),
                    e.getKey() + " must not declare sessionId — the bridge injects it");
            assertFalse(params.contains("secretKey"),
                    e.getKey() + " must not declare secretKey — the bridge injects it");
        }
    }

    /**
     * The inter-AI tools declare caller credentials by hand rather than through
     * applyCredentialsIfRequested, so they need their own check that the CLI
     * path still gets them.
     */
    @Test
    void claudeInterAiToolsStillDeclareCallerCredentials() {
        Map<McpToolEnum, McpToolInterface> handlers
                = ClaudeToolHandlerFactory.build(() -> null, null);
        for (McpToolEnum tool : new McpToolEnum[]{McpToolEnum.SEND_AI_MESSAGE,
            McpToolEnum.GET_AI_MESSAGES, McpToolEnum.READ_AI_MESSAGE,
            McpToolEnum.DELETE_AI_MESSAGE, McpToolEnum.UPDATE_SESSION_DESCRIPTION}) {
            McpToolInterface handler = handlers.get(tool);
            if (handler == null) {
                continue;
            }
            Set<String> params = paramNames(handler, AiTypeEnum.CLAUDE);
            assertTrue(params.contains("sessionId"), tool + " should declare sessionId for CLI callers");
            assertTrue(params.contains("secretKey"), tool + " should declare secretKey for CLI callers");
        }
    }

    /**
     * Control: the same builders must still emit the full text for a CLI type.
     * Without this the assertions above would pass if the text vanished for
     * everyone, or if the handler map were simply empty.
     */
    @Test
    void claudeStillReceivesBuiltInToolDirectivesAndCredentials() {
        Map<McpToolEnum, McpToolInterface> handlers
                = ClaudeToolHandlerFactory.build(() -> null, null);
        assertFalse(handlers.isEmpty(), "handler map must be populated for the control to mean anything");

        String text = McpInstructionRegistry.buildFullInstructions(
                AiTypeEnum.CLAUDE, handlers);
        assertTrue(text.contains("INSTEAD OF"), "Claude should still get built-in-tool redirects");
        assertTrue(text.contains("Bash"), "Claude should still get Bash policy");

        JsonObject schema = handlers.values().iterator().next()
                .schema(AiTypeEnum.CLAUDE.getMcpOptions());
        assertTrue(schema.toString().contains("sessionId"), "Claude schemas should still carry credentials");
    }
}

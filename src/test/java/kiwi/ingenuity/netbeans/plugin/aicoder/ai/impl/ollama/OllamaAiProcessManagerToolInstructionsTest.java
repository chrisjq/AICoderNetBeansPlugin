package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.google.gson.JsonObject;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class OllamaAiProcessManagerToolInstructionsTest {

    @Test
    void nativeModeRetainsMcpInstructionsButOmitsRenderedToolList() {
        String instructions = OllamaAiProcessManager.instructionsWithToolProtocol(
                "MCP instructions", List.of(tool("read_file")), false);

        assertFalse(instructions.isEmpty());
        assertTrue(instructions.contains("MCP instructions"));
        assertFalse(instructions.contains("read_file"));
    }

    @Test
    void schemaModeRetainsMcpInstructionsAndRendersToolList() {
        String instructions = OllamaAiProcessManager.instructionsWithToolProtocol(
                "MCP instructions", List.of(tool("read_file")), true);

        assertFalse(instructions.isEmpty());
        assertTrue(instructions.contains("MCP instructions"));
        assertTrue(instructions.contains("read_file"));
    }

    private static JsonObject tool(String name) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", "Read a file.");
        return tool;
    }
}

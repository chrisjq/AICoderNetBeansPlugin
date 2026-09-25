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

    @Test
    void schemaModeDelimitsToolListBeforeReplyProtocol() {
        String instructions = OllamaAiProcessManager.instructionsWithToolProtocol(
                "MCP instructions", List.of(tool("read_file")), true);

        int endMarker = instructions.indexOf("## End of tool list");
        int replyProtocol = instructions.indexOf("Reply as JSON, one tool call at a time.");
        assertTrue(instructions.contains("## MCP Tool List"), instructions);
        assertTrue(instructions.contains("tool result will come back to you"), instructions);
        assertTrue(instructions.contains("your turn continues"), instructions);
        // Deliberately not "the only way to end your turn": the loop also ends on an empty-JSON reply,
        // the narration bound, unproductive tool rounds and the hard iteration cap. Those are all
        // legitimate, so the prompt must not claim otherwise — but none of them is something the MODEL
        // sends, which is what this wording tells it.
        assertTrue(instructions.contains("EndTurn is how you end your turn"), instructions);
        assertTrue(instructions.contains("leave tool_name empty"), instructions);
        assertTrue(endMarker >= 0, instructions);
        assertTrue(replyProtocol > endMarker, instructions);
    }

    private static JsonObject tool(String name) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", "Read a file.");
        return tool;
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ExtractedToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OllamaAiProcessManagerDuplicatesTest {

    @Test
    void ollamaAiProcessManager_refusesToolWithDuplicateParameters() {
        ExtractedToolCall call = new ExtractedToolCall("SaveFile", "{\"path\": \"/tmp/a\", \"path\": \"/tmp/b\"}",
                                                       Map.of("path", 2));
        String result = McpToolInvoker.duplicateParametersMessage(call.name(), call.duplicateCounts());

        assertTrue(result.contains("Duplicate parameters for SaveFile"), "should mention tool name: " + result);
        assertTrue(result.contains("path"), "should list the duplicate parameter: " + result);
        assertTrue(result.contains("2×"), "should show count: " + result);
        assertTrue(result.contains("Each parameter may be given once"), "should explain the rule: " + result);
    }

    @Test
    void ollamaAiProcessManager_refusesWithMultipleDuplicates() {
        Map<String, Integer> dupes = Map.of("param1", 2, "param2", 3);
        String result = McpToolInvoker.duplicateParametersMessage("TestTool", dupes);

        assertTrue(result.contains("Duplicate parameters for TestTool"), "should list tool name: " + result);
        assertTrue(result.contains("param1"), "should list first duplicate: " + result);
        assertTrue(result.contains("param2"), "should list second duplicate: " + result);
        assertTrue(result.contains("2×"), "should show count for param1: " + result);
        assertTrue(result.contains("3×"), "should show count for param2: " + result);
    }

    @Test
    void extractedToolCall_conveysExtractorDuplicates() {
        ExtractedToolCall call = new ExtractedToolCall("GetFile", "{\"path\": \"/tmp/a\", \"path\": \"/tmp/b\"}",
                                                       Map.of("path", 2));

        assertNotNull(call.duplicateCounts(), "duplicateCounts should not be null");
        assertEquals(2, call.duplicateCounts().get("path"), "should preserve extractor duplicates");
    }

    @Test
    void extractedToolCall_cleanCallHasEmptyDuplicates() {
        ExtractedToolCall call = new ExtractedToolCall("CleanTool", "{\"param\": \"value\"}");

        assertTrue(call.duplicateCounts().isEmpty(), "clean call should have empty duplicates");
    }
}

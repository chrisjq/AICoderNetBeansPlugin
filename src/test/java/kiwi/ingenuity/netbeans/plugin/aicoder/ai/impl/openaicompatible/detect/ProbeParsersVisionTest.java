package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Vision detection: Ollama's {@code /api/show} capabilities list and LM Studio's model type or vision capability.
 */
class ProbeParsersVisionTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    @Timeout(5)
    void ollamaVisionCapabilityMeansVision() {
        assertEquals(Boolean.TRUE,
                     ProbeParsers.ollamaShow(json("{\"capabilities\":[\"completion\",\"vision\"]}")).vision());
        assertEquals(Boolean.FALSE, ProbeParsers.ollamaShow(json("{\"capabilities\":[\"completion\"]}")).vision());
        assertNull(ProbeParsers.ollamaShow(json("{\"parameters\":\"num_ctx 4096\"}")).vision(),
                   "no capabilities list says nothing");
    }

    @Test
    @Timeout(5)
    void lmStudioV0TypeVlmMeansVision() {
        String body = "{\"data\":[{\"id\":\"qwen-vl\",\"type\":\"vlm\",\"state\":\"loaded\",\"max_context_length\":8192},"
                + "{\"id\":\"llama\",\"type\":\"llm\",\"state\":\"loaded\",\"max_context_length\":8192}]}";

        assertEquals(Boolean.TRUE, ProbeParsers.lmStudioV0(json(body), "qwen-vl").vision());
        assertEquals(Boolean.FALSE, ProbeParsers.lmStudioV0(json(body), "llama").vision());
        assertNull(ProbeParsers.lmStudioV0(json(body), "missing").vision());
    }

    @Test
    @Timeout(5)
    void lmStudioV1VisionCapabilityWins() {
        String body = "{\"models\":[{\"key\":\"gemma\",\"type\":\"llm\",\"max_context_length\":8192,"
                + "\"capabilities\":{\"vision\":true,\"trained_for_tool_use\":true}}]}";

        assertEquals(Boolean.TRUE, ProbeParsers.lmStudioV1(json(body), "gemma").vision());
    }

    @Test
    @Timeout(5)
    void capabilitiesCarryVisionAndOlderConstructorsLeaveItUnknown() {
        assertNull(new DetectedFacts.Capabilities(true, false).vision());
        assertNull(DetectedFacts.Capabilities.unknown().vision());
        assertEquals(Boolean.TRUE, new DetectedFacts.Capabilities(true, false).withVision(true).vision());
    }
}

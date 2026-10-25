package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaContextBrokerSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaChatContextBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettingsCreator;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves files written before the P0-pre moves still load after them (spec §11).
 */
class OllamaPreMoveCompatibilityTest {

    private static JsonObject load(String name) throws Exception {
        try (var in = OllamaPreMoveCompatibilityTest.class.getResourceAsStream(
                "/kiwi/ingenuity/netbeans/plugin/aicoder/p0pre/" + name)) {
            assertNotNull(in, name);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    @Timeout(10)
    void contextFileFromBeforeTheMoveRestores() throws Exception {
        JsonObject saved = load("ollama-context.json");
        var broker = new OllamaChatContextBroker("p0pre", OllamaContextBrokerSettings.defaults());
        broker.restoreFromJson(saved);
        JsonObject again = broker.toJson();
        assertEquals(saved.keySet(), again.keySet());
        assertEquals(3, broker.entryCount());
        assertEquals(1.25d, broker.calibrationRatio(), 1e-9);
        assertTrue(broker.snapshot().stream()
                .flatMap(message -> message.toolCalls().stream())
                .anyMatch(call -> call.id().equals("call_p0pre_1")));
    }

    @Test
    @Timeout(10)
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sessionSettingsFileFromBeforeTheMoveLoads() throws Exception {
        JsonObject saved = load("ollama-session-settings.json");
        AiSessionSettingsCreator creator = AiTypeEnum.OLLAMA_LOCAL.getSettingsCreator();
        var settings = (OllamaSessionSettings) creator.create();
        creator.update(settings, saved);
        assertEquals(Integer.valueOf(50), settings.maxHistory());
        assertEquals("http://localhost:11434", settings.baseUrl());
        assertEquals("qwen3:8b", settings.model());
        assertEquals("MESSAGE_COUNT", settings.contextTrimTrigger().name());
        assertEquals("SUMMARISE", settings.contextTrimStrategy().name());
        assertEquals(Integer.valueOf(4321), settings.contextTokenThreshold());
        assertEquals(Integer.valueOf(73), settings.contextTrimTargetPercent());
        assertEquals(Integer.valueOf(17), settings.contextMaxMessages());
        assertEquals(Boolean.FALSE, settings.contextPersistOnClose());
    }
}

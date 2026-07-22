package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class OllamaSessionSettingsTest {

    @Test
    void populateJsonObjectIncludesBaseUrl() {
        OllamaSessionSettings settings = new OllamaSessionSettings(
                null, null, null, null, null, null,
                "qwen2.5-coder:7b", "http://localhost:11434", null, null);
        JsonObject cfg = new JsonObject();

        settings.populateJsonObject(cfg);

        assertEquals("qwen2.5-coder:7b", cfg.get("model").getAsString());
        assertEquals("http://localhost:11434", cfg.get("baseUrl").getAsString());
    }

    @Test
    void additionalInfoIncludesBaseUrlWhenPresent() {
        OllamaSessionSettings settings = new OllamaSessionSettings(
                null, null, null, null, null, null,
                "qwen2.5-coder:7b", "http://localhost:11434", null, null);

        assertTrue(settings.getAdditionalInfo().contains("baseUrl: http://localhost:11434"));
    }
}

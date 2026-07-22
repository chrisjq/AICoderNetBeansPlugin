package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class OllamaSettingsCreatorTest {

    @Test
    void updateReadsBaseUrlFromJson() {
        JsonObject cfg = new JsonObject();
        cfg.addProperty("model", "qwen2.5-coder:14b");
        cfg.addProperty("baseUrl", "http://10.0.0.11:11434");

        OllamaSettingsCreator creator = new OllamaSettingsCreator();
        OllamaSessionSettings settings = creator.create();
        creator.update(settings, cfg);

        assertEquals("qwen2.5-coder:14b", settings.model());
        assertEquals("http://10.0.0.11:11434", settings.baseUrl());
    }

    @Test
    void updateLeavesBaseUrlNullWhenAbsent() {
        OllamaSettingsCreator creator = new OllamaSettingsCreator();
        OllamaSessionSettings settings = creator.create();
        creator.update(settings, new JsonObject());

        assertNull(settings.baseUrl());
    }
}

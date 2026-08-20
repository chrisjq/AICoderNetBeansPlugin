package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import java.time.Instant;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Mirrors CodexAiImplementationTest and OpenCodeAiImplementationTest: a model
 * picked for one session must reach that session's own settings and must not
 * touch the global Tools &gt; Options default, which is owned solely by
 * OllamaAiSettingsTab.
 */
class OllamaAiImplementationTest {

    private static AiSession newSession(String id, OllamaSessionSettings settings) {
        return new AiSession(id, "Test", null, AiTypeEnum.OLLAMA_LOCAL, null, settings, Instant.now(), Instant.now());
    }

    private static OllamaAiImplementation implFor(AiSession session) {
        return new OllamaAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };
    }

    @Test
    void setModel_updatesSessionSettings() {
        OllamaSessionSettings settings = new OllamaSessionSettings();
        OllamaAiImplementation impl = implFor(newSession("ollama-setmodel-1", settings));

        impl.setModel("llama3.3");

        assertEquals("llama3.3", settings.model(), "setModel must update the session settings");
    }

    @Test
    void setModel_doesNotChangeOllamaPluginSettingsGlobalDefault() {
        String globalBefore = OllamaPluginSettings.getModel();
        OllamaSessionSettings settings = new OllamaSessionSettings();
        OllamaAiImplementation impl = implFor(newSession("ollama-setmodel-2", settings));

        impl.setModel("qwen3");

        assertEquals(globalBefore, OllamaPluginSettings.getModel(),
                "setModel must NOT write the global plugin default — picking a model for one "
                + "session would otherwise change Tools > Options for every other session");
    }

    @Test
    void setModel_withNoCurrentSessionDoesNotThrow() {
        String globalBefore = OllamaPluginSettings.getModel();
        OllamaAiImplementation impl = new OllamaAiImplementation(e -> {
        }, null);

        impl.setModel("mistral");

        assertEquals(globalBefore, OllamaPluginSettings.getModel(),
                "no session at all must neither throw nor fall back to writing the global default");
    }
}

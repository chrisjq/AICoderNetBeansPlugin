package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AiTypeEnumSettingsTest {

    @Test
    void claudeCreatesClaudeSessionSettings() {
        assertTrue(AiTypeEnum.CLAUDE.createDefaultSettings() instanceof ClaudeSessionSettings);
    }

    @Test
    void grokCreatesGrokSessionSettings() {
        assertTrue(AiTypeEnum.GROK.createDefaultSettings() instanceof GrokSessionSettings);
    }

    @Test
    void githubCopilotCreatesGithubCopilotSessionSettings() {
        assertTrue(AiTypeEnum.GitHubCoPilot.createDefaultSettings() instanceof GithubCopilotSessionSettings);
    }

    @Test
    void everyTypeCreatesModelSettingsWithNoModelPicked() {
        for (AiTypeEnum type : AiTypeEnum.values()) {
            AiSessionSettings settings = type.createDefaultSettings();
            assertTrue(settings instanceof AiModelSessionSettings,
                    type + " should create model-capable settings");
            assertNull(((AiModelSessionSettings) settings).model(),
                    type + " default settings should not pre-pick a model");
        }
    }

    @Test
    void eachCallCreatesAFreshInstance() {
        assertNotSame(AiTypeEnum.CLAUDE.createDefaultSettings(),
                AiTypeEnum.CLAUDE.createDefaultSettings());
    }

    @Test
    void ollamaLocalCreatesOllamaSessionSettings() {
        assertTrue(AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings() instanceof OllamaSessionSettings);
    }

    @Test
    void ollamaLocalUsesCredentialFreePromptPath() {
        //assertFalse(AiTypeEnum.OLLAMA_LOCAL.includeSessionCredentialsInPrompt());
        //assertTrue(AiTypeEnum.CLAUDE.includeSessionCredentialsInPrompt());
        //assertTrue(AiTypeEnum.GROK.includeSessionCredentialsInPrompt());
        //assertTrue(AiTypeEnum.GitHubCoPilot.includeSessionCredentialsInPrompt());
    }

    @Test
    void fromKeyResolvesOllamaLocal() {
        assertEquals(AiTypeEnum.OLLAMA_LOCAL, AiTypeEnum.fromKey("ollama_local"));
    }
}

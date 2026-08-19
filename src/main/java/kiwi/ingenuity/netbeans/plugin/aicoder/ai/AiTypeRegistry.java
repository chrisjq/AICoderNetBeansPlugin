package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.Arrays;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.Registry;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.CodexAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.GithubCopilotAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.GrokAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.OllamaAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiTypeSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

public final class AiTypeRegistry implements Registry {

    public List<AiTypeSettings> getAll() {
        return Arrays.stream(AiTypeEnum.values())
                .filter(t -> t.isImplemented())
                .map(t -> new AiTypeSettings(
                        t,
                        PluginSettings.isAiEnabled(t)))
                .toList();
    }

    public List<AiTypeSettings> getEnabled() {
        return getAll().stream().filter(AiTypeSettings::enabled).toList();
    }

    public AiTypeSettings getSettings(AiTypeEnum type) {
        return new AiTypeSettings(
                type,
                PluginSettings.isAiEnabled(type));
    }

    public void save(AiTypeSettings settings) {
        PluginSettings.setAiEnabled(settings.type(), settings.enabled());
    }

    public AiImplementation create(AiTypeEnum type, AiProcessEventListener listener, ExecutablePrompter prompter) {
        return switch (type) {
            case CLAUDE ->
                new ClaudeAiImplementation(listener, prompter);
            case GROK ->
                new GrokAiImplementation(listener, prompter);
            case GitHubCoPilot ->
                new GithubCopilotAiImplementation(listener, prompter);
            case OLLAMA_LOCAL ->
                new OllamaAiImplementation(listener, prompter);
            case OPENCODE ->
                new OpenCodeAiImplementation(listener, prompter);
            case CODEX ->
                new CodexAiImplementation(listener, prompter);
            default ->
                throw new AssertionError("Unknown AiTypeEnum: " + type);
        };
    }
}

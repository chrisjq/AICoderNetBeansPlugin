package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.Arrays;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.Registry;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.GithubCopilotAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.GrokAiImplementation;
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

    public AiImplementation create(AiTypeEnum type, AiProcessEventListener listener) {
        return switch (type) {
            case CLAUDE ->
                new ClaudeAiImplementation(listener);
            case GROK ->
                new GrokAiImplementation(listener);
            case GitHubCoPilot ->
                new GithubCopilotAiImplementation(listener);
            default ->
                throw new AssertionError("Unknown AiTypeEnum: " + type);
        };
    }
}

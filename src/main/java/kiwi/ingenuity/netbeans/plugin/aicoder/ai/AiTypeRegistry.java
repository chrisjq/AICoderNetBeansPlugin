package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
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

    private static final Logger LOG = Logger.getLogger(AiTypeRegistry.class.getName());
    private static final Map<AiTypeEnum, AiTypeLifecycle> STARTED_LIFECYCLES = new EnumMap<>(AiTypeEnum.class);

    private static synchronized void startLifecycle(AiTypeEnum type, AiTypeLifecycle lifecycle) {
        if (STARTED_LIFECYCLES.containsKey(type)) {
            return;
        }
        lifecycle.start();
        STARTED_LIFECYCLES.put(type, lifecycle);
    }

    /**
     * Stops type-wide services for types that have actually created a session. Called by the module installer during
     * uninstall.
     */
    public static synchronized void shutdownLifecycles() {
        for (AiTypeLifecycle lifecycle : STARTED_LIFECYCLES.values()) {
            try {
                lifecycle.stop();
            }
            catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "Error stopping AI type lifecycle", ex);
            }
        }
        STARTED_LIFECYCLES.clear();
    }

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
        AiImplementation implementation = switch (type) {
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
        startLifecycle(type, implementation.typeLifecycle());
        return implementation;
    }

}

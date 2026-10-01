package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;

/**
 * Type-wide, live-discovered Copilot reasoning-effort capabilities.
 */
public record GithubCopilotReasoningEffortsEvent(
        Map<String, List<String>> supportedByModel,
        Map<String, String> defaultByModel) implements AiPropertyEvent {

    public GithubCopilotReasoningEffortsEvent {
        supportedByModel = deepCopy(supportedByModel);
        defaultByModel = defaultByModel != null ? Map.copyOf(defaultByModel) : Map.of();
    }

    private static Map<String, List<String>> deepCopy(Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((model, efforts) -> copy.put(model, efforts != null ? List.copyOf(efforts) : List.of()));
        return Map.copyOf(copy);
    }

    public List<String> supportedFor(String model) {
        return model == null ? List.of() : supportedByModel.getOrDefault(model, List.of());
    }

    public String defaultFor(String model) {
        return model == null ? null : defaultByModel.get(model);
    }
}

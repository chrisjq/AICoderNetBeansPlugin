package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import java.util.List;

/**
 * Immutable, type-wide snapshot of models discovered for an AI backend.
 */
public record AvailableModelsEvent(List<String> models) implements AiPropertyEvent {

    public AvailableModelsEvent {
        models = List.copyOf(models);
    }
}

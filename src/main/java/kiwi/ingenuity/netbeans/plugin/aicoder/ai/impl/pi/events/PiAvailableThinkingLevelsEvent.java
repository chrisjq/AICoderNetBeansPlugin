package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * The {@code get_available_thinking_levels} result for the model this session's pi process currently has
 * selected.
 */
public record PiAvailableThinkingLevelsEvent(List<String> levels) implements AiProcessImplEvent {

    public PiAvailableThinkingLevelsEvent {
        levels = List.copyOf(levels);
    }
}

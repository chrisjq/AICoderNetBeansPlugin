package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.events;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;

public record ClaudeModelsEvent(List<String> models) implements AiPropertyEvent {

}

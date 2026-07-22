package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;

public record OllamaModelsEvent(List<String> models) implements AiPropertyEvent {

}

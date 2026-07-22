package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;

public record OllamaCapabilityHintEvent(String message) implements AiPropertyEvent {

}

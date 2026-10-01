package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * The model this session's pi process is actually using, {@code "provider/id"}: reported by {@code get_state}
 * when the process spawns and by a successful {@code set_model}.
 */
public record PiModelChangedEvent(String model) implements AiProcessImplEvent {

}

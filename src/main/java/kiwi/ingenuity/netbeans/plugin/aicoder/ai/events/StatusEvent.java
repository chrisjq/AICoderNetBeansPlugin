package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

public record StatusEvent(StatusEventTypeEnum type, String text) implements AiProcessEvent {

}

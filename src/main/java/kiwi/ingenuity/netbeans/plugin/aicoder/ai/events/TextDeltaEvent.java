package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

public record TextDeltaEvent(String text, String turnId) implements AiProcessEvent {

}

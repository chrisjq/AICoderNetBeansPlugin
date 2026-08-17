package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

public final class SystemNotificationEvent implements AiProcessEvent {

    private final String text;

    public SystemNotificationEvent(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

/**
 * A turn finished. {@code reason} is null for backends that do not report one. {@code message} is the already-formatted
 * chat text, or null when there is nothing to show.
 */
public record TurnCompleteEvent(TurnEndReason reason, String message) implements AiProcessEvent {

    public TurnCompleteEvent() {
        this(null, null);
    }

    public TurnCompleteEvent(TurnEndReason reason) {
        this(reason, null);
    }
}

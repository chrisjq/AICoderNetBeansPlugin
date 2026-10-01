package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

/**
 * A status change reported by a backend.
 *
 * <p>
 * {@code cancellable} is only meaningful for {@link StatusEventTypeEnum#BUSY}: it tells the UI whether the
 * work that has just started can be cancelled, which decides whether the Stop button is shown. The UI never
 * needs to know <em>what</em>
 * the work is. Every other status type ignores it.
 */
public record StatusEvent(StatusEventTypeEnum type, String text, boolean cancellable) implements AiProcessEvent {

    public StatusEvent(StatusEventTypeEnum type, String text) {
        this(type, text, false);
    }

    /**
     * The backend has started work the user must wait for — work the backend runs itself (a compaction, a
     * backend-initiated turn). A turn the user sent needs no BUSY: the UI locked itself when Send was
     * pressed.
     */
    public static StatusEvent busy(String text, boolean cancellable) {
        return new StatusEvent(StatusEventTypeEnum.BUSY, text, cancellable);
    }
}

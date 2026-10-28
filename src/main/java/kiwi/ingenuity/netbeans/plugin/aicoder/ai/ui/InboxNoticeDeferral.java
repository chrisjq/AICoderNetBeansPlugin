package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiInboxMessageEvent;

/**
 * Holds inbox notices that arrive while a backend turn is busy, preserving arrival order until the turn closes.
 */
final class InboxNoticeDeferral {

    private final Consumer<AiInboxMessageEvent> immediateRenderer;
    private final List<AiInboxMessageEvent> pending = new ArrayList<>();

    InboxNoticeDeferral(Consumer<AiInboxMessageEvent> immediateRenderer) {
        this.immediateRenderer = Objects.requireNonNull(immediateRenderer, "immediateRenderer");
    }

    void offer(AiInboxMessageEvent message, boolean busy) {
        Objects.requireNonNull(message, "message");
        if (busy) {
            pending.add(message);
        }
        else {
            flush();
            immediateRenderer.accept(message);
        }
    }

    void flush() {
        if (pending.isEmpty()) {
            return;
        }
        List<AiInboxMessageEvent> notices = new ArrayList<>(pending);
        pending.clear();
        notices.forEach(immediateRenderer);
    }
}

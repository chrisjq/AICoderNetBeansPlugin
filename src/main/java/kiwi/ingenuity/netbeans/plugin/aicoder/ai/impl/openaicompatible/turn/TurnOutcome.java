package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnEndReason;

/**
 * The first terminal result claimed by one turn; later terminal signals are ignored.
 */
public final class TurnOutcome {

    private record Value(TurnEndReason reason, String message) {

    }

    private final AtomicReference<Value> value = new AtomicReference<>();

    public boolean claim(TurnEndReason reason, String message) {
        return value.compareAndSet(null, new Value(reason, message));
    }

    public TurnEndReason reason() {
        Value current = value.get();
        return current == null ? null : current.reason();
    }

    public String message() {
        Value current = value.get();
        return current == null ? null : current.message();
    }
}

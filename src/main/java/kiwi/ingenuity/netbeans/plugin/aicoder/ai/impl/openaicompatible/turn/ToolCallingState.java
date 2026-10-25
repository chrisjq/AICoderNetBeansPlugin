package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import java.util.HashMap;
import java.util.Map;

/**
 * The session's tool-calling decisions (spec §5): the unrescued native-failure count per model, which carries across
 * turns and resets on a working native round, and the sticky switch to schema mode. One instance per session start, so
 * a URL, model, key or type change (which restarts the session) starts afresh. Thread-safe: the turn thread writes it
 * and the manager reads it when the next turn starts.
 */
public final class ToolCallingState {

    private final Map<String, Integer> nativeFailures = new HashMap<>();
    private boolean switchedToSchema;

    public synchronized int nativeFailures(String model) {
        return nativeFailures.getOrDefault(key(model), 0);
    }

    public synchronized void recordNativeFailure(String model) {
        nativeFailures.merge(key(model), 1, Integer::sum);
    }

    /**
     * A native round whose tool calls all parsed: the count starts again.
     */
    public synchronized void recordNativeWorking(String model) {
        nativeFailures.remove(key(model));
    }

    public synchronized boolean switchedToSchema() {
        return switchedToSchema;
    }

    public synchronized void switchToSchema() {
        switchedToSchema = true;
    }

    private static String key(String model) {
        return model == null ? "" : model;
    }
}

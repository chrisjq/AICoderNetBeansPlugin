package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells a session's backend that its memory changed, so the change can be noted in the backend debug log. A memory tool
 * runs on the MCP server's thread and knows only the session id; the backend registers itself here while it runs.
 */
public final class SessionMemoryNotifier {

    /**
     * Receives one change: {@code action} is "save" or "delete".
     */
    @FunctionalInterface
    public interface Listener {

        void changed(String action, String name);
    }

    private static final Map<String, Listener> LISTENERS = new ConcurrentHashMap<>();

    private SessionMemoryNotifier() {
    }

    public static void register(String sessionId, Listener listener) {
        if (sessionId != null && listener != null) {
            LISTENERS.put(sessionId, listener);
        }
    }

    /**
     * Removes {@code listener} only if it is still the one registered, so a stopped backend cannot remove the listener
     * of the one that replaced it.
     */
    public static void unregister(String sessionId, Listener listener) {
        if (sessionId != null) {
            LISTENERS.remove(sessionId, listener);
        }
    }

    public static void notify(String sessionId, String action, String name) {
        Listener listener = sessionId == null ? null : LISTENERS.get(sessionId);
        if (listener != null) {
            try {
                listener.changed(action, name);
            }
            catch (RuntimeException ex) {
                // A logging problem must never fail the tool call.
            }
        }
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the live {@link DetectedSnapshotSource} of a running session by its id, so a settings dialog can show what the
 * session has detected without holding a reference to the backend. A session registers itself when it starts and
 * removes itself when it stops.
 */
public final class DetectedSnapshotSources {

    private static final Map<String, DetectedSnapshotSource> SOURCES = new ConcurrentHashMap<>();

    private DetectedSnapshotSources() {
    }

    public static void register(String sessionId, DetectedSnapshotSource source) {
        if (sessionId != null && source != null) {
            SOURCES.put(sessionId, source);
        }
    }

    /**
     * Removes the session's source, but only if it is still {@code source} (a restarted session may have registered a
     * newer one).
     */
    public static void unregister(String sessionId, DetectedSnapshotSource source) {
        if (sessionId != null && source != null) {
            SOURCES.remove(sessionId, source);
        }
    }

    /**
     * @return the running session's source, or null when the session is not running
     */
    public static DetectedSnapshotSource forSession(String sessionId) {
        return sessionId == null ? null : SOURCES.get(sessionId);
    }
}

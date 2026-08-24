package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kiwi.ingenuity.netbeans.plugin.aicoder.Registry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;

public final class SessionRegistry implements Registry {

    private static final Map<String, AbstractAiSession> SESSIONS = new ConcurrentHashMap<>();

    public static void register(AbstractAiSession session) {
        if (session.getId() == null) {
            throw new IllegalArgumentException("Session id must not be null");
        }
        SESSIONS.put(session.getId(), session);
    }

    public static void unregister(AbstractAiSession session) {
        SESSIONS.remove(session.getId(), session);
    }

    public static void unregister(String sessionId) {
        SESSIONS.remove(sessionId);
    }

    public static void registerAlias(String alias, AbstractAiSession session) {
        if (alias != null && !alias.isBlank()) {
            SESSIONS.put(alias, session);
        }
    }

    public static AbstractAiSession get(String sessionId) {
        return SESSIONS.get(sessionId);
    }

    /**
     * Every currently-registered session, including alias entries (the same session may appear more than once — see
     * {@link #registerAlias}). Backed by the live {@code ConcurrentHashMap}, so this is a thin, allocation-free
     * wrapper, not a defensive copy — safe because {@code ConcurrentHashMap.values()} already tolerates concurrent
     * iteration without a snapshot; {@code unmodifiableCollection} only blocks callers from mutating the registry
     * through the view.
     */
    public static Collection<AbstractAiSession> allSessions() {
        return Collections.unmodifiableCollection(SESSIONS.values());
    }
}

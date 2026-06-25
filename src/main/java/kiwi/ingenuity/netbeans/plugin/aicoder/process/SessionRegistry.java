package kiwi.ingenuity.netbeans.plugin.aicoder.process;

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
}

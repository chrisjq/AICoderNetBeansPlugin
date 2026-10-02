package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import java.util.List;
import java.util.stream.Collectors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;

/**
 * Private helpers shared by the idle-watcher MCP tools in this package.
 */
final class IdleWatcherTools {

    static final String DISABLED_MESSAGE
                        = "Error: Idle AI watcher timers are disabled for this session. Enable 'Allow Idle AI Watcher Timer?' in this session's configuration.";

    private IdleWatcherTools() {
    }

    static String sessionName(String sessionId) {
        AbstractAiSession target = SessionRegistry.get(sessionId);
        return target != null ? target.getAiSession().name() : sessionId;
    }

    /**
     * Resolves {@code targetSessionId} exactly as {@link SendAiMessageTool} does for PeerMessageSend: if it
     * already names a known session, used as is; otherwise treated as a session NAME and resolved via the
     * broker's active-session name index. A unique match resolves silently; an ambiguous one reports an error
     * listing the matches, the same way PeerMessageSend does. No match at all (neither a known id nor any
     * name) is returned unchanged, so the caller's own "not open"/"not found" error fires against exactly
     * what was typed.
     */
    record Resolution(String sessionId, String error) {

    }

    static Resolution resolveTargetSessionId(String targetSessionId) {
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        if (broker.isKnownSession(targetSessionId)) {
            return new Resolution(targetSessionId, null);
        }
        List<AiSession> nameMatches = broker.findActiveSessionsByName(targetSessionId);
        if (nameMatches.size() == 1) {
            return new Resolution(nameMatches.get(0).id(), null);
        }
        if (nameMatches.size() > 1) {
            String matches = nameMatches.stream()
                    .map(s -> s.name() + " (" + s.id() + ")")
                    .collect(Collectors.joining(", "));
            return new Resolution(null, "Error: session name '" + targetSessionId + "' is ambiguous; matching peers: " + matches);
        }
        return new Resolution(targetSessionId, null);
    }
}

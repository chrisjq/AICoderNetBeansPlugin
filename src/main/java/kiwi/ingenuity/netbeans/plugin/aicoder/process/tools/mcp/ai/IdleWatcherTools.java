package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import java.util.List;
import java.util.stream.Collectors;
import kiwi.ingenuity.netbeans.plugin.aicoder.AccessControlLabelEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolAccess;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;

/**
 * Private helpers shared by the idle-watcher MCP tools in this package.
 */
final class IdleWatcherTools {

    private static final String IDLE_WATCHERS_FALLBACK = "The idle watcher timer is disabled for this session. Ask the user to enable '"
            + AccessControlLabelEnum.ALLOW_IDLE_WATCHER_TIMER.displayLabel()
            + "' in this session's settings (MCP Tools → Idle watchers) before retrying.";

    static String disabledMessage(AbstractAiSession session) {
        AiSessionSettings settings = session == null ? null : session.getSettings();
        String reason = McpToolAccess.refusalMessage(settings, McpToolGroupEnum.IDLE_WATCHERS);
        return "Error: " + (reason != null ? reason : IDLE_WATCHERS_FALLBACK);
    }

    private IdleWatcherTools() {
    }

    static String sessionName(String sessionId) {
        AbstractAiSession target = SessionRegistry.get(sessionId);
        return target != null ? target.getAiSession().name() : sessionId;
    }

    /**
     * Resolves {@code targetSessionId} exactly as {@link SendAiMessageTool} does for PeerMessageSend: if it already
     * names a known session, used as is; otherwise treated as a session NAME and resolved via the broker's
     * active-session name index. A unique match resolves silently; an ambiguous one reports an error listing the
     * matches, the same way PeerMessageSend does. No match at all (neither a known id nor any name) is returned
     * unchanged, so the caller's own "not open"/"not found" error fires against exactly what was typed.
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

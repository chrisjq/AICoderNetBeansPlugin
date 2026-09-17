package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

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
}

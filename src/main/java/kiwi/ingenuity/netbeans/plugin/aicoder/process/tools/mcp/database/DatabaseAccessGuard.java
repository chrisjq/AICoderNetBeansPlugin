package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.database;

import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolAccess;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;

/**
 * Shared enforcement for the database MCP tools' access settings — mirrors {@code WebRequestTool}'s inline checks,
 * factored out here since four separate tool classes need the same master + per-option gating.
 */
final class DatabaseAccessGuard {

    private DatabaseAccessGuard() {
    }

    static void requireAccess(AbstractAiSession session) throws McpArgumentException {
        // Worded for the layer that refuses: the MCP group switch, or the config setting beneath it.
        String refusal = McpToolAccess.refusalMessage(session.getSettings(), McpToolGroupEnum.DATABASE);
        if (refusal != null) {
            throw new McpArgumentException(-32602, refusal);
        }
    }

    static void requireOption(AbstractAiSession session, DatabaseAccessOptionEnum option) throws McpArgumentException {
        requireAccess(session);
        if (!session.getSettings().effectiveAllowDatabaseAccessOption(option)) {
            throw new McpArgumentException(-32602,
                                           "'" + option.label().displayLabel() + "' is disabled for this session. "
                                           + "Ask the user to enable it in this session's settings "
                                           + "(MCP Tools → Database) before retrying.");
        }
    }
}

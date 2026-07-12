package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.database;

import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;

/**
 * Shared enforcement for the database MCP tools' access settings — mirrors
 * {@code WebRequestTool}'s inline checks, factored out here since four
 * separate tool classes need the same master + per-option gating.
 */
final class DatabaseAccessGuard {

    private DatabaseAccessGuard() {
    }

    static void requireAccess(AbstractAiSession session) throws McpArgumentException {
        if (!session.getSettings().effectiveAllowDatabaseAccess()) {
            throw new McpArgumentException(-32602,
                    "Database access is disabled for this session. Ask the user to enable "
                    + "'Allow database access' in this session's settings or in the plugin's "
                    + "global settings (Tools > Options > AI Coder Code) before retrying.");
        }
    }

    static void requireOption(AbstractAiSession session, DatabaseAccessOptionEnum option) throws McpArgumentException {
        requireAccess(session);
        if (!session.getSettings().effectiveAllowDatabaseAccessOption(option)) {
            throw new McpArgumentException(-32602,
                    "'" + option.label().displayLabel() + "' is disabled for this session. "
                    + "Ask the user to enable it in this session's settings or in the "
                    + "plugin's global settings (Tools > Options > AI Coder Code) before "
                    + "retrying.");
        }
    }
}

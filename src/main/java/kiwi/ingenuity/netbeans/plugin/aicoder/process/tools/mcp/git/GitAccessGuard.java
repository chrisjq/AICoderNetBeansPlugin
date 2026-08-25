package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

/**
 * Shared enforcement for the git MCP tools' access settings — the git analogue of {@code DatabaseAccessGuard}.
 * <p>
 * Returns a message rather than throwing, matching {@code McpToolInvoker#gitScopeDenialOrNull} immediately beside it at
 * the call site. {@code DatabaseAccessGuard} throws because it is called from inside tool bodies, where throwing is the
 * local convention; here the caller is a dispatcher that already deals in denial strings.
 * <p>
 * Public rather than package-private because the single call site lives in {@code process.server}, not in this package.
 */
public final class GitAccessGuard {

    public static String denialOrNull(McpToolInterface handler, AbstractAiSession session) {
        if (handler == null || handler.section() != McpSectionEnum.GIT) {
            return null;
        }
        // A read-only tool can be invoked without a session (see McpToolInvoker#invoke), so the
        // plugin defaults are the fallback rather than an NPE or a silent allow.
        boolean master = session == null
                ? PluginSettings.isAllowGitAccess()
                : session.getSettings().effectiveAllowGitAccess();
        if (!master) {
            return "Git access is disabled for this session. Ask the user to enable "
                    + "'Allow git access' in this session's settings or in the plugin's "
                    + "global settings (Tools > Options > AI Coder Code) before retrying.";
        }
        GitAccessOptionEnum option = GitAccessOptionEnum.forMutating(handler.isMutating());
        boolean allowed = session == null
                ? PluginSettings.isAllowGitAccessOption(option)
                : session.getSettings().effectiveAllowGitAccessOption(option);
        if (!allowed) {
            return "'" + option.label().displayLabel() + "' is disabled for this session. "
                    + "Ask the user to enable it in this session's settings or in the "
                    + "plugin's global settings (Tools > Options > AI Coder Code) before "
                    + "retrying.";
        }
        return null;
    }

    private GitAccessGuard() {
    }
}

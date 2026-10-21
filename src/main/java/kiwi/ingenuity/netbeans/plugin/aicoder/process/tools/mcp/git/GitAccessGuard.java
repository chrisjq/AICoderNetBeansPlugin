package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolAccess;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
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
        AiSessionSettings settings = session == null ? new AiSessionSettings() : session.getSettings();
        McpToolGroupEnum group = handler.isMutating() ? McpToolGroupEnum.GIT_WRITE : McpToolGroupEnum.GIT_READ;
        // Worded for the layer that refuses: the MCP group switch, or — master off first, then the read/write option —
        // the config setting beneath it.
        return McpToolAccess.refusalMessage(settings, group);
    }

    private GitAccessGuard() {
    }
}

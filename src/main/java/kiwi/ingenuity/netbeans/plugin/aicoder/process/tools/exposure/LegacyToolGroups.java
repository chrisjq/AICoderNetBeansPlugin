package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import java.util.EnumSet;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;

/**
 * Derives a tool-exposure configuration from legacy access toggles when no explicit configuration exists.
 */
public final class LegacyToolGroups {

    private LegacyToolGroups() {
    }

    public static McpToolConfig derive(AiSessionSettings settings) {
        if (settings == null) {
            settings = new AiSessionSettings();
        }
        EnumSet<McpToolGroupEnum> disabled = EnumSet.noneOf(McpToolGroupEnum.class);
        if (!settings.effectiveAllowDatabaseAccess()) {
            disabled.add(McpToolGroupEnum.DATABASE);
        }
        if (!settings.effectiveAllowGitAccess()
                || !settings.effectiveAllowGitAccessOption(GitAccessOptionEnum.READ)) {
            disabled.add(McpToolGroupEnum.GIT_READ);
        }
        if (!settings.effectiveAllowGitAccess()
                || !settings.effectiveAllowGitAccessOption(GitAccessOptionEnum.WRITE)) {
            disabled.add(McpToolGroupEnum.GIT_WRITE);
        }
        if (!settings.effectiveAllowWebRequests()) {
            disabled.add(McpToolGroupEnum.WEB);
        }
        if (!settings.effectiveEnableClipboardAccess()) {
            disabled.add(McpToolGroupEnum.CLIPBOARD);
        }
        return McpToolConfig.withDisabled(disabled);
    }
}

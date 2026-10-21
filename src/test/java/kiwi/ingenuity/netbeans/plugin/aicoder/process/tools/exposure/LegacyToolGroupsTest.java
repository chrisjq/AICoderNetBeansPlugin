package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class LegacyToolGroupsTest {

    @Test
    void sessionOverridesWinOverGlobalSettings() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowGitAccess(true);
        settings.setAllowGitAccessOption(GitAccessOptionEnum.READ, true);
        settings.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, false);
        settings.setAllowDatabaseAccess(true);

        McpToolConfig config = LegacyToolGroups.derive(settings);

        assertFalse(config.isDisabled(McpToolGroupEnum.GIT_READ));
        assertTrue(config.isDisabled(McpToolGroupEnum.GIT_WRITE));
        assertFalse(config.isDisabled(McpToolGroupEnum.DATABASE));
    }

    @Test
    void nullSessionValuesFollowGlobalSettings() {
        McpToolConfig config = LegacyToolGroups.derive(new AiSessionSettings());

        assertEquals(!PluginSettings.isAllowDatabaseAccess(), config.isDisabled(McpToolGroupEnum.DATABASE));
        assertEquals(!PluginSettings.isAllowGitAccess()
                || !PluginSettings.isAllowGitAccessOption(GitAccessOptionEnum.READ),
                     config.isDisabled(McpToolGroupEnum.GIT_READ));
        assertEquals(!PluginSettings.isAllowGitAccess()
                || !PluginSettings.isAllowGitAccessOption(GitAccessOptionEnum.WRITE),
                     config.isDisabled(McpToolGroupEnum.GIT_WRITE));
        assertEquals(!PluginSettings.isAllowWebRequests(), config.isDisabled(McpToolGroupEnum.WEB));
        assertEquals(!PluginSettings.isEnableClipboardAccess(), config.isDisabled(McpToolGroupEnum.CLIPBOARD));
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpToolFirstStartConfigTest {

    @Test
    void initializesLegacyConfigUsingSessionOverrides() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowGitAccess(true);
        settings.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, false);

        assertTrue(McpToolFirstStartConfig.initialize(settings));
        assertTrue(settings.mcpToolConfig().isDisabled(McpToolGroupEnum.GIT_WRITE));
    }

    @Test
    void unsetValuesUseGlobalDefaults() {
        AiSessionSettings settings = new AiSessionSettings();

        McpToolFirstStartConfig.initialize(settings);

        assertEquals(!PluginSettings.isAllowWebRequests(),
                     settings.mcpToolConfig().isDisabled(McpToolGroupEnum.WEB));
    }

    /**
     * Every legacy toggle set explicitly on the session, so the result never depends on the global defaults.
     */
    private static AiSessionSettings everythingOn() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowDatabaseAccess(true);
        settings.setAllowGitAccess(true);
        settings.setAllowGitAccessOption(GitAccessOptionEnum.READ, true);
        settings.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, true);
        settings.setAllowWebRequests(true);
        settings.setEnableClipboardAccess(true);
        return settings;
    }

    private static Set<McpToolGroupEnum> disabledAfterFirstStart(AiSessionSettings settings) {
        McpToolFirstStartConfig.initialize(settings);
        return settings.mcpToolConfig().disabledGroups();
    }

    @Test
    void everyLegacyToggleOnDisablesNothing() {
        assertEquals(Set.of(), disabledAfterFirstStart(everythingOn()));
    }

    @Test
    void theDatabaseToggleDisablesOnlyTheDatabaseGroup() {
        AiSessionSettings settings = everythingOn();
        settings.setAllowDatabaseAccess(false);

        assertEquals(Set.of(McpToolGroupEnum.DATABASE), disabledAfterFirstStart(settings));
    }

    @Test
    void theClipboardToggleDisablesOnlyTheClipboardGroup() {
        AiSessionSettings settings = everythingOn();
        settings.setEnableClipboardAccess(false);

        assertEquals(Set.of(McpToolGroupEnum.CLIPBOARD), disabledAfterFirstStart(settings));
    }

    @Test
    void theWebToggleDisablesOnlyTheWebGroup() {
        AiSessionSettings settings = everythingOn();
        settings.setAllowWebRequests(false);

        assertEquals(Set.of(McpToolGroupEnum.WEB), disabledAfterFirstStart(settings));
    }

    @Test
    void theGitReadOptionDisablesOnlyGitRead() {
        AiSessionSettings settings = everythingOn();
        settings.setAllowGitAccessOption(GitAccessOptionEnum.READ, false);

        assertEquals(Set.of(McpToolGroupEnum.GIT_READ), disabledAfterFirstStart(settings));
    }

    @Test
    void theGitWriteOptionDisablesOnlyGitWrite() {
        AiSessionSettings settings = everythingOn();
        settings.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, false);

        assertEquals(Set.of(McpToolGroupEnum.GIT_WRITE), disabledAfterFirstStart(settings));
    }

    @Test
    void theGitMasterToggleDisablesBothGitGroupsAndNothingElse() {
        AiSessionSettings settings = everythingOn();
        settings.setAllowGitAccess(false);

        assertEquals(Set.of(McpToolGroupEnum.GIT_READ, McpToolGroupEnum.GIT_WRITE), disabledAfterFirstStart(settings));
    }

    @Test
    void existingConfigIsPreserved() {
        AiSessionSettings settings = new AiSessionSettings();
        McpToolConfig existing = McpToolConfig.withDisabled(
                java.util.Set.of(McpToolGroupEnum.DATABASE));
        settings.setMcpToolConfig(existing);

        assertFalse(McpToolFirstStartConfig.initialize(settings));
        assertEquals(existing, settings.mcpToolConfig());
    }
}

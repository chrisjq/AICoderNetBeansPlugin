package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpToolSelectionModelTest {

    @Test
    void alwaysOnGroupIsAlwaysEnabledAndNeverEditable() {
        McpToolSelectionModel model = new McpToolSelectionModel(McpToolConfig.ALL_ENABLED);

        assertTrue(model.isGroupEnabled(McpToolGroupEnum.ALWAYS_ON));
        assertFalse(model.isGroupEditable(McpToolGroupEnum.ALWAYS_ON));

        model.setGroupEnabled(McpToolGroupEnum.ALWAYS_ON, false);

        assertTrue(model.isGroupEnabled(McpToolGroupEnum.ALWAYS_ON),
                   "always-on tools must never be switched off");
    }

    @Test
    void buildQueueIsDerivedAndNotIndividuallyEditable() {
        McpToolSelectionModel model = new McpToolSelectionModel(McpToolConfig.ALL_ENABLED);

        assertFalse(model.isGroupEditable(McpToolGroupEnum.BUILD_QUEUE));
        assertTrue(model.isGroupEnabled(McpToolGroupEnum.BUILD_QUEUE),
                   "with every build system on, the build queue is on");

        model.setGroupEnabled(McpToolGroupEnum.BUILD_QUEUE, false);
        assertTrue(model.isGroupEnabled(McpToolGroupEnum.BUILD_QUEUE),
                   "the build queue cannot be switched off on its own");

        model.setGroupEnabled(McpToolGroupEnum.MAVEN, false);
        assertTrue(model.isGroupEnabled(McpToolGroupEnum.BUILD_QUEUE),
                   "one build system remaining keeps the queue on");

        model.setGroupEnabled(McpToolGroupEnum.GRADLE, false);
        model.setGroupEnabled(McpToolGroupEnum.ANT, false);
        model.setGroupEnabled(McpToolGroupEnum.GENERIC_PROJECT, false);
        assertFalse(model.isGroupEnabled(McpToolGroupEnum.BUILD_QUEUE),
                    "the build queue follows the build systems off");
    }

    @Test
    void switchableGroupTogglesTheUnderlyingConfig() {
        McpToolSelectionModel model = new McpToolSelectionModel(McpToolConfig.ALL_ENABLED);

        assertTrue(model.isGroupEditable(McpToolGroupEnum.WEB));
        assertTrue(model.isGroupEnabled(McpToolGroupEnum.WEB));
        assertFalse(model.config().isDisabled(McpToolGroupEnum.WEB));

        model.setGroupEnabled(McpToolGroupEnum.WEB, false);

        assertFalse(model.isGroupEnabled(McpToolGroupEnum.WEB));
        assertTrue(model.config().isDisabled(McpToolGroupEnum.WEB));

        model.setGroupEnabled(McpToolGroupEnum.WEB, true);

        assertTrue(model.isGroupEnabled(McpToolGroupEnum.WEB));
        assertFalse(model.config().isDisabled(McpToolGroupEnum.WEB));
    }

    @Test
    void forSettingsPrefersAnExplicitConfigOverLegacyToggles() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowWebRequests(false);
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);

        McpToolSelectionModel model = McpToolSelectionModel.forSettings(settings);

        assertTrue(model.isGroupEnabled(McpToolGroupEnum.WEB),
                   "an explicit config must win over the legacy web toggle");
    }

    @Test
    void forSettingsFallsBackToTheLegacyTogglesWhenNoConfigIsSet() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowWebRequests(false);
        settings.setAllowDatabaseAccess(false);
        settings.setEnableClipboardAccess(false);
        settings.setAllowGitAccess(false);

        McpToolSelectionModel model = McpToolSelectionModel.forSettings(settings);

        assertFalse(model.isGroupEnabled(McpToolGroupEnum.WEB));
        assertFalse(model.isGroupEnabled(McpToolGroupEnum.DATABASE));
        assertFalse(model.isGroupEnabled(McpToolGroupEnum.CLIPBOARD));
        assertFalse(model.isGroupEnabled(McpToolGroupEnum.GIT_READ));
        assertFalse(model.isGroupEnabled(McpToolGroupEnum.GIT_WRITE));
        assertTrue(model.isGroupEnabled(McpToolGroupEnum.FILE_READING),
                   "groups without a legacy toggle stay enabled");
    }

    @Test
    void configSettingsVisibilityFollowsTheOwningGroup() {
        McpToolSelectionModel model = new McpToolSelectionModel(McpToolConfig.ALL_ENABLED);

        assertTrue(model.areConfigSettingsVisibleFor(McpToolGroupEnum.DATABASE));

        model.setGroupEnabled(McpToolGroupEnum.DATABASE, false);

        assertFalse(model.areConfigSettingsVisibleFor(McpToolGroupEnum.DATABASE));
    }

    @Test
    void applyConfigReplacesTheSelection() {
        McpToolSelectionModel model = new McpToolSelectionModel(McpToolConfig.ALL_ENABLED);

        model.applyConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.WEB)));

        assertFalse(model.isGroupEnabled(McpToolGroupEnum.WEB));
        assertTrue(model.isGroupEnabled(McpToolGroupEnum.DATABASE));
    }

}

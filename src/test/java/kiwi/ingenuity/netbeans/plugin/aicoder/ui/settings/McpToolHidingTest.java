package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpToolHidingTest {

    @Test
    void databaseConfigSettingsAreHiddenWhileTheDatabaseGroupIsOff() {
        McpToolSelectionModel model = new McpToolSelectionModel(McpToolConfig.ALL_ENABLED);

        model.setGroupEnabled(McpToolGroupEnum.DATABASE, false);
        assertFalse(model.areConfigSettingsVisibleFor(McpToolGroupEnum.DATABASE));

        model.setGroupEnabled(McpToolGroupEnum.DATABASE, true);
        assertTrue(model.areConfigSettingsVisibleFor(McpToolGroupEnum.DATABASE));
    }

    @Test
    void webConfigSettingsAreHiddenWhileTheWebGroupIsOff() {
        McpToolSelectionModel model = new McpToolSelectionModel(McpToolConfig.ALL_ENABLED);

        model.setGroupEnabled(McpToolGroupEnum.WEB, false);
        assertFalse(model.areConfigSettingsVisibleFor(McpToolGroupEnum.WEB));

        model.setGroupEnabled(McpToolGroupEnum.WEB, true);
        assertTrue(model.areConfigSettingsVisibleFor(McpToolGroupEnum.WEB));
    }

    @Test
    void switchingOneGroupOffKeepsTheOtherGroupsConfigSettingsVisible() {
        McpToolSelectionModel model = new McpToolSelectionModel(McpToolConfig.ALL_ENABLED);

        model.setGroupEnabled(McpToolGroupEnum.DATABASE, false);

        assertTrue(model.areConfigSettingsVisibleFor(McpToolGroupEnum.WEB));
    }
}

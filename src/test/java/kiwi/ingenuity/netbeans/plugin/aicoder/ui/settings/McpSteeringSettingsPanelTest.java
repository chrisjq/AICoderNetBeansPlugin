package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpSteeringSettingsPanelTest {

    @Test
    void checkboxAndWindowRoundTrip() {
        McpSteeringSettingsPanel panel = new McpSteeringSettingsPanel(true);
        panel.setMcpSteeringSelected(true);
        assertTrue(panel.isMcpSteeringSelected());
        assertTrue(panel.isEnabled());

        panel.setEnabled(false);
        assertFalse(panel.isEnabled());
        panel.setEnabled(true);
        assertTrue(panel.isEnabled());
    }
}

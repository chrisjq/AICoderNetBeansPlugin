package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PeerMessagingSettingsPanelTest {

    @Test
    void dependentOptionsFollowInterAiSelectionIncludingProgrammaticLoad() {
        PeerMessagingSettingsPanel panel = new PeerMessagingSettingsPanel();
        panel.setAllowInterAiSelected(false);
        assertFalse(findOption(panel, "Auto-notify"));
        assertFalse(findOption(panel, "important"));
        panel.setAllowInterAiSelected(true);
        assertTrue(findOption(panel, "Auto-notify"));
        assertTrue(findOption(panel, "important"));
        panel.setAllowInterAiSelected(false);
        assertFalse(findOption(panel, "Auto-notify"));
        assertFalse(findOption(panel, "important"));
    }

    private static boolean findOption(PeerMessagingSettingsPanel panel, String text) {
        for (java.awt.Component child : panel.getComponents()) {
            if (child instanceof javax.swing.JCheckBox box && box.getText().toLowerCase().contains(text.toLowerCase())) {
                return box.isEnabled();
            }
        }
        return false;
    }
}

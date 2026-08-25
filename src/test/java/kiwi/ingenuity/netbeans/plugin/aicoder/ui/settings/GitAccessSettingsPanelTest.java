package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class GitAccessSettingsPanelTest {

    @Test
    void everyOptionRoundTripsThroughItsCheckBox() {
        GitAccessSettingsPanel panel = new GitAccessSettingsPanel(true);
        panel.setAllowGitAccessSelected(true);
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            panel.setOptionSelected(option, true);
            assertTrue(panel.isOptionSelected(option), option.name());
            panel.setOptionSelected(option, false);
            assertFalse(panel.isOptionSelected(option), option.name());
        }
    }

    @Test
    void subOptionsAreGreyedOutWhenTheMasterIsOff() {
        GitAccessSettingsPanel panel = new GitAccessSettingsPanel(true);

        panel.setAllowGitAccessSelected(false);
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            assertFalse(panel.isOptionEnabled(option), option.name());
        }

        panel.setAllowGitAccessSelected(true);
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            assertTrue(panel.isOptionEnabled(option), option.name());
        }
    }
}

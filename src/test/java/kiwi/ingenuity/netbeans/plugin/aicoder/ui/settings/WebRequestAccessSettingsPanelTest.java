package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class WebRequestAccessSettingsPanelTest {

    @Test
    void disablingParentDisablesEveryChildOption() throws Exception {
        WebRequestAccessSettingsPanel panel = createPanel();

        SwingUtilities.invokeAndWait(() -> panel.setAllowWebRequestsSelected(false));

        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            assertFalse(panel.isOptionEnabled(option), () -> option + " should be disabled");
        }
    }

    @Test
    void enablingParentReEnablesEveryChildOption() throws Exception {
        WebRequestAccessSettingsPanel panel = createPanel();

        SwingUtilities.invokeAndWait(() -> {
            panel.setAllowWebRequestsSelected(false);
            panel.setAllowWebRequestsSelected(true);
        });

        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            assertTrue(panel.isOptionEnabled(option), () -> option + " should be enabled");
        }
    }

    private static WebRequestAccessSettingsPanel createPanel() throws Exception {
        final WebRequestAccessSettingsPanel[] panel = new WebRequestAccessSettingsPanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new WebRequestAccessSettingsPanel(false));
        return panel[0];
    }
}

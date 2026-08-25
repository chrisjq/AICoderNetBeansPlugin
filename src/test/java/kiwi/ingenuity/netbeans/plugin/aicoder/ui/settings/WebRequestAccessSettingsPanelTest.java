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

    /**
     * The destination options must reach BOTH the plugin-defaults panel (global mode) and a session's own config
     * (session mode). The panel builds from the enum, so this guards the wiring rather than the layout — if either
     * constant is ever dropped from the enum, or the panel stops iterating values(), a user loses the only way to grant
     * this access.
     */
    @Test
    void destinationOptionsAppearInBothGlobalAndSessionModes() throws Exception {
        for (boolean sessionMode : new boolean[]{true, false}) {
            final WebRequestAccessSettingsPanel[] created = new WebRequestAccessSettingsPanel[1];
            SwingUtilities.invokeAndWait(() -> created[0] = new WebRequestAccessSettingsPanel(sessionMode));
            WebRequestAccessSettingsPanel panel = created[0];
            SwingUtilities.invokeAndWait(() -> panel.setAllowWebRequestsSelected(true));
            for (WebRequestAccessOptionEnum option
                    : new WebRequestAccessOptionEnum[]{WebRequestAccessOptionEnum.LOCALHOST,
                        WebRequestAccessOptionEnum.PRIVATE_NETWORKS}) {
                SwingUtilities.invokeAndWait(() -> panel.setOptionSelected(option, true));
                assertTrue(panel.isOptionSelected(option),
                        option + " must have a checkbox in sessionMode=" + sessionMode);
                assertTrue(panel.isOptionEnabled(option),
                        option + " must be enabled when web requests are allowed");
            }
        }
    }

    /**
     * Destination options obey the master switch like every other option.
     */
    @Test
    void destinationOptionsAreDisabledWhenWebRequestsAreOff() throws Exception {
        final WebRequestAccessSettingsPanel[] created = new WebRequestAccessSettingsPanel[1];
        SwingUtilities.invokeAndWait(() -> created[0] = new WebRequestAccessSettingsPanel(true));
        WebRequestAccessSettingsPanel panel = created[0];
        SwingUtilities.invokeAndWait(() -> panel.setAllowWebRequestsSelected(false));
        assertFalse(panel.isOptionEnabled(WebRequestAccessOptionEnum.LOCALHOST));
        assertFalse(panel.isOptionEnabled(WebRequestAccessOptionEnum.PRIVATE_NETWORKS));
    }

    private static WebRequestAccessSettingsPanel createPanel() throws Exception {
        final WebRequestAccessSettingsPanel[] panel = new WebRequestAccessSettingsPanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new WebRequestAccessSettingsPanel(false));
        return panel[0];
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class PluginSettingsPanelBannerTest {

    private static final List<String> BANNER_PHRASES = List.of(
            "Default config template (Templates &rarr; Config Templates)",
            "MCP templates (Templates &rarr; MCP Templates)",
            "plugin-wide or per AI type");

    @Test
    void defaultsBannerNoteAppearsInExactlyOneLabel() throws Exception {
        List<String> labelTexts = new ArrayList<>();
        collectLabelTexts(createPanel(), labelTexts);

        for (String phrase : BANNER_PHRASES) {
            int count = 0;
            for (String text : labelTexts) {
                if (text != null && text.contains(phrase)) {
                    count++;
                }
            }
            assertEquals(1, count, "\"" + phrase + "\" must appear in exactly one JLabel, found in " + count);
        }
    }

    private static PluginSettingsPanel createPanel() throws Exception {
        final PluginSettingsPanel[] panel = new PluginSettingsPanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new PluginSettingsPanel());
        return panel[0];
    }

    private static void collectLabelTexts(Component component, List<String> texts) {
        if (component instanceof JLabel label) {
            texts.add(label.getText());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectLabelTexts(child, texts);
            }
        }
    }
}

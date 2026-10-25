package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.CompactionStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleBasicPanelCompactionTest {

    private Preferences prefs;

    @BeforeEach
    void isolatePreferences() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        prefs = Preferences.userRoot().node("openai-compatible-basic-" + UUID.randomUUID());
        OpenAiCompatiblePluginSettings.useForTest(prefs);
    }

    @AfterEach
    void restorePreferences() throws Exception {
        OpenAiCompatiblePluginSettings.useForTest(null);
        if (prefs != null) {
            prefs.removeNode();
        }
    }

    private static <T extends Component> List<T> find(Component root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T extends Component> void collect(Component component, Class<T> type, List<T> found) {
        if (type.isInstance(component)) {
            found.add(type.cast(component));
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, type, found);
            }
        }
    }

    private static JComboBox<?> strategyCombo(Component root) {
        return find(root, JComboBox.class).stream()
                .filter(combo -> combo.getItemCount() > 0 && combo.getItemAt(0) instanceof CompactionStrategyEnum)
                .findFirst().orElseThrow();
    }

    private static String keyLabel(Component root) {
        return find(root, JLabel.class).stream().map(JLabel::getText)
                .filter(text -> text != null && text.startsWith("Key:")).findFirst().orElseThrow();
    }

    private static OpenAiCompatibleSessionSettings explicit() {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setServerType(ServerTypeEnum.OTHER);
        settings.setBaseUrl("http://h:1");
        settings.setModel("m");
        settings.setApiKeySet(false);
        return settings;
    }

    @Test
    @Timeout(10)
    void theStrategyComboOffersAutoSummariseDropAndNoneWithTheAutoTooltip() {
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        panel.load(explicit());

        JComboBox<?> combo = strategyCombo(panel.component());
        assertEquals(4, combo.getItemCount());
        assertEquals(CompactionStrategyEnum.AUTO, combo.getItemAt(0));
        assertEquals(CompactionStrategyEnum.SUMMARISE, combo.getItemAt(1));
        assertEquals(CompactionStrategyEnum.DROP, combo.getItemAt(2));
        assertEquals(CompactionStrategyEnum.NONE, combo.getItemAt(3));
        assertEquals(CompactionStrategyEnum.AUTO, combo.getSelectedItem());
        assertEquals("Summarises when the window is at least the summarise threshold (16,384 by default), "
                + "otherwise drops the oldest messages.", combo.getToolTipText());
    }

    @Test
    @Timeout(10)
    void theCompactionControlsRoundTripThroughLoadAndApply() {
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        OpenAiCompatibleSessionSettings in = explicit();
        in.setCompactionStrategy(CompactionStrategyEnum.NONE);
        in.setAutoCompact(false);
        in.setPrune(false);
        panel.load(in);

        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);

        assertEquals(CompactionStrategyEnum.NONE, out.compactionStrategy());
        assertEquals(false, out.autoCompact());
        assertEquals(false, out.prune());
        assertEquals("Never compacts; the server decides what happens when the context is full",
                     strategyCombo(panel.component()).getToolTipText());
    }

    @Test
    @Timeout(10)
    void unsetSettingsShowTheDefaultsAutoWithBothSwitchesOn() {
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        panel.load(explicit());

        assertEquals(CompactionStrategyEnum.AUTO, strategyCombo(panel.component()).getSelectedItem());
        List<JCheckBox> checks = find(panel.component(), JCheckBox.class);
        assertTrue(checks.stream().filter(c -> c.getText().startsWith("Compact automatically"))
                .findFirst().orElseThrow().isSelected());
        assertTrue(checks.stream().filter(c -> c.getText().startsWith("Shorten old tool output"))
                .findFirst().orElseThrow().isSelected());
    }

    @Test
    @Timeout(10)
    void aStoredSummariseStrategyIsShownAsSummarise() {
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        OpenAiCompatibleSessionSettings in = explicit();
        in.setCompactionStrategy(CompactionStrategyEnum.SUMMARISE);
        panel.load(in);

        assertEquals(CompactionStrategyEnum.SUMMARISE, strategyCombo(panel.component()).getSelectedItem());
    }

    @Test
    @Timeout(10)
    void aSessionOnTheDefaultUrlWithoutItsOwnKeyShowsUsingDefault() {
        OpenAiCompatiblePluginSettings.setBaseUrl("http://h:1");
        OpenAiCompatiblePluginSettings.setDefaultKeySet(true);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();

        OpenAiCompatibleSessionSettings sameRoot = explicit();
        sameRoot.setBaseUrl("http://h:1/v1");
        panel.load(sameRoot);
        assertEquals("Key: using default (for h)", keyLabel(panel.component()));

        OpenAiCompatibleSessionSettings otherRoot = explicit();
        otherRoot.setBaseUrl("http://other:1");
        panel.load(otherRoot);
        assertEquals("Key: not set", keyLabel(panel.component()));

        OpenAiCompatibleSessionSettings ownKey = explicit();
        ownKey.setApiKeySet(true);
        panel.load(ownKey);
        assertEquals("Key: set", keyLabel(panel.component()));
    }

    @Test
    @Timeout(10)
    void editingTheUrlRefreshesTheUsingDefaultLabel() throws Exception {
        OpenAiCompatiblePluginSettings.setBaseUrl("http://h:1");
        OpenAiCompatiblePluginSettings.setDefaultKeySet(true);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        OpenAiCompatibleSessionSettings in = explicit();
        in.setBaseUrl("http://other:1");
        panel.load(in);
        assertEquals("Key: not set", keyLabel(panel.component()));

        javax.swing.JTextField url = find(panel.component(), javax.swing.JTextField.class).stream()
                .filter(field -> !(field instanceof javax.swing.JPasswordField)).findFirst().orElseThrow();
        SwingUtilities.invokeAndWait(() -> url.setText("http://h:1"));

        assertEquals("Key: using default (for h)", keyLabel(panel.component()));
    }

    @Test
    @Timeout(10)
    void theInfoBarCompactButtonFiresItsListenerAndCanBeDisabled() throws Exception {
        OpenAiCompatibleInfoBarExtension extension = new OpenAiCompatibleInfoBarExtension();
        JButton button = extension.createComponents().stream()
                .filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(candidate -> candidate.getText().endsWith("Compact")).findFirst().orElseThrow();
        assertEquals("⇒ Compact", button.getText());
        assertNotNull(button.getToolTipText());

        int[] calls = new int[1];
        button.doClick();
        assertEquals(0, calls[0]);

        extension.setCompactListener(() -> calls[0]++);
        button.doClick();
        assertEquals(1, calls[0]);

        extension.setCompactEnabled(false);
        SwingUtilities.invokeAndWait(() -> {
        });
        assertFalse(button.isEnabled());

        extension.setCompactEnabled(true);
        SwingUtilities.invokeAndWait(() -> {
        });
        assertTrue(button.isEnabled());
    }
}

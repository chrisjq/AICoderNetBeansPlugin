package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.JComboBox;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleSummaryModelPanelTest {

    private Preferences prefs;

    @BeforeEach
    void isolatePreferences() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        prefs = MemoryPreferences.create();
        OpenAiCompatiblePluginSettings.useForTest(prefs);
    }

    @AfterEach
    void restorePreferences() throws Exception {
        OpenAiCompatiblePluginSettings.useForTest(null);
        if (prefs != null) {
            prefs.removeNode();
        }
    }

    private static void collect(Component component, List<JComboBox<?>> found) {
        if (component instanceof JComboBox<?> combo) {
            found.add(combo);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, found);
            }
        }
    }

    private static JComboBox<?> summaryCombo(Component root) {
        List<JComboBox<?>> combos = new ArrayList<>();
        collect(root, combos);
        return combos.stream()
                .filter(combo -> combo.getToolTipText() != null
                        && combo.getToolTipText().contains("Leave empty to use the chat model"))
                .findFirst().orElseThrow();
    }

    private static OpenAiCompatibleSessionSettings explicit() {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setServerType(ServerTypeEnum.OTHER);
        settings.setBaseUrl("http://h:1");
        settings.setModel("chat-model");
        settings.setApiKeySet(false);
        return settings;
    }

    @Test
    @Timeout(10)
    void theSummaryModelComboIsEditableWithATooltipThatSaysEmptyMeansTheChatModel() {
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        panel.load(explicit());

        JComboBox<?> combo = summaryCombo(panel.component());
        assertTrue(combo.isEditable());
        assertTrue(combo.getToolTipText().contains("Leave empty to use the chat model"));
    }

    @Test
    @Timeout(10)
    void aSetSummaryModelRoundTripsThroughLoadAndApply() {
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        OpenAiCompatibleSessionSettings in = explicit();
        in.setSummaryModel("small-summariser");
        panel.load(in);

        assertEquals("small-summariser", summaryCombo(panel.component()).getEditor().getItem());
        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);

        assertEquals("small-summariser", out.summaryModel());
        assertEquals("small-summariser", out.effectiveSummaryModel("chat-model"));
    }

    @Test
    @Timeout(10)
    void anEmptySummaryModelIsStoredAsUnsetSoTheChatModelIsUsed() {
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        panel.load(explicit());

        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);

        assertNull(out.summaryModel());
        assertEquals("chat-model", out.effectiveSummaryModel(out.model()));
    }

    @Test
    @Timeout(10)
    void typingAndClearingTheSummaryModelIsReflectedInApply() {
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        panel.load(explicit());
        JComboBox<?> combo = summaryCombo(panel.component());

        combo.getEditor().setItem("  typed-model  ");
        OpenAiCompatibleSessionSettings typed = new OpenAiCompatibleSessionSettings();
        panel.applyTo(typed);
        assertEquals("typed-model", typed.summaryModel());

        combo.getEditor().setItem("");
        OpenAiCompatibleSessionSettings cleared = new OpenAiCompatibleSessionSettings();
        panel.applyTo(cleared);
        assertNull(cleared.summaryModel());
    }

    @Test
    @Timeout(10)
    void aStoredSummaryModelStaysUnsetWhenReloadedAfterBeingCleared() {
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        OpenAiCompatibleSessionSettings in = explicit();
        in.setSummaryModel("old");
        panel.load(in);
        panel.load(explicit());

        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);
        assertNull(out.summaryModel());
    }
}

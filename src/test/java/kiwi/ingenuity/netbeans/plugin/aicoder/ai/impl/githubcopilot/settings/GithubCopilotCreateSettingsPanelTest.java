package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mirrors {@code GrokCreateSettingsPanelTest}. Also covers the review finding shared with
 * {@code GithubCopilotAiSettingsTabTest}: {@code applyTo()} must never persist a reasoning effort the currently
 * selected model does not support, even if the combo's own selection were somehow stale.
 */
class GithubCopilotCreateSettingsPanelTest {

    @BeforeEach
    @AfterEach
    void resetGlobalState() {
        GithubCopilotPluginSettings.setReasoningEffort("");
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
        // The stale-selection test sets the combo programmatically, which its ActionListener turns into a
        // remembered "user pick" on the shared static. Clear it so it cannot leak into other tests (which depend on
        // load() seeing no remembered value).
        GithubCopilotCreateSettingsPanel.lastSelectedReasoningEffort = null;
    }

    @Test
    void defaultLabelMapsToNullStoredReasoningEffort() {
        GithubCopilotCreateSettingsPanel panel = new GithubCopilotCreateSettingsPanel(new AiModelCatalog());
        GithubCopilotSessionSettings empty = new GithubCopilotSessionSettings();
        panel.load(empty);

        GithubCopilotSessionSettings result = new GithubCopilotSessionSettings();
        panel.applyTo(result);

        assertNull(result.reasoningEffort(), "\"(model default)\" must store null, not an empty/placeholder string");
    }

    @Test
    void globalDefaultReasoningEffortPropagatesWhenSessionHasNone() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        settings.setModel("auto");
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of("auto", List.of("low", "medium")), Map.of());
        GithubCopilotPluginSettings.setReasoningEffort("medium");
        GithubCopilotCreateSettingsPanel panel = new GithubCopilotCreateSettingsPanel(new AiModelCatalog());
        panel.load(settings);

        GithubCopilotSessionSettings result = new GithubCopilotSessionSettings();
        panel.applyTo(result);

        assertEquals("medium", result.reasoningEffort());
    }

    @Test
    void storedReasoningEffortRoundTripsThroughLoadAndApplyToWhenSupported() {
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of("auto", List.of("low", "xhigh")), Map.of());
        GithubCopilotCreateSettingsPanel panel = new GithubCopilotCreateSettingsPanel(new AiModelCatalog());
        GithubCopilotSessionSettings stored = new GithubCopilotSessionSettings();
        stored.setModel("auto");
        stored.setReasoningEffort("xhigh");
        panel.load(stored);

        GithubCopilotSessionSettings result = new GithubCopilotSessionSettings();
        panel.applyTo(result);

        assertEquals("xhigh", result.reasoningEffort());
    }

    @Test
    void storedReasoningEffortUnsupportedByModelNeverPersists() {
        // No discovery has populated the per-model cache for "auto" — an unknown/no-data model means "no support".
        GithubCopilotCreateSettingsPanel panel = new GithubCopilotCreateSettingsPanel(new AiModelCatalog());
        GithubCopilotSessionSettings stored = new GithubCopilotSessionSettings();
        stored.setModel("auto");
        stored.setReasoningEffort("xhigh");
        panel.load(stored);

        GithubCopilotSessionSettings result = new GithubCopilotSessionSettings();
        panel.applyTo(result);

        assertNull(result.reasoningEffort(),
                   "applyTo() must never persist a reasoning effort the currently selected model does not support");
    }

    @Test
    void repopulationThatDropsTheSelectedEffortCollapsesTheComboCleanly() throws Exception {
        // Follow the real stale-selection sequence (the one that actually produced the bug on Grok): (1) the combo is
        // populated WITH "xhigh", (2) "xhigh" is selected legitimately (present, so the selection takes), then (3) the
        // combo is repopulated for a model whose supported list no longer contains "xhigh" — removeAllItems + addItem
        // without a setSelectedItem reset. On this JDK the combo does NOT go stale: the rawest repopulation route
        // (no setSelectedItem at all) still collapses the selection cleanly to the first entry, so getSelectedItem()
        // can never return a value the current model does not support. The defensive re-validation in applyTo() is
        // therefore belt-and-braces, not a fix for a live bug — the equivalent path IS live in Grok's Options tab.
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of("auto", List.of("low", "xhigh")), Map.of());
        GithubCopilotCreateSettingsPanel panel = new GithubCopilotCreateSettingsPanel(new AiModelCatalog());
        GithubCopilotSessionSettings stored = new GithubCopilotSessionSettings();
        stored.setModel("auto");
        stored.setReasoningEffort("xhigh");
        panel.load(stored);
        JComboBox<String> combo = reasoningEffortCombo(panel);
        AtomicReference<Object> selection = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> selection.set(combo.getSelectedItem()));
        assertEquals("xhigh", selection.get(), "sanity: with xhigh in the list, the selection must have taken");
        // Change the model's supported list so "xhigh" is no longer valid, then repopulate the combo the way a refresh
        // that CANNOT find the previous selection would: drop everything and re-add, WITHOUT restoring the selection.
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of("auto", List.of("low", "medium")), Map.of());
        SwingUtilities.invokeAndWait(() -> {
            combo.removeAllItems();
            combo.addItem(BlankSafeComboRenderer.DEFAULT_OPTION);
            combo.addItem("low");
            combo.addItem("medium");
        });
        AtomicReference<Object> collapsed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> collapsed.set(combo.getSelectedItem()));
        assertEquals(BlankSafeComboRenderer.DEFAULT_OPTION, collapsed.get(),
                     "the repopulated combo must collapse the no-longer-supported selection to the first entry; "
                     + "a stale selectedItemReminder must not survive");

        GithubCopilotSessionSettings result = new GithubCopilotSessionSettings();
        panel.applyTo(result);

        assertNull(result.reasoningEffort(),
                   "applyTo() must never persist a reasoning effort the currently selected model does not support");
    }

    @Test
    void modelSelectionRoundTripsThroughLoadAndApplyTo() {
        GithubCopilotCreateSettingsPanel panel = new GithubCopilotCreateSettingsPanel(new AiModelCatalog());
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        settings.setModel("gpt-5.4");
        panel.load(settings);

        GithubCopilotSessionSettings result = new GithubCopilotSessionSettings();
        panel.applyTo(result);

        assertEquals("gpt-5.4", result.model());
    }

    private static JComboBox<String> reasoningEffortCombo(GithubCopilotCreateSettingsPanel panel) throws Exception {
        Field field = GithubCopilotCreateSettingsPanel.class.getDeclaredField("reasoningEffortCombo");
        field.setAccessible(true);
        return (JComboBox<String>) field.get(panel);
    }
}

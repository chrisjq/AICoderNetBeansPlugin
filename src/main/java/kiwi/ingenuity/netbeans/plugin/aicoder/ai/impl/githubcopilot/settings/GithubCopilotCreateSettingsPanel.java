package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.GithubCopilotAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;

/**
 * Session-create panel for GitHub Copilot: model, plus reasoning effort. Unlike Claude/pi's static effort lists,
 * Copilot's supported efforts are live per-model data discovered by {@code GithubCopilotModelDiscovery} and cached in
 * {@link GithubCopilotPluginSettings} — this panel drives its combo from that cache for whichever model is currently
 * selected, never a hardcoded list.
 *
 * <p>
 * {@link ModelCreateSettingsPanel} keeps its own model combo private with no change hook, so the model combo is located
 * once via the component tree (it already exists once {@code super(...)} returns) and a plain {@code ActionListener} is
 * added directly to it, alongside whatever listener the base class already has.
 */
public final class GithubCopilotCreateSettingsPanel extends ModelCreateSettingsPanel<GithubCopilotSessionSettings> {

    static volatile String lastSelectedReasoningEffort = null;

    private final JComboBox<String> reasoningEffortCombo = new JComboBox<>();

    /**
     * True while this class is setting {@link #reasoningEffortCombo}'s selection itself, so
     * {@link #rememberReasoningEffortSelection()} can tell that apart from a real user pick — mirrors
     * {@code ClaudeCreateSettingsPanel}'s {@code programmatic} flag.
     */
    private boolean programmatic = false;

    public GithubCopilotCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.GitHubCoPilot, catalog, GithubCopilotSessionSettings::model, GithubCopilotSessionSettings::setModel);
        reasoningEffortCombo.setToolTipText("Reasoning effort — options depend on the selected model");
        JComboBox<?> modelCombo = findModelCombo(component());
        if (modelCombo != null) {
            modelCombo.addActionListener(e -> refreshReasoningEffortOptions(modelText(modelCombo), null));
        }
        refreshReasoningEffortOptions(modelCombo != null ? modelText(modelCombo) : defaultModel(), null);
        reasoningEffortCombo.addActionListener(e -> rememberReasoningEffortSelection());

        content().add(new JLabel("Reasoning effort:"), BorderLayout.WEST);
        content().add(reasoningEffortCombo, BorderLayout.CENTER);
    }

    @Override
    public void load(GithubCopilotSessionSettings settings) {
        super.load(settings);
        String effort = settings.reasoningEffort();
        if (effort == null || effort.isBlank()) {
            effort = lastSelectedReasoningEffort;
        }
        if (effort == null || effort.isBlank()) {
            String globalDefault = GithubCopilotPluginSettings.getReasoningEffort();
            effort = (globalDefault == null || globalDefault.isBlank()) ? null : globalDefault;
        }
        JComboBox<?> modelCombo = findModelCombo(component());
        String model = modelCombo != null ? modelText(modelCombo) : defaultModel();
        refreshReasoningEffortOptions(model, effort);
    }

    @Override
    public void applyTo(GithubCopilotSessionSettings settings) {
        super.applyTo(settings);
        String effort = selectedReasoningEffort();
        // Defensive re-validation against the live per-model list, not just trust in the combo's own selected item:
        // reasoningEffortCombo is non-editable, and JComboBox.setSelectedItem on a non-editable combo can leave
        // getSelectedItem() returning a value no longer among the combo's own items (e.g. a stale selection
        // retained across a refresh that dropped it) — silently persisting an unsupported value the user has no
        // way to clear from the UI. Hit exactly this on pi; never persist a value the current model doesn't
        // support.
        if (effort != null) {
            JComboBox<?> modelCombo = findModelCombo(component());
            String model = modelCombo != null ? modelText(modelCombo) : defaultModel();
            if (!GithubCopilotPluginSettings.getSupportedReasoningEfforts(model).contains(effort)) {
                effort = null;
            }
        }
        settings.setReasoningEffort(effort);
    }

    @Override
    protected List<String> knownModels() {
        return Arrays.asList(GithubCopilotPluginSettings.getKnownModels());
    }

    @Override
    protected String defaultModel() {
        return GithubCopilotPluginSettings.getModel();
    }

    @Override
    public void startLoading() {
        GithubCopilotAiImplementation.triggerModelDiscovery();
    }

    /**
     * Rebuilds {@link #reasoningEffortCombo}'s options from {@code model}'s live-discovered supported list (empty/
     * absent means "no support": only {@link BlankSafeComboRenderer#DEFAULT_OPTION} is offered), selecting {@code
     * preferredEffort} if still valid for this model, else the combo's own current selection if still valid, else {@link
     * BlankSafeComboRenderer#DEFAULT_OPTION}.
     */
    private void refreshReasoningEffortOptions(String model, String preferredEffort) {
        List<String> supported = GithubCopilotPluginSettings.getSupportedReasoningEfforts(model);
        String current = selectedReasoningEffort();
        String toSelect = (preferredEffort != null && supported.contains(preferredEffort)) ? preferredEffort
                          : (current != null && supported.contains(current) ? current : null);
        boolean wasProgrammatic = programmatic;
        programmatic = true;
        try {
            reasoningEffortCombo.removeAllItems();
            reasoningEffortCombo.addItem(BlankSafeComboRenderer.DEFAULT_OPTION);
            for (String effort : supported) {
                reasoningEffortCombo.addItem(effort);
            }
            reasoningEffortCombo.setSelectedItem(toSelect != null ? toSelect : BlankSafeComboRenderer.DEFAULT_OPTION);
        }
        finally {
            programmatic = wasProgrammatic;
        }
    }

    private String selectedReasoningEffort() {
        Object sel = reasoningEffortCombo.getSelectedItem();
        return (sel == null || BlankSafeComboRenderer.DEFAULT_OPTION.equals(sel)) ? null : sel.toString();
    }

    private void rememberReasoningEffortSelection() {
        if (programmatic) {
            return;
        }
        lastSelectedReasoningEffort = selectedReasoningEffort();
    }

    /**
     * Reads the current text of a model combo the same way {@code ModelCreateSettingsPanel}'s own private
     * {@code getSelectedModel()} does — the selected item if non-blank, else the editor's typed text.
     */
    private static String modelText(JComboBox<?> combo) {
        Object sel = combo.getSelectedItem();
        if (sel != null && !sel.toString().isBlank()) {
            return sel.toString().trim();
        }
        if (combo.isEditable() && combo.getEditor() != null) {
            Object item = combo.getEditor().getItem();
            if (item != null && !item.toString().isBlank()) {
                return item.toString().trim();
            }
        }
        return null;
    }

    /**
     * {@link ModelCreateSettingsPanel} exposes no accessor for its model combo, so it is located once here by walking
     * {@link #component()}'s tree — called before {@link #reasoningEffortCombo} is added to that tree, so there is
     * exactly one combo to find.
     */
    private static JComboBox<?> findModelCombo(Component root) {
        if (root instanceof JComboBox<?> combo) {
            return combo;
        }
        if (root instanceof Container container) {
            for (Component c : container.getComponents()) {
                JComboBox<?> found = findModelCombo(c);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}

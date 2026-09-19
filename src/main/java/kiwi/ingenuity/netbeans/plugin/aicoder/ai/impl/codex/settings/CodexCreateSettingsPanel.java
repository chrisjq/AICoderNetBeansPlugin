package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings;

import java.awt.BorderLayout;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.CodexReasoningEffortCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;

/**
 * Session-create panel for Codex: model, plus the reasoning effort for new sessions (the only other create-time control
 * Codex exposes — there is no build/plan mode). Unlike pi/Claude there is no fixed level list to hardcode (
 * ReasoningEffort is a free-form string): the combo offers {@code (model default)} plus any supported efforts past
 * sessions discovered via the {@code model/list} probe ({@link CodexReasoningEffortCatalog}), and is editable so
 * arbitrary effort strings still work. The effort control goes in the base panel's {@link #content()} area, so this
 * extends the shared panel rather than wrapping it.
 */
public final class CodexCreateSettingsPanel extends ModelCreateSettingsPanel<CodexSessionSettings> {

    static volatile String lastSelectedEffort = null;

    private final JComboBox<String> effortCombo;

    /**
     * True while {@link #load} sets the selection, so a programmatic restore does not overwrite
     * {@link #lastSelectedEffort} — mirrors {@code ClaudeCreateSettingsPanel}'s flag. Without this, opening the New
     * Session dialog for a session with a stored effort silently pollutes what the NEXT blank session defaults to.
     */
    private boolean programmatic = false;

    public CodexCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.CODEX, catalog, CodexSessionSettings::model, CodexSessionSettings::setModel);
        effortCombo = new JComboBox<>(new String[]{BlankSafeComboRenderer.DEFAULT_OPTION});
        effortCombo.setEditable(true);
        effortCombo.setToolTipText("Reasoning effort for new sessions — \"" + BlankSafeComboRenderer.DEFAULT_OPTION
                + "\" omits the field, letting the model apply its own default (editable: any effort string is accepted)");
        effortCombo.addActionListener(e -> rememberEffortSelection());

        content().add(new JLabel("Effort:"), BorderLayout.WEST);
        content().add(effortCombo, BorderLayout.CENTER);
    }

    @Override
    public void load(CodexSessionSettings settings) {
        super.load(settings);
        String model = settings.model();
        if (model == null || model.isBlank()) {
            model = defaultModel();
        }
        String effort = settings.effort();
        if (effort == null || effort.isBlank()) {
            effort = lastSelectedEffort;
        }
        if (effort == null || effort.isBlank()) {
            effort = CodexPluginSettings.getEffort();
        }
        programmatic = true;
        try {
            rebuildEffortOptions(model);
            effortCombo.setSelectedItem((effort == null || effort.isBlank()) ? BlankSafeComboRenderer.DEFAULT_OPTION : effort);
        }
        finally {
            programmatic = false;
        }
    }

    @Override
    public void applyTo(CodexSessionSettings settings) {
        super.applyTo(settings);
        String s = currentEditorText();
        settings.setEffort(s.isEmpty() || BlankSafeComboRenderer.DEFAULT_OPTION.equals(s) ? null : s);
    }

    private void rebuildEffortOptions(String model) {
        List<String> supported = CodexReasoningEffortCatalog.supportedEffortsFor(model);
        String[] items = new String[1 + supported.size()];
        items[0] = BlankSafeComboRenderer.DEFAULT_OPTION;
        for (int i = 0; i < supported.size(); i++) {
            items[i + 1] = supported.get(i);
        }
        effortCombo.setModel(new javax.swing.DefaultComboBoxModel<>(items));
        effortCombo.setSelectedItem(BlankSafeComboRenderer.DEFAULT_OPTION);
    }

    private String currentEditorText() {
        Object sel = effortCombo.getSelectedItem();
        if (sel == null && effortCombo.getEditor() != null) {
            sel = effortCombo.getEditor().getItem();
        }
        return sel != null ? sel.toString().trim() : "";
    }

    private void rememberEffortSelection() {
        if (programmatic) {
            return;
        }
        String s = currentEditorText();
        lastSelectedEffort = (s.isEmpty() || BlankSafeComboRenderer.DEFAULT_OPTION.equals(s)) ? null : s;
    }

    @Override
    protected List<String> knownModels() {
        return Arrays.asList(CodexPluginSettings.getKnownModels());
    }

    @Override
    protected String defaultModel() {
        return CodexPluginSettings.getModel();
    }
}

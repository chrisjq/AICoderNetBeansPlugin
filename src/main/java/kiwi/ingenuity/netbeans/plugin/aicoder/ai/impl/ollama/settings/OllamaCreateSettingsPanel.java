package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.OllamaAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;

/**
 * Local Ollama create settings include model, session base URL, and thinking (reasoning effort). Base URL and
 * thinking share the base panel's {@link #content()} area via a small two-row sub-panel, since
 * {@code content()} itself only has one {@code BorderLayout} slot.
 */
public final class OllamaCreateSettingsPanel extends ModelCreateSettingsPanel<OllamaSessionSettings> {

    /**
     * Maps to a {@code null} stored reasoning effort, meaning "use the model's own default", per the spec.
     */
    private static final String[] REASONING_EFFORT_OPTIONS = {BlankSafeComboRenderer.DEFAULT_OPTION, "low", "medium", "high"};
    static volatile String lastSelectedReasoningEffort = null;

    private final JTextField baseUrl = new JTextField(28);
    private final JComboBox<String> reasoningEffortCombo;
    private final JCheckBox nativeToolCalling = new JCheckBox("Use native tool calling");

    /**
     * True while {@link #load} is setting {@link #reasoningEffortCombo}'s selection, so
     * {@link #rememberReasoningEffortSelection()} can tell that programmatic restore apart from a real user
     * pick — mirrors {@code PiCreateSettingsPanel}'s {@code programmatic} flag.
     */
    private boolean programmatic = false;

    public OllamaCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.OLLAMA_LOCAL, catalog, OllamaSessionSettings::model, OllamaSessionSettings::setModel);
        reasoningEffortCombo = new JComboBox<>(REASONING_EFFORT_OPTIONS);
        reasoningEffortCombo.setToolTipText("Default thinking effort for new sessions");
        reasoningEffortCombo.addActionListener(e -> rememberReasoningEffortSelection());

        JPanel extra = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 0, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        extra.add(new JLabel("Base URL:"), c);
        c.gridx = 1;
        c.weightx = 1;
        extra.add(baseUrl, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        extra.add(new JLabel("Thinking:"), c);
        c.gridx = 1;
        c.weightx = 1;
        extra.add(reasoningEffortCombo, c);

        c.gridx = 1;
        c.gridy = 2;
        nativeToolCalling.setToolTipText("Use native tools; switch off for models such as qwen2.5-coder that may misbehave with native tool calling");
        extra.add(nativeToolCalling, c);

        content().add(extra, BorderLayout.CENTER);
    }

    @Override
    public void load(OllamaSessionSettings settings) {
        super.load(settings);
        String url = settings.baseUrl() == null ? "" : settings.baseUrl();
        baseUrl.setText(url);
        nativeToolCalling.setSelected(Boolean.TRUE.equals(settings.useNativeToolCalling()));
        String effort = settings.reasoningEffort();
        if (effort == null || effort.isBlank()) {
            effort = lastSelectedReasoningEffort;
        }
        if (effort == null || effort.isBlank()) {
            effort = OllamaPluginSettings.getReasoningEffort();
        }
        boolean wasProgrammatic = programmatic;
        programmatic = true;
        try {
            reasoningEffortCombo.setSelectedItem((effort == null || effort.isBlank()) ? BlankSafeComboRenderer.DEFAULT_OPTION : effort);
        } finally {
            programmatic = wasProgrammatic;
        }
    }

    @Override
    protected List<String> knownModels() {
        return Arrays.asList(OllamaPluginSettings.getKnownModels());
    }

    @Override
    protected String defaultModel() {
        return OllamaPluginSettings.getModel();
    }

    @Override
    public void startLoading() {
        OllamaAiImplementation.triggerModelDiscovery(baseUrl.getText().trim());
    }

    @Override
    public void applyTo(OllamaSessionSettings settings) {
        super.applyTo(settings);
        String value = baseUrl.getText().trim();
        settings.setBaseUrl(value.isEmpty() ? null : value);
        Object sel = reasoningEffortCombo.getSelectedItem();
        settings.setReasoningEffort((sel != null && !BlankSafeComboRenderer.DEFAULT_OPTION.equals(sel.toString())) ? sel.toString() : null);
        settings.setUseNativeToolCalling(nativeToolCalling.isSelected());
    }

    private void rememberReasoningEffortSelection() {
        if (programmatic) {
            return;
        }
        Object sel = reasoningEffortCombo.getSelectedItem();
        lastSelectedReasoningEffort = (sel != null && !BlankSafeComboRenderer.DEFAULT_OPTION.equals(sel.toString())) ? sel.toString() : null;
    }
}

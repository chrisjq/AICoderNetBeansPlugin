package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.GrokAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.GrokReasoningEffortSupport;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;

/**
 * Session-create panel for Grok: model, plus reasoning effort. The base panel's model combo exposes no
 * change-notification hook to subclasses, so — unlike {@code GrokAiSettingsTab}/{@code GrokAiInfoBarExtension}, which
 * own their model combo directly and filter live — this combo offers the union of every level any known model supports
 * rather than filtering by the model selected in this same dialog. A combination invalid for the actually selected
 * model is caught and cleared by {@code GrokAiProcessManager} at launch time instead, per spec.
 */
public final class GrokCreateSettingsPanel extends ModelCreateSettingsPanel<GrokSessionSettings> {

    private final JComboBox<String> reasoningEffortCombo;

    public GrokCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.GROK, catalog, GrokSessionSettings::model, GrokSessionSettings::setModel);
        List<String> options = new ArrayList<>();
        options.add(BlankSafeComboRenderer.DEFAULT_OPTION);
        options.addAll(GrokReasoningEffortSupport.allKnownLevels());
        reasoningEffortCombo = new JComboBox<>(options.toArray(String[]::new));
        reasoningEffortCombo.setToolTipText("Reasoning effort for new sessions (only applied if the chosen model supports it)");

        content().add(new JLabel("Reasoning effort:"), BorderLayout.WEST);
        content().add(reasoningEffortCombo, BorderLayout.CENTER);
    }

    @Override
    public void load(GrokSessionSettings settings) {
        super.load(settings);
        String effort = settings.reasoningEffort();
        if (effort == null || effort.isBlank()) {
            effort = GrokPluginSettings.getReasoningEffort();
        }
        reasoningEffortCombo.setSelectedItem((effort == null || effort.isBlank()) ? BlankSafeComboRenderer.DEFAULT_OPTION : effort);
    }

    @Override
    public void applyTo(GrokSessionSettings settings) {
        super.applyTo(settings);
        Object sel = reasoningEffortCombo.getSelectedItem();
        settings.setReasoningEffort((sel != null && !BlankSafeComboRenderer.DEFAULT_OPTION.equals(sel.toString())) ? sel.toString() : null);
    }

    @Override
    protected List<String> knownModels() {
        return Arrays.asList(GrokPluginSettings.getKnownModels());
    }

    @Override
    protected String defaultModel() {
        return GrokPluginSettings.getModel();
    }

    @Override
    public void startLoading() {
        GrokAiImplementation.triggerModelDiscovery();
    }
}

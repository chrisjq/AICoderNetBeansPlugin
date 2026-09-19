package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings;

import java.awt.BorderLayout;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;

/**
 * Session-create panel for Claude: model, plus the effort level (last selection remembered for the next dialog, as pi's
 * create panel remembers its thinking level). The effort control goes in the base panel's {@link #content()} area, so
 * this extends the shared panel rather than wrapping it.
 */
public final class ClaudeCreateSettingsPanel extends ModelCreateSettingsPanel<ClaudeSessionSettings> {

    /**
     * Maps to a {@code null} stored effort level, meaning "use Claude's own default", per the spec.
     */
    private static final String[] EFFORT_OPTIONS = {
        BlankSafeComboRenderer.DEFAULT_OPTION, "low", "medium", "high", "xhigh", "max"
    };
    static volatile String lastSelectedEffort = null;

    private final JComboBox<String> effortCombo;

    /**
     * True while {@link #load} is setting {@link #effortCombo}'s selection, so {@link #rememberEffortSelection()} can
     * tell that programmatic restore apart from a real user pick — mirrors {@code ClaudeAiInfoBarExtension}'s
     * {@code programmatic} flag. Without this, opening the New Session dialog for a session with a stored effort
     * silently overwrites {@link #lastSelectedEffort} — polluting what the NEXT blank session defaults to — even though
     * the user never touched the combo.
     */
    private boolean programmatic = false;

    public ClaudeCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.CLAUDE, catalog, ClaudeSessionSettings::model, ClaudeSessionSettings::setModel);
        effortCombo = new JComboBox<>(EFFORT_OPTIONS);
        effortCombo.setToolTipText("Default effort level for new sessions");
        effortCombo.addActionListener(e -> rememberEffortSelection());

        content().add(new JLabel("Effort level:"), BorderLayout.WEST);
        content().add(effortCombo, BorderLayout.CENTER);
    }

    @Override
    public void load(ClaudeSessionSettings settings) {
        super.load(settings);
        String effort = settings.effort();
        if (effort == null || effort.isBlank()) {
            effort = lastSelectedEffort;
        }
        if (effort == null || effort.isBlank()) {
            effort = ClaudePluginSettings.getEffort();
        }
        boolean wasProgrammatic = programmatic;
        programmatic = true;
        try {
            effortCombo.setSelectedItem((effort == null || effort.isBlank()) ? BlankSafeComboRenderer.DEFAULT_OPTION : effort);
        }
        finally {
            programmatic = wasProgrammatic;
        }
    }

    @Override
    public void applyTo(ClaudeSessionSettings settings) {
        super.applyTo(settings);
        Object sel = effortCombo.getSelectedItem();
        settings.setEffort((sel != null && !BlankSafeComboRenderer.DEFAULT_OPTION.equals(sel.toString())) ? sel.toString() : null);
    }

    @Override
    protected List<String> knownModels() {
        return Arrays.asList(ClaudePluginSettings.getKnownModels());
    }

    @Override
    protected String defaultModel() {
        return ClaudePluginSettings.getModel();
    }

    @Override
    public void startLoading() {
        ClaudeAiImplementation.triggerModelDiscovery();
    }

    private void rememberEffortSelection() {
        if (programmatic) {
            return;
        }
        Object sel = effortCombo.getSelectedItem();
        lastSelectedEffort = (sel != null && !BlankSafeComboRenderer.DEFAULT_OPTION.equals(sel.toString())) ? sel.toString() : null;
    }
}

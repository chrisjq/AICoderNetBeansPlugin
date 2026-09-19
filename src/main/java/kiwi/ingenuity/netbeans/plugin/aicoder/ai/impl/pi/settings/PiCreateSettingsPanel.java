package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings;

import java.awt.BorderLayout;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiExecutableLocator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiModelDiscovery;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;

/**
 * Session-create panel for pi: model, plus the thinking level (last selection remembered for the next dialog, as
 * OpenCode's create panel remembers its mode). The thinking-level control goes in the base panel's {@link #content()}
 * area, so this extends the shared panel rather than wrapping it.
 */
public final class PiCreateSettingsPanel extends ModelCreateSettingsPanel<PiSessionSettings> {

    /**
     * Maps to a {@code null} stored thinking level, meaning "use pi's own default", per the spec.
     */
    private static final String[] THINKING_LEVEL_OPTIONS = {
        BlankSafeComboRenderer.DEFAULT_OPTION, "off", "minimal", "low", "medium", "high", "xhigh", "max"
    };
    static volatile String lastSelectedThinkingLevel = null;

    private final JComboBox<String> thinkingLevelCombo;

    /**
     * True while {@link #load} is setting {@link #thinkingLevelCombo}'s selection, so
     * {@link #rememberThinkingLevelSelection()} can tell that programmatic restore apart from a real user pick —
     * mirrors {@code PiAiInfoBarExtension}'s {@code programmatic} flag. Without this, opening the New Session dialog
     * for a session with a stored thinking level silently overwrites {@link #lastSelectedThinkingLevel} — polluting
     * what the NEXT blank session defaults to — even though the user never touched the combo.
     */
    private boolean programmatic = false;

    public PiCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.PI, catalog, PiSessionSettings::model, PiSessionSettings::setModel);
        thinkingLevelCombo = new JComboBox<>(THINKING_LEVEL_OPTIONS);
        thinkingLevelCombo.setToolTipText("Default thinking level for new sessions");
        thinkingLevelCombo.addActionListener(e -> rememberThinkingLevelSelection());

        content().add(new JLabel("Thinking level:"), BorderLayout.WEST);
        content().add(thinkingLevelCombo, BorderLayout.CENTER);
    }

    @Override
    public void load(PiSessionSettings settings) {
        super.load(settings);
        String level = settings.thinkingLevel();
        if (level == null || level.isBlank()) {
            level = lastSelectedThinkingLevel;
        }
        if (level == null || level.isBlank()) {
            level = PiPluginSettings.getThinkingLevel();
        }
        boolean wasProgrammatic = programmatic;
        programmatic = true;
        try {
            thinkingLevelCombo.setSelectedItem((level == null || level.isBlank()) ? BlankSafeComboRenderer.DEFAULT_OPTION : level);
        }
        finally {
            programmatic = wasProgrammatic;
        }
    }

    @Override
    public void applyTo(PiSessionSettings settings) {
        super.applyTo(settings);
        Object sel = thinkingLevelCombo.getSelectedItem();
        settings.setThinkingLevel((sel != null && !BlankSafeComboRenderer.DEFAULT_OPTION.equals(sel.toString())) ? sel.toString() : null);
    }

    @Override
    protected List<String> knownModels() {
        return Arrays.asList(PiPluginSettings.getKnownModels());
    }

    @Override
    protected String defaultModel() {
        return PiPluginSettings.getModel();
    }

    @Override
    public void startLoading() {
        // No PiAiImplementation.triggerModelDiscovery() wrapper exists (unlike Claude's) — PiModelDiscovery already
        // owns publishing into the catalog this panel listens on, so call it directly rather than adding a
        // cross-package indirection that would just forward to the same place.
        PiModelDiscovery.discoverAsync(PiExecutableLocator.locate());
    }

    private void rememberThinkingLevelSelection() {
        if (programmatic) {
            return;
        }
        Object sel = thinkingLevelCombo.getSelectedItem();
        lastSelectedThinkingLevel = (sel != null && !BlankSafeComboRenderer.DEFAULT_OPTION.equals(sel.toString())) ? sel.toString() : null;
    }
}

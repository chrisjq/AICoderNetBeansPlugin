package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings;

import java.awt.BorderLayout;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;

/**
 * Session-create panel for OpenCode: model, plus the agent mode (build/plan)
 * that OpenCode alone offers. The mode control goes in the base panel's
 * {@link #content()} area, so this extends the shared panel rather than
 * wrapping it.
 */
public final class OpenCodeCreateSettingsPanel extends ModelCreateSettingsPanel<OpenCodeSessionSettings> {

    private static final String[] MODE_OPTIONS = {"build", "plan"};
    static volatile String lastSelectedMode = null;

    private final JComboBox<String> modeCombo;

    public OpenCodeCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.OPENCODE, catalog, OpenCodeSessionSettings::model, OpenCodeSessionSettings::setModel);
        modeCombo = new JComboBox<>(MODE_OPTIONS);
        modeCombo.setEditable(true);
        modeCombo.setToolTipText("Default agent mode for new sessions");
        modeCombo.addActionListener(e -> rememberModeSelection());

        content().add(new JLabel("Mode:"), BorderLayout.WEST);
        content().add(modeCombo, BorderLayout.CENTER);
    }

    @Override
    public void load(OpenCodeSessionSettings settings) {
        super.load(settings);
        String m = settings.mode();
        if (m == null || m.isBlank()) {
            m = lastSelectedMode;
        }
        if (m == null || m.isBlank()) {
            m = OpenCodePluginSettings.getMode();
        }
        modeCombo.setSelectedItem(m);
    }

    @Override
    public void applyTo(OpenCodeSessionSettings settings) {
        super.applyTo(settings);
        Object sel = modeCombo.getSelectedItem();
        settings.setMode(sel != null && !sel.toString().isBlank()
                ? sel.toString() : OpenCodePluginSettings.getMode());
    }

    @Override
    protected List<String> knownModels() {
        return Arrays.asList(OpenCodePluginSettings.getKnownModels());
    }

    @Override
    protected String defaultModel() {
        return OpenCodePluginSettings.getModel();
    }

    @Override
    public void startLoading() {
        OpenCodeAiImplementation.triggerModelDiscovery();
    }

    private void rememberModeSelection() {
        Object sel = modeCombo.getSelectedItem();
        lastSelectedMode = (sel != null && !sel.toString().isBlank()) ? sel.toString() : null;
    }
}

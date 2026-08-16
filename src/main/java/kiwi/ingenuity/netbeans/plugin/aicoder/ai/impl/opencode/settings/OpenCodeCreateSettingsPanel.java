package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings;

import java.awt.BorderLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;

public final class OpenCodeCreateSettingsPanel implements AiSessionCreateSettingsPanel<OpenCodeSessionSettings> {

    private static final String[] MODE_OPTIONS = {"build", "plan"};
    static volatile String lastSelectedMode = null;

    private final JPanel outer;
    private final JComboBox<String> modeCombo;
    private final ModelCreateSettingsPanel<OpenCodeSessionSettings> model;

    public OpenCodeCreateSettingsPanel(AiModelCatalog catalog) {
        model = new ModelCreateSettingsPanel<>(AiTypeEnum.OPENCODE, catalog, OpenCodeSessionSettings::model, OpenCodeSessionSettings::setModel);
        modeCombo = new JComboBox<>(MODE_OPTIONS);
        modeCombo.setEditable(true);
        modeCombo.setToolTipText("Default agent mode for new sessions");
        modeCombo.addActionListener(e -> rememberModeSelection());

        JPanel modeRow = new JPanel(new BorderLayout(6, 0));
        modeRow.add(new JLabel("Mode:"), BorderLayout.WEST);
        modeRow.add(modeCombo, BorderLayout.CENTER);

        outer = new JPanel(new BorderLayout(0, 4));
        outer.add(model.component(), BorderLayout.NORTH);
        outer.add(modeRow, BorderLayout.CENTER);
    }

    @Override
    public JComponent component() {
        return outer;
    }

    @Override
    public void load(OpenCodeSessionSettings settings) {
        model.load(settings);
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
        model.applyTo(settings);
        Object sel = modeCombo.getSelectedItem();
        settings.setMode(sel != null && !sel.toString().isBlank()
                ? sel.toString() : OpenCodePluginSettings.getMode());
    }

    @Override
    public void startLoading() {
        OpenCodeAiImplementation.triggerModelDiscovery();
    }

    @Override
    public void dispose() {
        model.dispose();
    }

    private void rememberModeSelection() {
        Object sel = modeCombo.getSelectedItem();
        lastSelectedMode = (sel != null && !sel.toString().isBlank()) ? sel.toString() : null;
    }
}

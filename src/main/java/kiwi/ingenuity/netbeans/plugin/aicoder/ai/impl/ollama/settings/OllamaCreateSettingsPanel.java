package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import java.awt.BorderLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.OllamaAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;

/**
 * Local Ollama create settings include both model and session base URL.
 */
public final class OllamaCreateSettingsPanel implements AiSessionCreateSettingsPanel<OllamaSessionSettings> {

    private final JPanel panel = new JPanel(new BorderLayout(6, 4));
    private final ModelCreateSettingsPanel<OllamaSessionSettings> model;
    private final JTextField baseUrl = new JTextField(28);

    public OllamaCreateSettingsPanel(AiModelCatalog catalog) {
        model = new ModelCreateSettingsPanel<>(AiTypeEnum.OLLAMA_LOCAL, catalog, OllamaSessionSettings::model, OllamaSessionSettings::setModel);
        panel.add(model.component(), BorderLayout.NORTH);
        JPanel url = new JPanel(new BorderLayout(6, 0));
        url.add(new JLabel("Base URL:"), BorderLayout.WEST);
        url.add(baseUrl, BorderLayout.CENTER);
        panel.add(url, BorderLayout.CENTER);
    }

    @Override
    public JComponent component() {
        return panel;
    }

    @Override
    public void load(OllamaSessionSettings settings) {
        model.load(settings);
        String url = settings.baseUrl() == null ? "" : settings.baseUrl();
        baseUrl.setText(url);
    }

    @Override
    public void startLoading() {
        OllamaAiImplementation.triggerModelDiscovery(baseUrl.getText().trim());
    }

    @Override
    public void applyTo(OllamaSessionSettings settings) {
        model.applyTo(settings);
        String value = baseUrl.getText().trim();
        settings.setBaseUrl(value.isEmpty() ? null : value);
    }

    @Override
    public void dispose() {
        model.dispose();
    }
}

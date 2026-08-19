package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import java.awt.BorderLayout;
import java.util.Arrays;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JTextField;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.OllamaAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;

/**
 * Local Ollama create settings include both model and session base URL. The URL
 * field goes in the base panel's {@link #content()} area, so this extends the
 * shared panel rather than wrapping it.
 */
public final class OllamaCreateSettingsPanel extends ModelCreateSettingsPanel<OllamaSessionSettings> {

    private final JTextField baseUrl = new JTextField(28);

    public OllamaCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.OLLAMA_LOCAL, catalog, OllamaSessionSettings::model, OllamaSessionSettings::setModel);
        content().add(new JLabel("Base URL:"), BorderLayout.WEST);
        content().add(baseUrl, BorderLayout.CENTER);
    }

    @Override
    public void load(OllamaSessionSettings settings) {
        super.load(settings);
        String url = settings.baseUrl() == null ? "" : settings.baseUrl();
        baseUrl.setText(url);
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
    }
}

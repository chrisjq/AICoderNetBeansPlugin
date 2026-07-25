package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.io.IOException;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ConfigTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.TemplatePersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiSessionConfigPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiSessionConfigPanelMode;

public final class ConfigTemplatesPanel extends TemplatePanel<ConfigTemplate> {

    private final AiSessionConfigPanel config;

    public ConfigTemplatesPanel(TemplatePersistenceManager templates, Runnable onTemplateChanged) {
        this(new AiSessionConfigPanel(AiSessionConfigPanelMode.TEMPLATE), templates, onTemplateChanged);
    }

    private ConfigTemplatesPanel(AiSessionConfigPanel config, TemplatePersistenceManager templates, Runnable onTemplateChanged) {
        super(config, templates, onTemplateChanged,
                new String[]{"Name", "Updated", "Created"},
                ConfigTemplate::name,
                ConfigTemplate::updatedAt,
                ConfigTemplate::createdAt);
        this.config = config;
        initPanel();
    }

    @Override
    protected List<ConfigTemplate> loadData() throws IOException {
        return templates.saveConfigDefaultsIfEmpty();
    }

    @Override
    protected void saveData(ConfigTemplate old, String name) throws IOException {
        templates.save(old == null
                ? ConfigTemplate.create(name, config.snapshot())
                : old.withNameAndSettings(name, config.snapshot()));
    }

    @Override
    protected void deleteData(ConfigTemplate value) throws IOException {
        templates.deleteConfigTemplate(value.id());
    }

    @Override
    protected void loadEditor(ConfigTemplate value) {
        if (config != null && value != null && value.settings() != null) {
            config.loadSession(value.settings());
        }
    }

    @Override
    protected void clearEditor() {
        super.clearEditor();
        if (config != null) {
            config.loadSession(new AiSessionSettings());
        }
    }
}

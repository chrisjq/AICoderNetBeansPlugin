package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ConfigTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.TemplatePersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiSessionConfigPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiSessionConfigPanelMode;

public final class ConfigTemplatesPanel extends TemplatePanel<ConfigTemplate> {

    private static final Logger LOG = Logger.getLogger(ConfigTemplatesPanel.class.getName());

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

    /**
     * The Default from the last load, for seeding "New" and the empty editor without going back to the store. Written
     * on the store thread, read on the event dispatch thread.
     */
    private volatile ConfigTemplate loadedDefault;

    /**
     * Runs on the store thread: no Swing here.
     */
    @Override
    protected List<ConfigTemplate> loadData() throws IOException {
        List<ConfigTemplate> loaded = new ArrayList<>(templates.saveConfigDefaultsIfEmpty());
        loaded.sort(Comparator.comparing(ConfigTemplate::isDefault).reversed()
                .thenComparing(ConfigTemplate::name, String.CASE_INSENSITIVE_ORDER));
        loadedDefault = loaded.stream().filter(ConfigTemplate::isDefault).findFirst().orElse(null);
        return loaded;
    }

    @Override
    protected void saveData(ConfigTemplate old, String name) throws IOException {
        prepareSave(old, name).run();
    }

    /**
     * The editor is read here, on the event dispatch thread; only the store write is deferred to the store thread.
     */
    @Override
    protected StoreWrite prepareSave(ConfigTemplate old, String name) {
        ConfigTemplate value = old == null
                               ? ConfigTemplate.create(name, config.snapshot())
                               : old.withNameAndSettings(name, config.snapshot());
        return () -> templates.save(value);
    }

    @Override
    protected void deleteData(ConfigTemplate value) throws IOException {
        templates.deleteConfigTemplate(value.id());
    }

    @Override
    protected boolean canDelete(ConfigTemplate value) {
        return value != null && !value.isDefault();
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
            config.loadSession(defaultSeedSettings());
        }
    }

    /**
     * What a new template starts from: the Default as of the last load. Until the first load completes (or if it
     * failed) the editor is seeded from a blank settings object, which reads the legacy globals — it is reseeded from
     * the real Default as soon as a load succeeds.
     */
    private AiSessionSettings defaultSeedSettings() {
        ConfigTemplate cached = loadedDefault;
        if (cached != null) {
            AiSessionSettings settings = cached.settings();
            if (settings != null) {
                return settings;
            }
            LOG.log(Level.WARNING, "The Default config template has no settings; seeding from the global defaults");
        }
        return new AiSessionSettings();
    }
}

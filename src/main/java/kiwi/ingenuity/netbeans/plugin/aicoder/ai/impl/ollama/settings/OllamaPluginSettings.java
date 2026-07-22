package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import org.openide.util.NbPreferences;

public final class OllamaPluginSettings {

    public static final String DEFAULT_MODEL = "qwen2.5-coder:7b";
    public static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private static volatile String[] discoveredModels = null;

    private static Preferences prefs() {
        return NbPreferences.forModule(PluginSettings.class);
    }

    public static String getModel() {
        return prefs().get(OllamaPluginSettingsKeyEnum.MODEL.key(), DEFAULT_MODEL);
    }

    public static void setModel(String value) {
        prefs().put(OllamaPluginSettingsKeyEnum.MODEL.key(),
                value != null ? value : DEFAULT_MODEL);
    }

    public static String getBaseUrl() {
        return prefs().get(OllamaPluginSettingsKeyEnum.BASE_URL.key(), DEFAULT_BASE_URL);
    }

    public static void setBaseUrl(String value) {
        prefs().put(OllamaPluginSettingsKeyEnum.BASE_URL.key(),
                value != null ? value : DEFAULT_BASE_URL);
    }

    public static String[] getKnownModels() {
        String[] models = discoveredModels;
        return (models != null && models.length > 0)
                ? models
                : new String[]{DEFAULT_MODEL};
    }

    public static void setDiscoveredModels(String[] models) {
        discoveredModels = models;
    }

    private OllamaPluginSettings() {
    }
}

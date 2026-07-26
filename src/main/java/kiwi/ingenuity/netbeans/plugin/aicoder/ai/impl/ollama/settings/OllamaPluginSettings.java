package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import org.openide.util.NbPreferences;

public final class OllamaPluginSettings {

    // Ollama only serves models the user has pulled locally, so this list is just
    // the pre-discovery fallback shown before `ollama list` reports the real set;
    // getKnownModels() swaps in the discovered models once available. The combo is
    // editable, so a model that is installed but not listed here can still be typed.
    public static final String[] KNOWN_MODELS = {
        "qwen2.5-coder:7b"
    };
    public static final String DEFAULT_MODEL = KNOWN_MODELS[0];
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
        String[] d = discoveredModels;
        return (d != null && d.length > 0) ? d : KNOWN_MODELS;
    }

    public static void setDiscoveredModels(String[] models) {
        discoveredModels = models;
    }

    private OllamaPluginSettings() {
    }
}

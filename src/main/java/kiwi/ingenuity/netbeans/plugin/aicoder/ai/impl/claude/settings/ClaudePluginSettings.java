package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings;

import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import org.openide.util.NbPreferences;

public final class ClaudePluginSettings {

    // Highest first: the default is the first entry, so refreshing this list keeps the default at the top model.
    // Used until discovery replaces it; the model combo is editable, so any other id can still be typed.
    public static final String[] KNOWN_MODELS = {
        "claude-opus-5-5",
        "claude-sonnet-5-5",
        "claude-haiku-4-5"
    };
    public static final String DEFAULT_MODEL = KNOWN_MODELS[0];
    /**
     * An empty effort means "use Claude's own default", not "unset" — the launch command omits
     * {@code --effort} in that case.
     */
    public static final String DEFAULT_EFFORT = "";

    private static volatile String[] discoveredModels = null;

    private static Preferences prefs() {
        return NbPreferences.forModule(PluginSettings.class);
    }

    public static String[] getKnownModels() {
        String[] d = discoveredModels;
        return (d != null && d.length > 0) ? d : KNOWN_MODELS;
    }

    public static void setDiscoveredModels(String[] models) {
        discoveredModels = models;
    }

    public static String getExecutable() {
        return prefs().get(ClaudePluginSettingsKeyEnum.EXECUTABLE.key(), "");
    }

    public static void setExecutable(String v) {
        prefs().put(ClaudePluginSettingsKeyEnum.EXECUTABLE.key(), v);
    }

    public static String getModel() {
        return prefs().get(ClaudePluginSettingsKeyEnum.MODEL.key(), DEFAULT_MODEL);
    }

    public static void setModel(String v) {
        prefs().put(ClaudePluginSettingsKeyEnum.MODEL.key(), v);
    }

    public static String getEffort() {
        return prefs().get(ClaudePluginSettingsKeyEnum.EFFORT.key(), DEFAULT_EFFORT);
    }

    public static void setEffort(String v) {
        prefs().put(ClaudePluginSettingsKeyEnum.EFFORT.key(), v != null ? v : DEFAULT_EFFORT);
    }

    private ClaudePluginSettings() {
    }
}

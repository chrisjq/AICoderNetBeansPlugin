package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings;

import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudePluginSettingsKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import org.openide.util.NbPreferences;

public final class ClaudePluginSettings {

    public static final String[] KNOWN_MODELS = {
        "claude-opus-4-8",
        "claude-sonnet-4-6",
        "claude-haiku-4-5"
    };
    public static final String DEFAULT_MODEL = "claude-sonnet-4-6";

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

    private ClaudePluginSettings() {
    }
}

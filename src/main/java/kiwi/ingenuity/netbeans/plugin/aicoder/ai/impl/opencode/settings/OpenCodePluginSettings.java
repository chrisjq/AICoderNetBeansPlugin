package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings;

import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import org.openide.util.NbPreferences;

public final class OpenCodePluginSettings {

    public static final String[] KNOWN_MODELS = {
        "opencode/big-pickle"
    };
    public static final String DEFAULT_MODEL = KNOWN_MODELS[0];

    private static volatile String[] discoveredModels = null;
    public static final String DEFAULT_MODE = "build";

    private static Preferences prefs() {
        return NbPreferences.forModule(PluginSettings.class);
    }

    public static String[] getKnownModels() {
        String[] d = discoveredModels;
        if (d != null && d.length > 0) {
            return d;
        }
        // OpenCode cannot re-discover models without spawning a new process; persist
        // the list from the last session/new response so the create-dialog is useful
        // even when no session has started this IDE run.
        String persisted = prefs().get(OpenCodePluginSettingsKeyEnum.DISCOVERED_MODELS.key(), "");
        if (!persisted.isEmpty()) {
            return persisted.split(",", -1);
        }
        return KNOWN_MODELS;
    }

    public static void setDiscoveredModels(String[] models) {
        discoveredModels = models;
        if (models != null && models.length > 0) {
            prefs().put(OpenCodePluginSettingsKeyEnum.DISCOVERED_MODELS.key(), String.join(",", models));
        }
        else {
            prefs().remove(OpenCodePluginSettingsKeyEnum.DISCOVERED_MODELS.key());
        }
    }

    public static String getExecutable() {
        return prefs().get(OpenCodePluginSettingsKeyEnum.EXECUTABLE.key(), "");
    }

    public static void setExecutable(String v) {
        prefs().put(OpenCodePluginSettingsKeyEnum.EXECUTABLE.key(), v);
    }

    public static String getModel() {
        return prefs().get(OpenCodePluginSettingsKeyEnum.MODEL.key(), DEFAULT_MODEL);
    }

    public static void setModel(String v) {
        prefs().put(OpenCodePluginSettingsKeyEnum.MODEL.key(), v);
    }

    public static String getMode() {
        return prefs().get(OpenCodePluginSettingsKeyEnum.MODE.key(), DEFAULT_MODE);
    }

    public static void setMode(String v) {
        prefs().put(OpenCodePluginSettingsKeyEnum.MODE.key(), v);
    }

    private OpenCodePluginSettings() {
    }
}

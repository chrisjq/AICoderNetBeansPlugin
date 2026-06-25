package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.settings;

import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.GithubCopilotPluginSettingsKeyEnum;
import org.openide.util.NbPreferences;

public final class GithubCopilotPluginSettings {

    // "auto" lets Copilot pick an available model and always works. The rest are
    // common Copilot models but availability is account/plan-specific; the combo
    // is editable so users can type any model they have. The process manager
    // falls back to "auto" automatically if the CLI rejects a chosen model.
    public static final String[] KNOWN_MODELS = {
        "auto",
        "claude-sonnet-4.5",
        "claude-haiku-4.5",
        "gpt-5-mini"
    };
    public static final String DEFAULT_MODEL = "auto";

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
        return prefs().get(GithubCopilotPluginSettingsKeyEnum.EXECUTABLE.key(), "");
    }

    public static void setExecutable(String v) {
        prefs().put(GithubCopilotPluginSettingsKeyEnum.EXECUTABLE.key(), v != null ? v : "");
    }

    public static String getModel() {
        return prefs().get(GithubCopilotPluginSettingsKeyEnum.MODEL.key(), DEFAULT_MODEL);
    }

    public static void setModel(String v) {
        prefs().put(GithubCopilotPluginSettingsKeyEnum.MODEL.key(), v != null ? v : DEFAULT_MODEL);
    }

    private GithubCopilotPluginSettings() {
    }
}

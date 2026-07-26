package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings;

import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import org.openide.util.NbPreferences;

public final class GithubCopilotPluginSettings {

    // "auto" lets Copilot pick an available model and always works, so it is the
    // only pre-discovery entry — the real set is account/plan-specific and gets
    // filled in by discovery. The combo is editable, so any model the account has
    // can still be typed.
    //
    // Note: handleSessionStartFailure() switches back to "auto" when a start fails
    // with "is not available", but that does NOT cover a bad model chosen at
    // runtime. Under the SDK an unavailable model creates its session happily and
    // only fails at query time (SessionErrorEvent, errorType=query), so the user
    // sees the error and has to pick another model themselves. That fallback dates
    // from the old one-shot `copilot -p` implementation, where the model was passed
    // per invocation and a bad one failed the launch.
    public static final String[] KNOWN_MODELS = {
        "auto"
    };
    public static final String DEFAULT_MODEL = KNOWN_MODELS[0];

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

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings;

import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import org.openide.util.NbPreferences;

public final class GrokPluginSettings {

    // Known model ids for the "grok" CLI's -m/--model flag, confirmed against
    // xAI's published model/pricing list (https://docs.x.ai/developers/models)
    // rather than guessed. grok-4.5 is xAI's current recommendation for both
    // code and chat use cases. The combo box in GrokAiSettingsTab is editable,
    // so any model id accepted by the CLI can still be typed in even if it is
    // not listed here — and GrokModelDiscovery replaces this list at runtime
    // with the account's real available models via `grok models` when that
    // succeeds.
    public static final String[] KNOWN_MODELS = {
        "grok-4.5",
        "grok-4.3",
        "grok-4.20-0309-reasoning",
        "grok-4.20-0309-non-reasoning",
        "grok-4.20-multi-agent-0309",
        "grok-build-0.1"
    };
    public static final String DEFAULT_MODEL = "grok-4.5";

    private static Preferences prefs() {
        return NbPreferences.forModule(PluginSettings.class);
    }

    public static String getModel() {
        return prefs().get(GrokPluginSettingsKeyEnum.MODEL.key(), DEFAULT_MODEL);
    }

    public static void setModel(String v) {
        prefs().put(GrokPluginSettingsKeyEnum.MODEL.key(), v != null ? v : DEFAULT_MODEL);
    }

    public static String getExecutable() {
        return prefs().get(GrokPluginSettingsKeyEnum.EXECUTABLE.key(), "");
    }

    public static void setExecutable(String v) {
        prefs().put(GrokPluginSettingsKeyEnum.EXECUTABLE.key(), v != null ? v : "");
    }

    private GrokPluginSettings() {
    }
}

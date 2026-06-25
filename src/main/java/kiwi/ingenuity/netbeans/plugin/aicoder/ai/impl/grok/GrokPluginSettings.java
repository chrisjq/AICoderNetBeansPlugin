package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import org.openide.util.NbPreferences;

public final class GrokPluginSettings {

    public static final String[] KNOWN_MODELS = {
        "grok-2-latest",
        "grok-2",
        "grok-beta"
    };
    public static final String DEFAULT_MODEL = "grok-2-latest";

    private static Preferences prefs() {
        return NbPreferences.forModule(PluginSettings.class);
    }

    public static String getApiKey() {
        return prefs().get(GrokPluginSettingsKeyEnum.API_KEY.key(), "");
    }

    public static void setApiKey(String v) {
        prefs().put(GrokPluginSettingsKeyEnum.API_KEY.key(), v != null ? v : "");
    }

    public static String getModel() {
        return prefs().get(GrokPluginSettingsKeyEnum.MODEL.key(), DEFAULT_MODEL);
    }

    public static void setModel(String v) {
        prefs().put(GrokPluginSettingsKeyEnum.MODEL.key(), v != null ? v : DEFAULT_MODEL);
    }

    private GrokPluginSettings() {
    }
}

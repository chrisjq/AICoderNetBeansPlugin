package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings;

import java.io.File;
import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import org.openide.util.NbPreferences;

/**
 * Global pi settings. Persisted preferences, not per-session state (see {@code PiSessionSettings}).
 *
 * <p>
 * An empty {@link #getModel()} / {@link #getThinkingLevel()} means "use pi's own default", not "unset" — the launch
 * command simply omits {@code --model}/{@code --thinking} in that case, per the spec's *Model and thinking-level
 * pickers* section.
 */
public final class PiPluginSettings {

    public static final String DEFAULT_MODEL = "";
    public static final String DEFAULT_THINKING_LEVEL = "";
    private static final String DISCOVERED_MODELS_DELIMITER = "\n";

    private static volatile String[] discoveredModelsCache = null;

    private static Preferences prefs() {
        return NbPreferences.forModule(PluginSettings.class);
    }

    public static String getExecutable() {
        return prefs().get(PiPluginSettingsKeyEnum.EXECUTABLE.key(), "");
    }

    public static void setExecutable(String v) {
        prefs().put(PiPluginSettingsKeyEnum.EXECUTABLE.key(), v != null ? v : "");
    }

    public static String getModel() {
        return prefs().get(PiPluginSettingsKeyEnum.MODEL.key(), DEFAULT_MODEL);
    }

    public static void setModel(String v) {
        prefs().put(PiPluginSettingsKeyEnum.MODEL.key(), v != null ? v : DEFAULT_MODEL);
    }

    public static String getThinkingLevel() {
        return prefs().get(PiPluginSettingsKeyEnum.THINKING_LEVEL.key(), DEFAULT_THINKING_LEVEL);
    }

    public static void setThinkingLevel(String v) {
        prefs().put(PiPluginSettingsKeyEnum.THINKING_LEVEL.key(), v != null ? v : DEFAULT_THINKING_LEVEL);
    }

    public static String getVerifiedVersion() {
        return prefs().get(PiPluginSettingsKeyEnum.VERIFIED_VERSION.key(), "");
    }

    public static void setVerifiedVersion(String v) {
        prefs().put(PiPluginSettingsKeyEnum.VERIFIED_VERSION.key(), v != null ? v : "");
    }

    /**
     * Models discovered by {@code PiModelDiscovery}, each as {@code "provider/id"}. Persisted (unlike Claude's
     * in-memory-only discovered list) so the Options tab and the new-session dialog have something to show before
     * discovery has run again in this IDE session.
     */
    public static String[] getKnownModels() {
        String[] cached = discoveredModelsCache;
        if (cached != null) {
            return cached;
        }
        String stored = prefs().get(PiPluginSettingsKeyEnum.DISCOVERED_MODELS.key(), "");
        String[] parsed = stored.isBlank() ? new String[0] : stored.split(DISCOVERED_MODELS_DELIMITER);
        discoveredModelsCache = parsed;
        return parsed;
    }

    public static void setDiscoveredModels(String[] models) {
        String[] safe = models != null ? models : new String[0];
        discoveredModelsCache = safe;
        prefs().put(PiPluginSettingsKeyEnum.DISCOVERED_MODELS.key(), String.join(DISCOVERED_MODELS_DELIMITER, safe));
    }

    /**
     * The executable-path rule shared by {@code PiAiSettingsTab.isValid()}: an empty path is valid (auto-detect at
     * launch), an absolute path must point at an existing file, and a relative path is always accepted (resolved
     * against PATH at launch time).
     */
    public static boolean isValidExecutablePath(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        File f = new File(path);
        return !f.isAbsolute() || f.isFile();
    }

    private PiPluginSettings() {
    }
}

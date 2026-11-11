package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.CodexAiImplementation;
import org.openide.util.NbPreferences;

/**
 * Executable path, model and reasoning-effort defaults. Sandbox/approval defaults live in {@code CodexAiProcessManager}
 * (they are per-thread protocol params, not user preferences yet).
 *
 * <p>
 * Models are discovered live from the installed Codex CLI. The lower-case {@value #AUTO_MODEL} entry represents an
 * omitted model, allowing Codex to choose its own default.
 */
public final class CodexPluginSettings {

    public static final String AUTO_MODEL = "auto";

    private static final Logger LOG = Logger.getLogger(CodexPluginSettings.class.getName());
    private static volatile Preferences testPreferences;
    /**
     * An empty model lets the Codex CLI choose its own default.
     */
    public static final String DEFAULT_MODEL = "";

    /**
     * An empty effort means "use the model's own default" — the {@code effort} field is simply omitted from
     * {@code turn/start}.
     */
    public static final String DEFAULT_EFFORT = "";

    private static Preferences prefs() {
        Preferences test = testPreferences;
        return test != null ? test : NbPreferences.forModule(PluginSettings.class);
    }

    public static String getExecutable() {
        return prefs().get(CodexPluginSettingsKeyEnum.EXECUTABLE.key(), "");
    }

    public static void setExecutable(String v) {
        prefs().put(CodexPluginSettingsKeyEnum.EXECUTABLE.key(), v);
    }

    /**
     * The models the Codex CLI last reported: the live catalogue when it has a list, else the list persisted from the
     * last successful discovery. A cache of what the CLI reported, never a hard-coded list; empty before any discovery.
     */
    public static String[] getKnownModels() {
        List<String> live = CodexAiImplementation.modelCatalog().getCachedModels();
        return (live.isEmpty() ? getDiscoveredModels() : live).toArray(String[]::new);
    }

    /**
     * The last successful discovery, persisted one model id per line; empty when none has succeeded yet.
     */
    public static List<String> getDiscoveredModels() {
        String stored = prefs().get(CodexPluginSettingsKeyEnum.DISCOVERED_MODELS.key(), "");
        return stored.isEmpty() ? List.of() : List.copyOf(Arrays.asList(stored.split("\n")));
    }

    public static void setDiscoveredModels(List<String> models) {
        List<String> kept = new ArrayList<>(models);
        String joined = String.join("\n", kept);
        while (joined.length() > Preferences.MAX_VALUE_LENGTH && !kept.isEmpty()) {
            kept.remove(kept.size() - 1);
            joined = String.join("\n", kept);
        }
        try {
            prefs().put(CodexPluginSettingsKeyEnum.DISCOVERED_MODELS.key(), joined);
        }
        catch (IllegalArgumentException ex) {
            LOG.log(Level.FINE, "Could not persist the discovered Codex models", ex);
        }
    }

    /**
     * The stored form of a typed or chosen model. {@code auto} (any case) is kept as {@link #AUTO_MODEL}, so a session
     * that chose auto does not fall back to the global default; blank stays {@link #DEFAULT_MODEL}, meaning "not set".
     */
    public static String toStoredModel(String model) {
        if (model == null || model.isBlank()) {
            return DEFAULT_MODEL;
        }
        return AUTO_MODEL.equalsIgnoreCase(model.trim()) ? AUTO_MODEL : model.trim();
    }

    public static String toDisplayModel(String model) {
        return model == null || model.isBlank() ? AUTO_MODEL : model;
    }

    public static String getModel() {
        return prefs().get(CodexPluginSettingsKeyEnum.MODEL.key(), DEFAULT_MODEL);
    }

    public static void setModel(String v) {
        prefs().put(CodexPluginSettingsKeyEnum.MODEL.key(), toStoredModel(v));
    }

    /**
     * Global reasoning-effort default for new Codex sessions; empty means "model default".
     */
    public static String getEffort() {
        return prefs().get(CodexPluginSettingsKeyEnum.EFFORT.key(), DEFAULT_EFFORT);
    }

    public static void setEffort(String v) {
        prefs().put(CodexPluginSettingsKeyEnum.EFFORT.key(), v != null ? v : DEFAULT_EFFORT);
    }

    /**
     * Tests only. Points the settings at a test-owned preferences node (null restores the real one), so tests never
     * write the user's Codex settings.
     */
    public static void useForTests(Preferences preferences) {
        testPreferences = preferences;
    }

    private CodexPluginSettings() {
    }
}

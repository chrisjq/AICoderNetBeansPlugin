package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings;

import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import org.openide.util.NbPreferences;

/**
 * Executable path, model and reasoning-effort defaults. Sandbox/approval defaults live in {@code CodexAiProcessManager}
 * (they are per-thread protocol params, not user preferences yet).
 *
 * <p>
 * Model slugs: the {@code app-server} does expose a {@code model/list} RPC (codex-cli 0.155.0) and it is used for
 * per-turn reasoning-effort discovery ({@code supportedReasoningEfforts}/{@code defaultReasoningEffort} per model). The
 * model <em>picker</em> however is not yet wired to live discovery — that stays a follow-up — so the slugs below remain
 * a small hardcoded snapshot (read from {@code ~/.codex/models_cache.json}, filtering out {@code codex-auto-review});
 * {@code ModelCreateSettingsPanel}'s combo is editable, so free text still works for any model this list doesn't know
 * about.
 */
public final class CodexPluginSettings {

    public static final String[] KNOWN_MODELS = {
        "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5", "gpt-5.4-mini"
    };
    /**
     * Matches what the live binary itself defaults to when no model is given.
     */
    public static final String DEFAULT_MODEL = KNOWN_MODELS[0];

    /**
     * An empty effort means "use the model's own default" — the {@code effort} field is simply omitted from
     * {@code turn/start}.
     */
    public static final String DEFAULT_EFFORT = "";

    private static Preferences prefs() {
        return NbPreferences.forModule(PluginSettings.class);
    }

    public static String getExecutable() {
        return prefs().get(CodexPluginSettingsKeyEnum.EXECUTABLE.key(), "");
    }

    public static void setExecutable(String v) {
        prefs().put(CodexPluginSettingsKeyEnum.EXECUTABLE.key(), v);
    }

    public static String[] getKnownModels() {
        return KNOWN_MODELS;
    }

    public static String getModel() {
        return prefs().get(CodexPluginSettingsKeyEnum.MODEL.key(), DEFAULT_MODEL);
    }

    public static void setModel(String v) {
        prefs().put(CodexPluginSettingsKeyEnum.MODEL.key(), v);
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

    private CodexPluginSettings() {
    }
}

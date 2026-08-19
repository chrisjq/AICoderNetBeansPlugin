package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings;

import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import org.openide.util.NbPreferences;

/**
 * Executable path and model default. Sandbox/approval defaults live in
 * {@code CodexAiProcessManager} (they are per-thread protocol params, not user
 * preferences yet) and the settings tab arrives in a later slice (design doc
 * §6, §9).
 *
 * <p>
 * Unlike OpenCode, there is no {@code app-server} RPC that enumerates models —
 * checked the 246 generated schemas, nothing like a {@code model/list} method
 * exists, and {@code codex --help}/{@code codex exec --help} only expose a
 * free-text {@code -m/--model} flag. The slugs below were read from
 * {@code ~/.codex/models_cache.json} (a client-side cache of a remote catalog,
 * confirmed by its {@code fetched_at}/{@code etag} fields) with {@code
 * "visibility":"list"} — filtering out {@code codex-auto-review}, which is
 * marked {@code "visibility":"hide"} and is not a user-selectable chat model.
 * That cache file is an internal implementation detail with no stability
 * guarantee, so this is a small hardcoded snapshot, not a live read of it;
 * {@code ModelCreateSettingsPanel}'s combo is editable, so free text still
 * works for any model this list doesn't know about.
 */
public final class CodexPluginSettings {

    public static final String[] KNOWN_MODELS = {
        "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5", "gpt-5.4-mini"
    };
    /**
     * Matches what the live binary itself defaults to when no model is given.
     */
    public static final String DEFAULT_MODEL = KNOWN_MODELS[0];

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

    private CodexPluginSettings() {
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings;

import java.util.List;
import java.util.Map;
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

    /**
     * Global default reasoning effort. An empty string (the persisted sentinel — {@code Preferences} cannot store
     * {@code null}) means "not set": {@code GithubCopilotProcessManager} omits {@code SessionConfig}/
     * {@code ResumeSessionConfig}'s {@code setReasoningEffort} entirely rather than sending a value.
     */
    public static final String DEFAULT_REASONING_EFFORT = "";

    public static String getReasoningEffort() {
        return prefs().get(GithubCopilotPluginSettingsKeyEnum.REASONING_EFFORT.key(), DEFAULT_REASONING_EFFORT);
    }

    public static void setReasoningEffort(String v) {
        prefs().put(GithubCopilotPluginSettingsKeyEnum.REASONING_EFFORT.key(), v != null ? v : DEFAULT_REASONING_EFFORT);
    }

    /**
     * Live per-model reasoning-effort support, populated by {@code GithubCopilotModelDiscovery} as a side effect of its
     * SDK-tier discovery ({@code ModelInfo.getSupportedReasoningEfforts()}/{@code getDefaultReasoningEffort()}).
     * In-memory only, not persisted (like the model list, this is fresh per IDE run) — empty until discovery has
     * actually completed at least once, and empty for any model discovery never reported data for (e.g. the direct
     * JSON-RPC fallback tier, which does not carry this). Nothing about effort levels is ever hardcoded: a model with
     * no entry here is treated as "no support".
     */
    private static volatile Map<String, List<String>> supportedReasoningEffortsByModel = Map.of();
    private static volatile Map<String, String> defaultReasoningEffortByModel = Map.of();

    /**
     * The reasoning-effort levels {@code modelId} supports, or an empty list if the model is unknown or reported none —
     * both cases mean "no support" to every caller (there is no live/static-table distinction to make here).
     */
    public static List<String> getSupportedReasoningEfforts(String modelId) {
        if (modelId == null) {
            return List.of();
        }
        return supportedReasoningEffortsByModel.getOrDefault(modelId, List.of());
    }

    public static String getDefaultReasoningEffort(String modelId) {
        return modelId == null ? null : defaultReasoningEffortByModel.get(modelId);
    }

    public static void setModelReasoningEffortInfo(Map<String, List<String>> supportedByModel, Map<String, String> defaultByModel) {
        supportedReasoningEffortsByModel = supportedByModel != null ? Map.copyOf(supportedByModel) : Map.of();
        defaultReasoningEffortByModel = defaultByModel != null ? Map.copyOf(defaultByModel) : Map.of();
    }

    private GithubCopilotPluginSettings() {
    }
}

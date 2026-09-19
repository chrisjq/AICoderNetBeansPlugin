package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.util.List;
import java.util.Map;

/**
 * Per-model reasoning-effort support table (xAI CLI {@code --reasoning-effort}, {@code grok --help}). A model not
 * listed here supports no reasoning-effort levels at all — {@link #supportedFor} returns an empty list and callers must
 * never pass the flag for it.
 */
public final class GrokReasoningEffortSupport {

    private static final Map<String, List<String>> SUPPORTED_BY_MODEL = Map.of(
            "grok-4.6", List.of("low", "medium", "high", "xhigh"),
            "grok-4.5", List.of("low", "medium", "high")
    );

    /**
     * The union of every level any known model supports — used where the live model selection is not available to
     * filter against (the session-create dialog's shared {@code ModelCreateSettingsPanel} exposes no model-change hook
     * to subclasses), so the combo offers every value the process-manager-level validation might still accept. A
     * combination invalid for the actually selected model is caught and cleared there instead, per spec.
     */
    public static List<String> allKnownLevels() {
        return List.of("low", "medium", "high", "xhigh");
    }

    /**
     * Levels {@code model} supports, or empty if the model is unlisted (or null/blank, e.g. reached before a model has
     * been resolved on the launch path) — meaning it supports none.
     */
    public static List<String> supportedFor(String model) {
        // Map.of's immutable map throws NullPointerException from get(null) — unlike HashMap, it does not just
        // return null — so this must be checked BEFORE touching the map, not after (same JDK trap hit once before on
        // the pi backend).
        if (model == null || model.isBlank()) {
            return List.of();
        }
        List<String> supported = SUPPORTED_BY_MODEL.get(model);
        return supported != null ? supported : List.of();
    }

    private GrokReasoningEffortSupport() {
    }
}

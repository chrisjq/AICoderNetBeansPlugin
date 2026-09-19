package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide cache of reasoning-effort capabilities per model, populated from the {@code app-server}
 * {@code model/list} responses captured when a session establishes its thread. Drives the effort combos in the
 * session-create dialog and the Options tab, where no live server is available; the info bar receives the same
 * per-session data directly via {@code CodexReasoningEffortEvent}.
 *
 * <p>
 * This is a capabilities cache (like {@code AiModelCatalog}), not per-session selection state, so the static datastore
 * is safe alongside per-session settings.
 */
public final class CodexReasoningEffortCatalog {

    private static final Map<String, List<String>> SUPPORTED_BY_MODEL = new ConcurrentHashMap<>();

    private CodexReasoningEffortCatalog() {
    }

    /**
     * Records the supported reasoning efforts an active server advertises for {@code model}. An empty list removes any
     * previous entry (the model no longer exposes capability information).
     */
    public static void cache(String model, List<String> supportedEfforts) {
        if (model == null || model.isBlank()) {
            return;
        }
        if (supportedEfforts == null || supportedEfforts.isEmpty()) {
            SUPPORTED_BY_MODEL.remove(model);
        }
        else {
            SUPPORTED_BY_MODEL.put(model, List.copyOf(supportedEfforts));
        }
    }

    /**
     * Supported reasoning efforts for {@code model}, or an empty list when nothing has been discovered (or the model
     * advertises none).
     */
    public static List<String> supportedEffortsFor(String model) {
        if (model == null) {
            return Collections.emptyList();
        }
        return SUPPORTED_BY_MODEL.getOrDefault(model, Collections.emptyList());
    }

    /**
     * Forgets every cached entry. Intended for tests.
     */
    public static void clear() {
        SUPPORTED_BY_MODEL.clear();
    }
}

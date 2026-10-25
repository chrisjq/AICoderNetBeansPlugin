package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import java.util.List;

/**
 * Resolves a reasoning effort against positively advertised model capabilities.
 */
public final class ReasoningEffortPolicy {

    private ReasoningEffortPolicy() {
    }

    public record Decision(String levelToSendOrNull, boolean clearPinned) {

    }

    /**
     * @param pinned session-pinned effort, or null when inherited
     * @param globalDefault inherited global effort, or null for the server default
     * @param advertisedLevels known non-empty model levels, or null when capability discovery is unknown
     */
    public static Decision effective(String pinned, String globalDefault, List<String> advertisedLevels) {
        String requested = normalise(pinned != null ? pinned : globalDefault);
        if (requested == null) {
            return new Decision(null, false);
        }
        if (advertisedLevels == null) {
            return new Decision(requested, false);
        }
        boolean supported = advertisedLevels.stream().map(ReasoningEffortPolicy::normalise)
                .anyMatch(requested::equals);
        if (supported) {
            return new Decision(requested, false);
        }
        return new Decision(null, pinned != null);
    }

    private static String normalise(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import java.util.Locale;

/**
 * Per-server-kind advice for raising the context window (spec §4.7): used by the "too small for tools" message, the
 * small-window hint tooltip and the truncation-guard message. The constants carry the same names as the server kinds
 * (OLLAMA, LMSTUDIO, LLAMACPP, VLLM, GENERIC), so a kind maps to its remedy by name and this enum does not depend on
 * the detector.
 */
public enum ServerRemedyEnum {
    OLLAMA("OLLAMA_CONTEXT_LENGTH or the model's num_ctx"),
    LMSTUDIO("set Context Length when loading the model"),
    LLAMACPP("start it with -c"),
    VLLM("--max-model-len"),
    GENERIC("raise it on the server");

    private final String remedy;

    ServerRemedyEnum(String remedy) {
        this.remedy = remedy;
    }

    /**
     * @return the advice text, as it appears inside the parentheses of the spec messages
     */
    public String remedy() {
        return remedy;
    }

    /**
     * Maps a server kind, given by its enum name, to its remedy. Null, blank and unknown names give {@link #GENERIC}.
     * Names are matched case-insensitively and ignoring underscores, so {@code LM_STUDIO} and {@code LMSTUDIO} agree.
     */
    public static ServerRemedyEnum forKind(String kindName) {
        if (kindName == null) {
            return GENERIC;
        }
        String normalised = kindName.trim().replace("_", "").toUpperCase(Locale.ROOT);
        for (ServerRemedyEnum value : values()) {
            if (value.name().equals(normalised)) {
                return value;
            }
        }
        return GENERIC;
    }
}

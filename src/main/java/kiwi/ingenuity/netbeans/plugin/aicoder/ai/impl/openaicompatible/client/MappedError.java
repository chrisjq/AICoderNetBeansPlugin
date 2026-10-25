package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

/**
 * A server or transport failure mapped to a stable kind and a user-facing message (spec §4.1).
 * <p>
 * The rate-limit message still contains the literal placeholder {@code N}; the client fills in the wait.
 *
 * @param kind the failure class
 * @param message user-facing text
 * @param rejectedField the optional request field the server named, or null
 * @param statedContextLimit the context limit the server stated, or null
 */
public record MappedError(Kind kind, String message, String rejectedField, Integer statedContextLimit) {

    public MappedError {
        if (kind == null) {
            throw new IllegalArgumentException("kind");
        }
        if (message == null) {
            message = "";
        }
    }

    public enum Kind {
        KEY_REJECTED,
        NO_KEY_SENT,
        KEY_REQUIRED,
        PATH_NOT_FOUND,
        MODEL_NOT_FOUND,
        RATE_LIMITED,
        QUOTA,
        REJECTED_FIELD,
        UNNAMED_REJECTION,
        TOOLS_UNSUPPORTED,
        CONTEXT_OVERFLOW,
        SERVER_ERROR,
        TLS,
        CONNECTION_FAILED
    }
}

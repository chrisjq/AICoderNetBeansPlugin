package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

/**
 * Timeouts specific to the Ollama implementation.
 */
public enum OllamaTimeoutEnum {
    OLLAMA_MODEL_DISCOVERY_MILLIS(15_000L, Kind.OPERATION);

    private final long millis;
    private final Kind kind;

    OllamaTimeoutEnum(long millis, Kind kind) {
        this.millis = millis;
        this.kind = kind;
    }

    public long millis() {
        return millis;
    }

    private enum Kind {
        OPERATION
    }
}

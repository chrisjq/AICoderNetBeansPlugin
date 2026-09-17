package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp;

/**
 * Lifecycle status of a tool call announced by a {@code tool_call} or {@code tool_call_update} session/update (design
 * doc §12). Only these four values appear in the wire vocabulary: {@code pending}/{@code in_progress} mean the call is
 * still running, {@code completed}/{@code failed} mean it has finished.
 *
 * <p>
 * Any other wire string (including statuses this plugin has never seen, such as {@code cancelled}) resolves to null via
 * {@link #fromWire} and is ignored by the caller — a count that is a little optimistic is safer than one that guesses,
 * and {@code OpenCodeAiProcessManager}'s mail-interrupt safety valve is the backstop that prevents a stuck count from
 * holding an interrupt forever.
 */
public enum AcpToolCallStatusEnum {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    FAILED("failed");

    /**
     * Resolve a wire string to its enum constant. Returns null if the string is not recognised — the protocol may add
     * values over time and the caller must not fail on unknown statuses.
     */
    public static AcpToolCallStatusEnum fromWire(String wire) {
        if (wire == null) {
            return null;
        }
        for (AcpToolCallStatusEnum v : values()) {
            if (v.wireValue.equals(wire)) {
                return v;
            }
        }
        return null;
    }

    /**
     * True while the tool call is still running — it holds an interrupt.
     */
    public boolean isInFlight() {
        return this == PENDING || this == IN_PROGRESS;
    }

    /**
     * True once the tool call has finished — it releases an interrupt. Any status that is neither in flight nor
     * terminal is not part of the documented vocabulary.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    private final String wireValue;

    AcpToolCallStatusEnum(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

}

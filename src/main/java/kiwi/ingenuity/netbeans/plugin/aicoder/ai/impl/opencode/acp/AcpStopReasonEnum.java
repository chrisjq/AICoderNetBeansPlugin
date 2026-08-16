package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp;

/**
 * How a turn ends. This is the stopReason field in the session/prompt response.
 * There is no end-of-turn notification — the reason arrives as part of the
 * response.
 */
public enum AcpStopReasonEnum {
    END_TURN("end_turn"),
    MAX_TOKENS("max_tokens"),
    MAX_TURN_REQUESTS("max_turn_requests"),
    REFUSAL("refusal"),
    CANCELLED("cancelled");

    /**
     * Resolve a wire string to its enum constant. Returns null if the string is
     * not recognised — the protocol adds new values over time and must not fail
     * on unknown stop reasons.
     */
    public static AcpStopReasonEnum fromWire(String wire) {
        if (wire == null) {
            return null;
        }
        for (AcpStopReasonEnum v : values()) {
            if (v.wireValue.equals(wire)) {
                return v;
            }
        }
        return null;
    }

    private final String wireValue;

    AcpStopReasonEnum(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

}

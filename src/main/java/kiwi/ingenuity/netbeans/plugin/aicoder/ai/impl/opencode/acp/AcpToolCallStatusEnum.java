package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp;

public enum AcpToolCallStatusEnum {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    FAILED("failed");

    /**
     * Resolve a wire string to its enum constant. Returns null if the string is
     * not recognised — the protocol adds new values over time and must not fail
     * on unknown statuses.
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

    private final String wireValue;

    AcpToolCallStatusEnum(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp;

public enum AcpPermissionKindEnum {
    ALLOW_ONCE("allow_once"),
    ALLOW_ALWAYS("allow_always"),
    REJECT_ONCE("reject_once"),
    REJECT_ALWAYS("reject_always");

    /**
     * Resolve a wire string to its enum constant. Returns null if the string is
     * not recognised — the protocol adds new values over time and must not fail
     * on unknown kinds.
     */
    public static AcpPermissionKindEnum fromWire(String wire) {
        if (wire == null) {
            return null;
        }
        for (AcpPermissionKindEnum v : values()) {
            if (v.wireValue.equals(wire)) {
                return v;
            }
        }
        return null;
    }

    private final String wireValue;

    AcpPermissionKindEnum(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

}

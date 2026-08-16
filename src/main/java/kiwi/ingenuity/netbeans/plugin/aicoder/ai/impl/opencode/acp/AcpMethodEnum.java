package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp;

/**
 * WIRE method strings for OpenCode's JSON-RPC protocol. These are the exact
 * strings transmitted in the "method" field of requests and responses. Note:
 * OpenCode's TypeScript source uses different names (newSession, sessionUpdate,
 * requestPermission, etc.) — those are SDK-internal and MUST NOT appear here.
 */
public enum AcpMethodEnum {
    INITIALIZE("initialize"),
    AUTHENTICATE("authenticate"),
    SESSION_NEW("session/new"),
    SESSION_LOAD("session/load"),
    SESSION_RESUME("session/resume"),
    SESSION_CLOSE("session/close"),
    SESSION_LIST("session/list"),
    SESSION_PROMPT("session/prompt"),
    SESSION_CANCEL("session/cancel"),
    SESSION_SET_CONFIG_OPTION("session/set_config_option"),
    SESSION_UPDATE("session/update"),
    SESSION_REQUEST_PERMISSION("session/request_permission"),
    FS_READ_TEXT_FILE("fs/read_text_file"),
    FS_WRITE_TEXT_FILE("fs/write_text_file");

    /**
     * Resolve a wire string to its enum constant. Returns null if the string is
     * not recognised — the protocol adds new values over time and must not fail
     * on unknown methods.
     */
    public static AcpMethodEnum fromWire(String wire) {
        if (wire == null) {
            return null;
        }
        for (AcpMethodEnum v : values()) {
            if (v.wireValue.equals(wire)) {
                return v;
            }
        }
        return null;
    }

    private final String wireValue;

    AcpMethodEnum(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp;

/**
 * Discriminator for update types inside a session/update notification. Do not
 * confuse the field name sessionUpdate with the method name session/update.
 */
public enum AcpSessionUpdateEnum {
    USER_MESSAGE_CHUNK("user_message_chunk"),
    AGENT_MESSAGE_CHUNK("agent_message_chunk"),
    AGENT_THOUGHT_CHUNK("agent_thought_chunk"),
    TOOL_CALL("tool_call"),
    TOOL_CALL_UPDATE("tool_call_update"),
    PLAN("plan"),
    AVAILABLE_COMMANDS_UPDATE("available_commands_update"),
    CURRENT_MODE_UPDATE("current_mode_update"),
    CONFIG_OPTION_UPDATE("config_option_update"),
    SESSION_INFO_UPDATE("session_info_update"),
    USAGE_UPDATE("usage_update");

    /**
     * Resolve a wire string to its enum constant. Returns null if the string is
     * not recognised — the protocol adds new values over time and must not fail
     * on unknown update types.
     */
    public static AcpSessionUpdateEnum fromWire(String wire) {
        if (wire == null) {
            return null;
        }
        for (AcpSessionUpdateEnum v : values()) {
            if (v.wireValue.equals(wire)) {
                return v;
            }
        }
        return null;
    }

    private final String wireValue;

    AcpSessionUpdateEnum(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

/**
 * The RPC commands {@code pi --mode rpc} accepts, sent wrapped in a {@code {type:"command", ...}} line carrying a
 * caller-generated {@code id} that the matching {@code response} echoes. The full set per the pi backend plan; each
 * command name is also its {@code type:"command", command:<name>} value on the wire.
 */
public enum PiRpcCommandEnum {
    PROMPT("prompt"),
    STEER("steer"),
    ABORT("abort"),
    GET_STATE("get_state"),
    GET_AVAILABLE_MODELS("get_available_models"),
    SET_MODEL("set_model"),
    GET_AVAILABLE_THINKING_LEVELS("get_available_thinking_levels"),
    SET_THINKING_LEVEL("set_thinking_level"),
    COMPACT("compact"),
    GET_SESSION_STATS("get_session_stats");

    private final String command;

    PiRpcCommandEnum(String command) {
        this.command = command;
    }

    public String command() {
        return command;
    }

    /**
     * Resolves a wire command name to its constant, or {@code null} if unknown.
     */
    public static PiRpcCommandEnum of(String command) {
        if (command == null) {
            return null;
        }
        for (PiRpcCommandEnum c : values()) {
            if (c.command.equals(command)) {
                return c;
            }
        }
        return null;
    }
}

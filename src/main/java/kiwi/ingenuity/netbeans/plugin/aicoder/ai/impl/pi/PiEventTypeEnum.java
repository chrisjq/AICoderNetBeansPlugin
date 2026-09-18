package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

/**
 * The event {@code type} names {@code pi --mode rpc} emits on stdout, as verified live against pi 0.85.1 (spec
 * *Implementation-time verifications*). Turn order is {@code agent_start} → {@code turn_start} →
 * {@code message_start}/{@code message_end} (user, then assistant; usage inside {@code message.usage}) →
 * {@code turn_end} → {@code agent_end} → optional {@code auto_retry_start}/{@code auto_retry_end} →
 * {@code agent_settled}. {@code message_update} carries {@code assistantMessageEvent} with a
 * {@code text_delta|thinking_delta|toolcall_start|toolcall_delta|toolcall_end} subtype; the subtype names are values,
 * not types, and stay literals in the parser.
 */
public enum PiEventTypeEnum {
    AGENT_START("agent_start"),
    AGENT_END("agent_end"),
    AGENT_SETTLED("agent_settled"),
    TURN_START("turn_start"),
    TURN_END("turn_end"),
    MESSAGE_START("message_start"),
    MESSAGE_END("message_end"),
    MESSAGE_UPDATE("message_update"),
    AUTO_RETRY_START("auto_retry_start"),
    AUTO_RETRY_END("auto_retry_end"),
    // Kept even though PiStreamJsonParser.toEvent() ignores it (falls into its EXTENSION_UI_RESPONSE/.../
    // QUEUE_UPDATE/TOOL_EXECUTION_UPDATE catch-all at FINE): confirmed live on the wire (queue_update), so `of()`
    // must still resolve it to a known constant rather than log it as an unhandled type every time it arrives.
    QUEUE_UPDATE("queue_update"),
    TOOL_EXECUTION_START("tool_execution_start"),
    // Also kept and ignored for the same reason: part of pi's own AgentEvent union (sub-updates of an
    // already-reported tool_execution_start), but the parser only renders the start/end pair.
    TOOL_EXECUTION_UPDATE("tool_execution_update"),
    TOOL_EXECUTION_END("tool_execution_end"),
    // Fires when pi compacts the conversation — both on our own explicit `compact` command (reason:"manual") and
    // on pi's own automatic compaction (reason:"threshold"|"overflow", never requested by the plugin at all).
    // Verified against the shipped agent-session.d.ts (Round-5 wire-shape scan).
    COMPACTION_START("compaction_start"),
    COMPACTION_END("compaction_end"),
    // Fires when the thinking level changes by any means OTHER than our own set_thinking_level RPC (e.g. pi's own
    // normalisation of an unsupported level, or an in-session mechanism outside our control) — verified against
    // agent-session.d.ts: {type, level}.
    THINKING_LEVEL_CHANGED("thinking_level_changed"),
    EXTENSION_UI_REQUEST("extension_ui_request"),
    EXTENSION_UI_RESPONSE("extension_ui_response"),
    RESPONSE("response");

    private final String type;

    PiEventTypeEnum(String type) {
        this.type = type;
    }

    public String type() {
        return type;
    }

    /**
     * Resolves a wire type name to its constant, or {@code null} if unknown.
     */
    public static PiEventTypeEnum of(String type) {
        if (type == null) {
            return null;
        }
        for (PiEventTypeEnum t : values()) {
            if (t.type.equals(type)) {
                return t;
            }
        }
        return null;
    }
}

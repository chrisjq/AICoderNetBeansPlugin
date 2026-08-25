package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

/**
 * When an inbox message actually reaches a backend that is mid-turn.
 * <p>
 * This is a property of the backend's wire protocol, not a setting. It was previously implicit, spread across five
 * process managers, and invisible to the session doing the sending — so a sender could set {@code important=true}
 * against a backend that cannot be interrupted at all and have no way to discover that the flag did nothing.
 * <p>
 * The distinction also decides whether the recipient needs telling what happened afterwards: only {@link #ABORTS_TURN}
 * destroys work in progress, and only that case can be mistaken by the assistant for a rejection by the user.
 */
public enum MailDeliveryTimingEnum {

    /**
     * The message is injected INTO the running turn and read without stopping it. Nothing is aborted and no tool call
     * is lost.
     * <p>
     * Codex does this with {@code turn/steer}; GitHub Copilot with an immediate-mode send.
     */
    DURING_TURN("Read mid-turn."),
    /**
     * The message ends the turn in order to be delivered. The recipient sees it promptly, but any tool call in flight
     * is aborted.
     * <p>
     * Claude does this: the plugin sends {@code control_request(interrupt)}, which is the same signal the Stop button
     * sends, so the backend reports the abort as a user cancellation. The assistant can therefore conclude the USER
     * rejected the call — a false belief about the user's intent — which is why this case gets a follow-up explanation.
     */
    ABORTS_TURN("Read mid-turn."),
    /**
     * The message waits for the turn to finish on its own. There is no mid-turn channel, so marking a message important
     * has no effect on this backend.
     * <p>
     * Grok and Ollama have no persistent session to inject into; OpenCode has not implemented it.
     */
    AFTER_TURN("Read at the end of the current turn.");

    private final String description;

    MailDeliveryTimingEnum(String description) {
        this.description = description;
    }

    /**
     * Sender-facing phrasing for tool output, written in terms of what setting {@code important} will actually DO to
     * this backend, rather than naming the mechanism. A sender does not care that Codex uses {@code turn/steer} and
     * Claude uses {@code control_request(interrupt)}; it cares whether the peer will read the message sooner and what
     * that costs.
     */
    public String description() {
        return description;
    }

    /**
     * Whether marking a message important changes when it is read.
     * <p>
     * True for {@link #DURING_TURN} and {@link #ABORTS_TURN}, but note they differ in kind: DURING_TURN genuinely reads
     * mid-turn, while ABORTS_TURN only brings the END of the turn forward and destroys work to do it. False for
     * {@link #AFTER_TURN}, where the flag is silently inert.
     */
    public boolean isInterruptible() {
        return this != AFTER_TURN;
    }
}

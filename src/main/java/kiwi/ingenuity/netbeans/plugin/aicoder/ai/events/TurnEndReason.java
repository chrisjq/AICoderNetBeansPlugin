package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import java.text.MessageFormat;
import java.util.Locale;

/**
 * Why a turn ended (spec §4.10, §4.1). Exactly one per turn.
 */
public enum TurnEndReason {
    ANSWERED(null),
    LENGTH("The answer was cut off by the server''s length limit (after {0,number,#} continuations). Ask it to "
            + "continue, or raise the limit on the server."),
    CANCELLED("Cancelled."),
    STEP_LIMIT("Stopped after {0,number,#} tool rounds (the limit). Say ''continue'' to go on."),
    REPETITION("Stopped: the model kept repeating the same tool call ({0}) without new results."),
    MALFORMED("The model produced invalid tool calls {0,number,#} times. Set Tool calling to Schema in session "
            + "settings, or use a model with tool support."),
    CONTEXT_OVERFLOW("{0}"),
    SIZE_LIMIT("Stopped: the response grew past the safety limit ({0,number,#} MB)."),
    SERVER_ERROR("{0}"),
    TIMEOUT("{0}");

    private final String template;

    TurnEndReason(String template) {
        this.template = template;
    }

    /**
     * The chat message for this reason, or null for {@link #ANSWERED}.
     */
    public String message(Object... args) {
        return template == null ? null : new MessageFormat(template, Locale.ROOT).format(args);
    }
}

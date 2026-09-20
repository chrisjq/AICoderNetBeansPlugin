package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

/**
 * The backend's turn just ended BECAUSE the plugin's file-access policy refused a read, and the agent has been told it
 * was a user's decision. Posted immediately BEFORE the {@link TurnCompleteEvent} of that turn, so the UI has recorded
 * it by the time it handles the turn's end.
 *
 * <p>
 * Some backends end the WHOLE turn when a tool call is refused, and report it as "The user declined this tool call".
 * The user was never asked: the refusal came from the plugin's policy, silently. An agent that believes otherwise tells
 * the user they refused something they never saw. The UI answers this event with an agent-only turn — see
 * {@link #compose} — that says what happened and restarts the work.
 *
 * <p>
 * THE USER SEES NOTHING OF THIS, by construction rather than by care: the event carries no display text at all, and the
 * UI feeds {@link #compose}'s result only into the agent-only channel of the turn it submits. Pinned by tests.
 *
 * <p>
 * The wording is deliberately not this class's own. Each entry is a short framing clause, the refused path, and then —
 * verbatim — the text {@code GetFileContent} itself returns for that path, which is
 * {@code McpHookServer.fileAccessDeniedMessage} and reaches the agent unwrapped. The agent already knows how to act on
 * that refusal, and a native read and a {@code GetFileContent} read now say the same thing as well as decide the same
 * way.
 *
 * @param refusals every read refused during the turn, in order, without repeats; never null
 */
public record PolicyRefusalEvent(List<Refusal> refusals) implements AiProcessEvent {

    /**
     * One refused read: the path, and the refusal text exactly as {@code GetFileContent} returns it for that path.
     *
     * <p>
     * The path is carried EXPLICITLY, not left to the text to echo. The shared message does name the path today, but a
     * notice that relied on that would silently lose the mapping between a file and its reason the day the wording
     * changed, and with several refusals in one turn the agent could no longer tell which reason belongs to which file.
     */
    public record Refusal(String path, String reason) {

    }

    /**
     * Opens each entry; the refused path follows.
     */
    public static final String ENTRY_START = "The previous tool result for ";

    /**
     * Follows the path; the shared refusal text, verbatim, follows. States the one fact the agent has wrong — that a
     * user rejected the call — and nothing else.
     */
    public static final String ENTRY_MIDDLE = " was not a user rejection but automatic: ";

    /**
     * The one caveat, and the instruction that starts the work again — the turn is dead and this is the message that
     * restarts it, so without an imperative an agent may simply acknowledge and stop. The refused read itself is known
     * not to have run; what is NOT known is the fate of any OTHER tool call that was still running in the same step,
     * which the backend cut off as well.
     */
    public static final String CUT_OFF_CALLS = "If any other tool call was still running in that turn, it was cut off "
            + "and its outcome is UNKNOWN: check its effect before repeating it, then continue.";

    public PolicyRefusalEvent {
        refusals = List.copyOf(refusals);
    }

    /**
     * One entry: the framing clause, the path, and the refusal text verbatim.
     */
    public static String entry(Refusal refusal) {
        return ENTRY_START + refusal.path() + ENTRY_MIDDLE + refusal.reason();
    }

    /**
     * The agent-only notice for these refusals: one entry per refused path, all the same shape and never merged under a
     * shared reason, then the single caveat.
     *
     * @return null when there is nothing to say
     */
    public static String compose(List<Refusal> refusals) {
        if (refusals == null || refusals.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Refusal refusal : refusals) {
            sb.append(entry(refusal)).append('\n');
        }
        return sb.append(CUT_OFF_CALLS).toString();
    }
}

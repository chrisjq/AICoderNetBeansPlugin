package kiwi.ingenuity.netbeans.plugin.aicoder.ai.session;

/**
 * How many times the plugin may wake a session on its own after a policy refusal ended the session's turn, between one
 * message from the user and the next.
 *
 * <p>
 * THE LOOP GUARD. A refusal is caused by the agent itself, and the wake-up is what runs the agent again, so without a
 * bound the two feed each other: the agent reads another out-of-scope path, is refused, is woken, reads another. Each
 * cycle is a full model round trip with no human in it, and it stops only when the model decides to. Unlike the other
 * one-shot flags in this codebase — a user pressing Stop, a peer's message arriving — nothing outside the model limits
 * how often this one can be armed.
 *
 * <p>
 * So the number of automatic turns is limited directly. Every wake-up takes one of {@link #MAX_WAKES} tokens, and only
 * {@link #reset()} gives them back — called when the USER submits a message. A turn the plugin starts by itself never
 * calls it, so it cannot refill its own budget. Whatever the model does, automatic turns per user message are at most
 * {@link #MAX_WAKES}.
 *
 * <p>
 * Thread-safe: taken from the backend's turn-completion thread, reset from the EDT.
 */
public final class PolicyRefusalBudget {

    /**
     * Automatic wake-ups allowed per user message.
     *
     * <p>
     * THE VALUE IS A JUDGEMENT CALL. Each wake-up is one cheap turn, and a legitimate task can touch several external
     * paths (a build log, a config file, a temp file) before it is done, so a cap that is too low abandons the agent
     * mid-task, which is the failure this exists to remove. Five leaves room for that while still ending a model that
     * ignores the notice. Change it freely: what is LOAD-BEARING is not the number but the rule that the budget is
     * refilled only when the user submits a message, because that alone is what makes the loop terminate.
     */
    public static final int MAX_WAKES = 5;

    private final int max;
    private int used = 0;

    public PolicyRefusalBudget() {
        this(MAX_WAKES);
    }

    PolicyRefusalBudget(int max) {
        this.max = max;
    }

    /**
     * Spends one wake-up.
     *
     * @return true if one was left and has now been used; false once the budget is spent, in which case the caller must
     * not wake the session
     */
    public synchronized boolean tryAcquire() {
        if (used >= max) {
            return false;
        }
        used++;
        return true;
    }

    /**
     * Gives every wake-up back. Only for a turn the user started.
     */
    public synchronized void reset() {
        used = 0;
    }
}

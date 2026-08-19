package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Fired when a {@code thread/tokenUsage/updated} notification arrives from the
 * Codex {@code app-server}. Carries the cumulative token count for the thread
 * ({@code tokenUsage.total.totalTokens}) and the model context window size
 * ({@code tokenUsage.modelContextWindow}, 0 when the server does not supply
 * it). Used to drive the context gauge in the info bar.
 */
public final class CodexTokenUsageEvent implements AiProcessImplEvent {

    private final long usedTokens;
    private final long contextWindow;

    public CodexTokenUsageEvent(long usedTokens, long contextWindow) {
        this.usedTokens = usedTokens;
        this.contextWindow = contextWindow;
    }

    /**
     * Cumulative total tokens used by this thread ({@code total.totalTokens}).
     */
    public long usedTokens() {
        return usedTokens;
    }

    /**
     * Model context window size, or 0 when the server did not supply it.
     */
    public long contextWindow() {
        return contextWindow;
    }
}

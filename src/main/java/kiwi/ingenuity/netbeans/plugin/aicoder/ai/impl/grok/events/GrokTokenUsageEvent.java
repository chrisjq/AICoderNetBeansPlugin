package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Fired after a grok CLI turn completes, carrying the real per-session context
 * usage read from the CLI's on-disk {@code signals.json} file (grok's
 * {@code --output-format json} stdout does not itself report usage — see
 * {@code GrokUsageSignalsReader}). Used to update the context usage progress
 * bar in the info bar (mirrors {@code GithubCopilotTokenUsageEvent}).
 */
public class GrokTokenUsageEvent implements AiProcessImplEvent {

    private final int currentTokens;
    private final int maxTokens;
    private final String model;

    public GrokTokenUsageEvent(int currentTokens, int maxTokens, String model) {
        this.currentTokens = currentTokens;
        this.maxTokens = maxTokens;
        this.model = model;
    }

    public int currentTokens() {
        return currentTokens;
    }

    public int maxTokens() {
        return maxTokens;
    }

    /**
     * The model actually used for the turn, if reported. May be null.
     */
    public String model() {
        return model;
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Fired after a Grok ACP turn completes, carrying the token usage reported in the {@code session/prompt}
 * result's {@code _meta}. {@code maxTokens} is 0 when Grok's {@code _meta} carries no context-window size for
 * that turn — the info bar keeps whatever window size it last knew rather than treating 0 as the real window.
 * Used to update the context usage progress bar in the info bar (mirrors
 * {@code GithubCopilotTokenUsageEvent}).
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

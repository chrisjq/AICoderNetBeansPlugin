package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Fired when GitHub Copilot API response includes token usage information. Used
 * to update the context usage progress bar in the info bar.
 */
public class GithubCopilotTokenUsageEvent implements AiProcessImplEvent {

    private final int currentTokens;
    private final int maxTokens;
    private final String model;

    public GithubCopilotTokenUsageEvent(int currentTokens, int maxTokens) {
        this(currentTokens, maxTokens, null);
    }

    public GithubCopilotTokenUsageEvent(int currentTokens, int maxTokens, String model) {
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
     * The model actually in use, if reported (e.g. by session.shutdown). The
     * info bar uses it to size the context window. May be null.
     */
    public String model() {
        return model;
    }
}

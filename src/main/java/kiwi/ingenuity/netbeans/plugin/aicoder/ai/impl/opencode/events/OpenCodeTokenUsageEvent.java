package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Fired when OpenCode reports token usage via a {@code usage_update} session
 * update. Used to update the context usage progress bar in the info bar.
 */
public class OpenCodeTokenUsageEvent implements AiProcessImplEvent {

    private final long usedTokens;
    private final long sizeTokens;

    public OpenCodeTokenUsageEvent(long usedTokens, long sizeTokens) {
        this.usedTokens = usedTokens;
        this.sizeTokens = sizeTokens;
    }

    public long usedTokens() {
        return usedTokens;
    }

    public long sizeTokens() {
        return sizeTokens;
    }
}

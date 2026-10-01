package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * {@code get_session_stats.contextUsage}, read after a turn completes: tokens in use and the model's context
 * window.
 */
public record PiContextUsageEvent(int usedTokens, int contextWindowTokens) implements AiProcessImplEvent {

}

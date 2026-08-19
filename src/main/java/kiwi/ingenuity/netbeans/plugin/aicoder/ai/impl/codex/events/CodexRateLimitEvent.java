package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Account-wide Codex primary rate-limit usage reported by the app server.
 */
public record CodexRateLimitEvent(double usedPercent, long windowDurationMins,
        long resetsAtEpochSeconds) implements AiProcessImplEvent, AiPropertyEvent {

}

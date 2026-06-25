package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * weeklyPct and sessionPct are -1.0 when unavailable. model may be null.
 */
public record ClaudeSessionInfoEvent(double weeklyPct, double sessionPct, String model) implements AiProcessImplEvent {

    public boolean hasWeeklyPct() {
        return weeklyPct >= 0;
    }

    public boolean hasSessionPct() {
        return sessionPct >= 0;
    }
}

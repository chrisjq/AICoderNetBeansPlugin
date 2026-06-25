package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;

public record ClaudeUsageEvent(double fiveHourPct, double sevenDayPct) implements AiPropertyEvent {

}

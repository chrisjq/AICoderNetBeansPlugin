package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Reports that Copilot cleared an unsupported persisted reasoning effort.
 */
public record GithubCopilotReasoningEffortClearedEvent() implements AiProcessImplEvent {
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Reports the model selected by Copilot after a requested model is unavailable.
 */
public record GithubCopilotModelFallbackEvent(String model) implements AiProcessImplEvent {

}

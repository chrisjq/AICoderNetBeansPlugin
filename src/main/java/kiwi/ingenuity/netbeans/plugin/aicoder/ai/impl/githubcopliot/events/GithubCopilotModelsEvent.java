package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.events;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;

/**
 * Carries the discovered GitHub Copilot model list so it can be broadcast to
 * every open Copilot session's model dropdown via {@code AiTypePropertyBus}
 * (mirrors {@code ClaudeModelsEvent}).
 */
public record GithubCopilotModelsEvent(List<String> models) implements AiPropertyEvent {

}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;

/**
 * Fired once the real list of models available to the account is discovered via
 * {@code grok models} (mirrors {@code ClaudeModelsEvent} /
 * {@code GithubCopilotModelsEvent}). Broadcast to every open Grok session's
 * info bar via {@code AiTypePropertyBus}.
 */
public record GrokModelsEvent(List<String> models) implements AiPropertyEvent {

}

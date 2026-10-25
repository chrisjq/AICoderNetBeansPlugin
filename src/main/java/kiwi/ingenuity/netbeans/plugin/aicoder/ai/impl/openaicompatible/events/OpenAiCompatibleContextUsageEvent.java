package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Context usage and its resolved window for an OpenAI Compatible session info bar.
 */
public record OpenAiCompatibleContextUsageEvent(int used, int window, String windowSource,
                                                String serverKind) implements AiProcessImplEvent {

}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * The thinking level this session's pi process is actually using. Produced by {@code PiStreamJsonParser} from
 * a {@code thinking_level_changed} frame ({@code {type, level}} per the shipped {@code agent-session.d.ts}),
 * which fires when the level changes by any means OTHER than the plugin's own {@code set_thinking_level} RPC
 * (e.g. pi normalising an unsupported level); {@code PiAiProcessManager} also emits it for the level
 * {@code get_state} reports when the process spawns and for a successful {@code set_thinking_level}.
 * {@code level} is pi's raw {@code ThinkingLevel} string
 * ({@code "off"|"minimal"|"low"|"medium"|"high"|"xhigh"|"max"}).
 */
public record PiThinkingLevelChangedEvent(String level) implements AiProcessImplEvent {

}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

/**
 * A pi {@code thinking_level_changed} frame ({@code {type, level}} per the shipped {@code agent-session.d.ts}, Round-5
 * wire-shape scan) — fires when the thinking level changes by any means OTHER than the plugin's own
 * {@code set_thinking_level} RPC (e.g. pi normalising an unsupported level, or an in-session mechanism outside the
 * plugin's control). {@code level} is pi's raw {@code ThinkingLevel} string ({@code "off"|"minimal"|"low"|"medium"
 * |"high"|"xhigh"|"max"}).
 *
 * <p>
 * {@code PiStreamJsonParser} only produces this event; it does not act on it itself — {@code PiAiProcessManager} (which
 * owns the {@code PiSessionControl.Listener} the info bar's picker follows) is expected to catch it in its own listener
 * wrapper and forward {@code level} to {@code PiSessionControl.Listener#onCurrentSelectionChanged}, the same way it
 * already does for {@code get_state}'s {@code data.thinkingLevel} at session start.
 */
public record PiThinkingLevelChangedEvent(String level) implements AiProcessEvent {

}

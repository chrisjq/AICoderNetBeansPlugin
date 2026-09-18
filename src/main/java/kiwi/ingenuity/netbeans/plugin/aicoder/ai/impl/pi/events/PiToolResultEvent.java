package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

/**
 * The per-turn result of one pi {@code tool_execution_end} frame, as produced by {@code PiStreamJsonParser}. Not
 * currently rendered anywhere: {@code AiTopComponent}'s event dispatch has no branch for it, matching the existing
 * house baseline — {@code ClaudeStreamJsonParser} does not event-ify tool results either — so a pi-only chat-visible
 * "tool result" surface was deliberately not added here. For a gated write/edit specifically, the accept/reject outcome
 * the user actually sees comes from a different, already-wired path: the shared {@code PermissionEvent} / diff-panel
 * flow in {@code McpHookServer}/{@code AiTopComponent}. {@code isError} is downgraded by the parser to {@code false}
 * for a write/edit the user accepted through that gate, whose blocked reason is the "SUCCESS — the user accepted"
 * marker (a rejection, not a failure) — carried here for if/when this event does get a rendering path of its own,
 * though today the downgrade has no observable effect.
 */
public record PiToolResultEvent(String toolCallId, String toolName, String resultText, boolean isError)
        implements AiProcessEvent {

}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

/**
 * Fired when the AI requests permission to apply an ordered set of proposed file changes. The items keep the order the
 * AI supplied — that order is meaningful and must never be sorted or reordered. The UI must complete the single
 * aggregate response with one allow/deny decision for the whole set; the AI process is blocked until the future is
 * resolved.
 *
 * Backend-neutral by design: it describes an ordered set of proposed file changes, each with a path and proposed
 * content, plus one aggregate response — no Codex types, no unified-diff assumptions, no accept/decline vocabulary.
 */
public record MultiPermissionEvent(
        List<MultiPermissionItem> items,
        CompletableFuture<PermissionDecision> response
        ) implements AiProcessEvent {

    /**
     * Defensive copy preserves the AI-supplied order and makes the batch unmodifiable, so no consumer can reorder or
     * mutate the change set while it is being reviewed.
     *
     * <p>
     * An empty change set is refused here, on the contract itself, rather than in any one consumer: this event is the
     * shared vocabulary a second backend is expected to adopt, and that backend may write its own consumer. A batch
     * with no files is a producer bug, not a user decision, and answering "accepted" for a set nobody could review is
     * exactly the defect per-file review exists to prevent. Throwing is safe for the caller — the backend handlers wrap
     * request handling in a catch-all that turns a throw into a protocol-level error reply, so the AI is answered
     * rather than left waiting.</p>
     *
     * @throws IllegalArgumentException if the item list is empty
     */
    public MultiPermissionEvent {
        items = List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("a change set must contain at least one file");
        }
    }
}

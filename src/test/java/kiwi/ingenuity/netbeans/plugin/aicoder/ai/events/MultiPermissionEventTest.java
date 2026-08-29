package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class MultiPermissionEventTest {

    /**
     * The event must hold the items in exactly the order the AI supplied — never sorted. Paths below are deliberately
     * non-alphabetical so any sorting would produce a different list.
     */
    @Test
    void holdsItemsInSuppliedOrder() {
        MultiPermissionItem third = new MultiPermissionItem("/tmp/c.java", "class C {}");
        MultiPermissionItem first = new MultiPermissionItem("/tmp/a.java", "class A {}");
        MultiPermissionItem second = new MultiPermissionItem("/tmp/b.java", "class B {}");

        MultiPermissionEvent event = new MultiPermissionEvent(
                List.of(third, first, second), new CompletableFuture<>());

        List<MultiPermissionItem> items = event.items();
        assertEquals(3, items.size());
        assertSame(third, items.get(0));
        assertSame(first, items.get(1));
        assertSame(second, items.get(2));
        assertEquals("/tmp/c.java", items.get(0).filePath());
        assertEquals("/tmp/a.java", items.get(1).filePath());
        assertEquals("/tmp/b.java", items.get(2).filePath());
    }

    /**
     * The event exposes exactly one aggregate response future, and it is the instance the caller supplied — the UI
     * completes it once for the whole change set.
     */
    @Test
    void exposesSingleAggregateFuture() {
        CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
        MultiPermissionEvent event = new MultiPermissionEvent(
                List.of(new MultiPermissionItem("/tmp/a.java", "class A {}")), future);

        assertSame(future, event.response());
    }

    /**
     * The compact constructor copies the list so no consumer can reorder or mutate the batch mid-review.
     */
    @Test
    void returnedItemListIsUnmodifiable() {
        MultiPermissionEvent event = new MultiPermissionEvent(
                List.of(new MultiPermissionItem("/tmp/a.java", "class A {}")), new CompletableFuture<>());

        assertThrows(UnsupportedOperationException.class, () -> event.items().add(new MultiPermissionItem("/tmp/b.java", "x")));
    }

    /**
     * An empty change set is refused on the contract itself, so every consumer is protected rather than only the one
     * that happens to check. A batch with no files is a producer bug, not a user decision, and a consumer that answered
     * "accepted" for it would be approving a set nobody could review — the defect per-file review exists to prevent.
     */
    @Test
    void rejectsAnEmptyChangeSet() {
        CompletableFuture<PermissionDecision> future = new CompletableFuture<>();

        assertThrows(IllegalArgumentException.class, () -> new MultiPermissionEvent(List.of(), future));
        assertFalse(future.isDone(), "the caller keeps ownership of a future the event refused to take");
    }
}

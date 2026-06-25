package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.PromptHistory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class PromptHistoryTest {

    @Test
    void previousOnEmpty_returnsEmpty() {
        assertEquals("", new PromptHistory().previous("current"));
    }

    @Test
    void previousAfterAdd_returnsThatEntry() {
        PromptHistory h = new PromptHistory();
        h.add("first prompt");
        assertEquals("first prompt", h.previous(""));
    }

    @Test
    void previousTwice_wrapsToOldest() {
        PromptHistory h = new PromptHistory();
        h.add("a");
        h.add("b");
        assertEquals("b", h.previous(""));
        assertEquals("a", h.previous("b"));
        assertEquals("a", h.previous("a")); // no more history — stay at oldest
    }

    @Test
    void next_afterPrevious_movesForward() {
        PromptHistory h = new PromptHistory();
        h.add("a");
        h.add("b");
        h.previous(""); // → "b"
        h.previous("b"); // → "a"
        assertEquals("b", h.next("a"));
    }

    @Test
    void next_atNewest_returnsEmpty() {
        PromptHistory h = new PromptHistory();
        h.add("a");
        h.previous(""); // → "a"
        assertEquals("", h.next("a")); // past the end → blank
    }

    @Test
    void duplicatesAreNotAdded() {
        PromptHistory h = new PromptHistory();
        h.add("same");
        h.add("same");
        h.previous("");
        assertEquals("", h.next("same")); // only one entry
    }

    @Test
    void clear_resetsHistory() {
        PromptHistory h = new PromptHistory();
        h.add("a");
        h.clear();
        assertEquals("", h.previous(""));
    }
}

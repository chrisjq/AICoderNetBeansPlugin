package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import java.util.List;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class PrunerTest {

    private static ChatMessage user(String text) {
        return new ChatMessage(ChatRole.USER, text, List.of(), null);
    }

    private static ChatMessage assistantCall(String id) {
        return new ChatMessage(ChatRole.ASSISTANT, null, List.of(new ChatToolCall(id, "Tool", "{}")), null);
    }

    private static ChatMessage toolResult(String id, String text) {
        return new ChatMessage(ChatRole.TOOL, text, List.of(), id);
    }

    private static ContextHistory historyWithToolResult(String content) {
        ContextHistory h = new ContextHistory();
        h.append(user("do it"));
        h.append(assistantCall("c1"));
        h.append(toolResult("c1", content));
        return h;
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void prunesLongToolResultInOldTurn() {
        String content = "x".repeat(5000);
        ContextHistory h = historyWithToolResult(content);

        Pruner.Result result = new Pruner().prune(h, 0);

        assertTrue(result.changed());
        assertEquals(1, result.prunedCount());
        String pruned = h.messages().get(2).content();
        assertEquals("x".repeat(200) + "\n[pruned: 4800 chars]", pruned);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void leavesShortToolResultAlone() {
        ContextHistory h = historyWithToolResult("y".repeat(3999));
        Pruner.Result result = new Pruner().prune(h, 0);
        assertFalse(result.changed());
        assertEquals(0, result.prunedCount());
        assertEquals("y".repeat(3999), h.messages().get(2).content());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void leavesExactlyThresholdLengthAlone() {
        ContextHistory h = historyWithToolResult("z".repeat(4000));
        assertFalse(new Pruner().prune(h, 0).changed());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void keepsTheMostRecentTurns() {
        ContextHistory h = new ContextHistory();
        h.append(user("old"));
        h.append(assistantCall("c1"));
        h.append(toolResult("c1", "a".repeat(5000)));
        h.append(user("recent"));
        h.append(assistantCall("c2"));
        h.append(toolResult("c2", "b".repeat(5000)));

        Pruner.Result result = new Pruner().prune(h, 1);

        assertTrue(result.changed());
        assertEquals(1, result.prunedCount());
        assertTrue(h.messages().get(2).content().endsWith("[pruned: 4800 chars]"));
        assertEquals("b".repeat(5000), h.messages().get(5).content(), "newest turn untouched");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void keepRecentAtLeastTurnCountPrunesNothing() {
        ContextHistory h = historyWithToolResult("c".repeat(5000));
        Pruner.Result result = new Pruner().prune(h, 5);
        assertFalse(result.changed());
        assertEquals("c".repeat(5000), h.messages().get(2).content());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void neverPrunesNonToolMessages() {
        ContextHistory h = new ContextHistory();
        h.append(user("u".repeat(6000)));
        h.append(new ChatMessage(ChatRole.ASSISTANT, "a".repeat(6000), List.of(), null));

        Pruner.Result result = new Pruner().prune(h, 0);

        assertFalse(result.changed());
        assertEquals("u".repeat(6000), h.messages().get(0).content());
        assertEquals("a".repeat(6000), h.messages().get(1).content());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void prunesEveryEligibleToolResultAcrossTurns() {
        ContextHistory h = new ContextHistory();
        h.append(user("one"));
        h.append(assistantCall("c1"));
        h.append(toolResult("c1", "a".repeat(5000)));
        h.append(user("two"));
        h.append(assistantCall("c2"));
        h.append(toolResult("c2", "b".repeat(6000)));
        h.append(user("three"));
        h.append(assistantCall("c3"));
        h.append(toolResult("c3", "c".repeat(7000)));

        Pruner.Result result = new Pruner().prune(h, 1);

        assertEquals(2, result.prunedCount());
        assertTrue(h.messages().get(2).content().endsWith("[pruned: 4800 chars]"));
        assertTrue(h.messages().get(5).content().endsWith("[pruned: 5800 chars]"));
        assertEquals("c".repeat(7000), h.messages().get(8).content());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void customThresholdAndKeptCharsAreHonoured() {
        ContextHistory h = historyWithToolResult("d".repeat(1000));
        Pruner.Result result = new Pruner(500, 50).prune(h, 0);
        assertTrue(result.changed());
        assertEquals("d".repeat(50) + "\n[pruned: 950 chars]", h.messages().get(2).content());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void nullHistoryIsIgnored() {
        assertFalse(new Pruner().prune(null, 0).changed());
        assertEquals(0, new Pruner().prune(null, 0).prunedCount());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void resultReportsNoChangeWhenNothingPruned() {
        ContextHistory h = new ContextHistory();
        h.append(user("hi"));
        Pruner.Result result = new Pruner().prune(h, 0);
        assertFalse(result.changed());
        assertEquals(0, result.prunedCount());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void defaultThresholdAndKeptChars() {
        Pruner pruner = new Pruner();
        assertEquals(4000, pruner.thresholdChars());
        assertEquals(200, pruner.charsKept());
    }
}

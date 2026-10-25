package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.TokenEstimator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CompactorTest {

    private static final int[] WINDOWS = {4_096, 8_192, 16_384, 131_072};
    private static final String BODY = "x".repeat(3_500);

    private static ChatMessage user(String text) {
        return new ChatMessage(ChatRole.USER, text, List.of(), null);
    }

    private static ChatMessage assistant(String text) {
        return new ChatMessage(ChatRole.ASSISTANT, text, List.of(), null);
    }

    private static ContextHistory overflowingHistory(int turns) {
        ContextHistory history = new ContextHistory("sys");
        for (int i = 0; i < turns; i++) {
            history.append(user("u" + i + BODY));
            history.append(assistant("a" + i + BODY));
        }
        return history;
    }

    private static Compactor.Result compact(ContextHistory history, TokenEstimator estimator, int window,
                                            boolean confirmed, boolean force, boolean prune,
                                            CompactionStrategyEnum strategy) {
        return new Compactor().compact(history, estimator, window, confirmed, force, prune, strategy);
    }

    private static void assertNoAdjacentUserRoles(ContextHistory history) {
        List<ChatMessage> msgs = history.messages();
        for (int i = 1; i < msgs.size(); i++) {
            assertFalse(msgs.get(i - 1).role() == ChatRole.USER && msgs.get(i).role() == ChatRole.USER,
                        "adjacent user messages at " + (i - 1));
        }
    }

    @Test
    @Timeout(5)
    void dropsOldestUntilUnderBudgetAtEachWindow() {
        TokenEstimator estimator = new TokenEstimator();
        ContextBudget budget = new ContextBudget(ContextBudgetSettings.defaults());
        for (int window : WINDOWS) {
            ContextHistory history = overflowingHistory(80);
            int originalTurns = history.turns().size();
            int target = window - budget.reserve(window);
            int overhead = estimator.estimate(new ChatMessage(ChatRole.SYSTEM, history.pinned(), List.of(), null));
            int keepTurns = Compactor.turnsCovering(history, estimator, budget.keepRecent(window, overhead));

            Compactor.Result result = compact(history, estimator, window, true, false, false,
                                              CompactionStrategyEnum.DROP);

            assertEquals(ResolvedCompactionEnum.DROP, result.resolved(), "window " + window);
            assertTrue(result.changed(), "window " + window);
            assertTrue(result.droppedExchanges() > 0, "window " + window);
            assertEquals(originalTurns - history.turns().size(), result.droppedExchanges(), "window " + window);
            assertTrue(history.turns().size() >= keepTurns, "window " + window);
            assertTrue(history.turns().size() < originalTurns, "window " + window);
            if (history.turns().size() > keepTurns) {
                int noticeSlack = estimator.estimate(user(Compactor.modelNotice(result.droppedExchanges()) + "\n\n"));
                assertTrue(history.estimateTokens(estimator) <= target + noticeSlack,
                           "window " + window);
            }
            assertEquals(Compactor.chatNotice(result.droppedExchanges(), true, window), result.chatNotice());
            assertTrue(history.messages().get(0).content().startsWith(
                    Compactor.modelNotice(result.droppedExchanges())));
            assertNoAdjacentUserRoles(history);
        }
    }

    @Test
    @Timeout(5)
    void autoResolvesToDropAndCompactsTheSameWay() {
        TokenEstimator estimator = new TokenEstimator();
        ContextHistory history = overflowingHistory(40);
        Compactor.Result result = compact(history, estimator, 4_096, true, false, false,
                                          CompactionStrategyEnum.AUTO);
        assertEquals(ResolvedCompactionEnum.DROP, result.resolved());
        assertTrue(result.droppedExchanges() > 0);
        assertTrue(result.chatNotice().startsWith("Context trimmed ("));
        assertFalse(result.chatNotice().contains("assumed"));
    }

    @Test
    @Timeout(5)
    void noneLeavesTheHistoryUntouched() {
        TokenEstimator estimator = new TokenEstimator();
        ContextHistory history = overflowingHistory(40);
        String first = history.messages().get(0).content();
        int size = history.size();
        int tokens = history.estimateTokens(estimator);

        Compactor.Result result = compact(history, estimator, 4_096, true, false, false,
                                          CompactionStrategyEnum.NONE);

        assertEquals(ResolvedCompactionEnum.NONE, result.resolved());
        assertFalse(result.changed());
        assertEquals(0, result.droppedExchanges());
        assertNull(result.chatNotice());
        assertEquals(size, history.size());
        assertEquals(first, history.messages().get(0).content());
        assertEquals(tokens, history.estimateTokens(estimator));
    }

    @Test
    @Timeout(5)
    void automaticCompactionSkipsAnUnconfirmedWindow() {
        TokenEstimator estimator = new TokenEstimator();
        ContextHistory history = overflowingHistory(40);
        int size = history.size();

        Compactor.Result result = compact(history, estimator, 4_096, false, false, false,
                                          CompactionStrategyEnum.DROP);

        assertFalse(result.changed());
        assertEquals(0, result.droppedExchanges());
        assertNull(result.chatNotice());
        assertEquals(size, history.size());
    }

    @Test
    @Timeout(5)
    void manualCompactOnUnconfirmedWindowNotesTheAssumedWindow() {
        TokenEstimator estimator = new TokenEstimator();
        ContextHistory history = overflowingHistory(40);

        Compactor.Result result = compact(history, estimator, 8_192, false, true, false,
                                          CompactionStrategyEnum.DROP);

        assertTrue(result.droppedExchanges() > 0);
        assertEquals(Compactor.chatNotice(result.droppedExchanges(), false, 8_192), result.chatNotice());
        assertTrue(result.chatNotice().endsWith(" — window assumed: 8192"));
    }

    @Test
    @Timeout(5)
    void keepRecentClampStopsBeforeDroppingTheProtectedTail() {
        TokenEstimator estimator = new TokenEstimator();
        String huge = "y".repeat(8_000);
        ContextHistory history = new ContextHistory("sys");
        history.append(user("old" + huge));
        history.append(assistant("old-a" + huge));
        history.append(user("mid" + huge));
        history.append(assistant("mid-a" + huge));
        history.append(user("new" + huge));
        history.append(assistant("new-a" + huge));

        int window = 4_096;
        ContextBudget budget = new ContextBudget(ContextBudgetSettings.defaults());
        int target = window - budget.reserve(window);
        int overhead = estimator.estimate(new ChatMessage(ChatRole.SYSTEM, history.pinned(), List.of(), null));
        int keepTurns = Compactor.turnsCovering(history, estimator, budget.keepRecent(window, overhead));
        assertTrue(keepTurns >= 1);
        assertTrue(history.estimateTokens(estimator) > target);

        Compactor.Result result = compact(history, estimator, window, true, false, false,
                                          CompactionStrategyEnum.DROP);

        assertEquals(keepTurns, history.turns().size());
        assertEquals(3 - keepTurns, result.droppedExchanges());
        assertTrue(history.estimateTokens(estimator) > target, "still over after clamp");
        assertTrue(history.messages().stream().anyMatch(m -> m.content() != null && m.content().contains("new")));
    }

    @Test
    @Timeout(5)
    void dropNoticeMergesIntoTheFirstRemainingUserMessage() {
        TokenEstimator estimator = new TokenEstimator();
        ContextHistory history = overflowingHistory(20);

        Compactor.Result result = compact(history, estimator, 4_096, true, false, false,
                                          CompactionStrategyEnum.DROP);

        ChatMessage first = history.messages().get(0);
        assertEquals(ChatRole.USER, first.role());
        String notice = Compactor.modelNotice(result.droppedExchanges());
        assertTrue(first.content().startsWith(notice + "\n\n"));
        assertTrue(first.content().contains(BODY));
        assertNoAdjacentUserRoles(history);
        List<ChatMessage> wire = history.toRequestMessages();
        assertEquals(ChatRole.SYSTEM, wire.get(0).role());
        assertEquals(ChatRole.USER, wire.get(1).role());
        assertNotEquals(wire.get(1).role(), wire.get(2).role());
    }

    @Test
    @Timeout(5)
    void dropNoticeIsPrependedWhenTheRemainderDoesNotStartWithUser() {
        ContextHistory history = new ContextHistory("sys");
        history.append(assistant("lead"));
        Compactor.insertDropNotice(history, 2);
        assertEquals(ChatRole.USER, history.messages().get(0).role());
        assertEquals(Compactor.modelNotice(2), history.messages().get(0).content());
        assertEquals("lead", history.messages().get(1).content());
        assertNoAdjacentUserRoles(history);
    }

    @Test
    @Timeout(5)
    void dropNoticeMergesWithABlankUserMessageAsTheNoticeAlone() {
        ContextHistory history = new ContextHistory();
        history.append(user("   "));
        Compactor.insertDropNotice(history, 1);
        assertEquals(1, history.size());
        assertEquals(Compactor.modelNotice(1), history.messages().get(0).content());
    }

    @Test
    @Timeout(5)
    void pruneRunsBeforeDropAndCanAvoidDropping() {
        TokenEstimator estimator = new TokenEstimator();
        ContextBudget budget = new ContextBudget(
                new ContextBudgetSettings(0.15, 1024, 0.5, null, 0.25, 2048, 10, 0.25));
        Pruner pruner = new Pruner(500, 50);
        Compactor compactor = new Compactor(pruner, budget);

        ContextHistory history = new ContextHistory("sys");
        history.append(user("old"));
        history.append(new ChatMessage(ChatRole.ASSISTANT, null,
                                       List.of(new ChatToolCall("c1", "Tool", "{}")), null));
        ChatMessage tool = new ChatMessage(ChatRole.TOOL, "z".repeat(20_000), List.of(), "c1");
        history.append(tool);
        history.append(user("new"));
        history.append(assistant("ok"));

        int window = 4_096;
        int target = window - budget.reserve(window);
        assertTrue(history.estimateTokens(estimator) > target);

        Compactor.Result result = compactor.compact(history, estimator, window, true, false, true,
                                                    CompactionStrategyEnum.DROP);

        assertTrue(result.prunedCount() >= 1);
        assertTrue(tool.content().startsWith("z".repeat(50)));
        assertTrue(tool.content().contains("[pruned:"));
        assertTrue(result.changed());
    }

    @Test
    @Timeout(5)
    void underBudgetHistoryIsLeftAlone() {
        TokenEstimator estimator = new TokenEstimator();
        ContextHistory history = new ContextHistory("sys");
        history.append(user("hi"));
        history.append(assistant("hello"));

        Compactor.Result result = compact(history, estimator, 4_096, true, false, false,
                                          CompactionStrategyEnum.DROP);

        assertFalse(result.changed());
        assertEquals(0, result.droppedExchanges());
        assertEquals(2, history.size());
        assertEquals("hi", history.messages().get(0).content());
    }

    @Test
    @Timeout(5)
    void nullHistoryOrEstimatorIsIdle() {
        Compactor.Result noHistory = new Compactor().compact(null, new TokenEstimator(), 4_096, true, false,
                                                             false, CompactionStrategyEnum.DROP);
        assertFalse(noHistory.changed());
        Compactor.Result noEstimator = new Compactor().compact(new ContextHistory(), null, 4_096, true, false,
                                                               false, CompactionStrategyEnum.DROP);
        assertFalse(noEstimator.changed());
    }
}

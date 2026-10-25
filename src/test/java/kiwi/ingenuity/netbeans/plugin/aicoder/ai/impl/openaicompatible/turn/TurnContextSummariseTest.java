package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.TokenEstimator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.CompactionStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.ResolvedCompactionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.Summariser;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.SessionDecisions;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.HintState.HintKind;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Summarise in the session's context management (plan 7 task 4).
 */
class TurnContextSummariseTest {

    /**
     * Records what the summary model was asked, and answers with the next scripted result.
     */
    private static final class FakeRunner implements TurnContext.SummaryRunner {

        final List<List<ChatMessage>> calls = new CopyOnWriteArrayList<>();
        final List<String> previous = new CopyOnWriteArrayList<>();
        final List<Summariser.Result> answers;

        FakeRunner(Summariser.Result... answers) {
            this.answers = List.of(answers);
        }

        @Override
        public Summariser.Result summarise(List<ChatMessage> messages, String previousSummary, int window,
                                           String pinned, kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.RequestHandle handle) {
            calls.add(messages);
            previous.add(previousSummary == null ? "<none>" : previousSummary);
            return answers.get(Math.min(calls.size() - 1, answers.size() - 1));
        }
    }

    /**
     * Records the Summary button state.
     */
    private static final class Hints implements TurnContext.HintSink {

        String summary = "<unset>";
        Boolean trimmed;

        @Override
        public void set(HintKind kind, String text, String tooltip) {
        }

        @Override
        public void clear(HintKind kind) {
        }

        @Override
        public void summary(String summaryOrNull, boolean trimmedNotSummarised) {
            summary = summaryOrNull;
            trimmed = trimmedNotSummarised;
        }
    }

    private final Hints hints = new Hints();
    private final TurnContext context = new TurnContext(new TokenEstimator(), new SessionDecisions("g"), hints);

    private static Summariser.Result ok(String text) {
        return new Summariser.Result(true, text);
    }

    private static Summariser.Result failed() {
        return new Summariser.Result(false, null);
    }

    private static TurnContext.Config config(CompactionStrategyEnum strategy, int override) {
        return new TurnContext.Config(strategy, true, true, override, 8192, 6144, "GENERIC", "m", "http://h");
    }

    /**
     * {@code turns} exchanges with a 3 000-character answer each, then the current prompt.
     */
    private static List<ChatMessage> history(int turns) {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 1; i <= turns; i++) {
            history.add(new ChatMessage(ChatRole.USER, "question " + i, List.of(), null));
            history.add(new ChatMessage(ChatRole.ASSISTANT, "a".repeat(3000), List.of(), null));
        }
        history.add(new ChatMessage(ChatRole.USER, "now", List.of(), null));
        return history;
    }

    @Test
    @Timeout(5)
    void autoSummarisesAConfirmedWindowAtTheThresholdIntoOneUserMessage() {
        FakeRunner runner = new FakeRunner(ok("S1"));
        context.beginTurn(config(CompactionStrategyEnum.AUTO, 16384), runner);
        List<ChatMessage> history = history(24);

        TurnContext.Compaction compaction = context.compact(history, "SYSTEM", false);

        assertTrue(compaction.changed());
        assertEquals(1, runner.calls.size());
        int summarised = runner.calls.get(0).size();
        assertTrue(compaction.notices().contains("Context compacted (" + summarised + " messages summarised)"),
                   compaction.notices().toString());
        assertEquals(ChatRole.USER, history.get(0).role());
        assertEquals(TurnContext.SUMMARY_PREFIX + "S1", history.get(0).content());
        assertEquals(1, history.stream().filter(m -> m.content() != null
                     && m.content().startsWith(TurnContext.SUMMARY_PREFIX)).count(), "ONE summary message");
        assertEquals("now", history.get(history.size() - 1).content(), "the current prompt is kept verbatim");
        assertEquals("S1", context.summary());
        assertEquals("S1", hints.summary, "the Summary button shows it");
        assertFalse(hints.trimmed);
    }

    @Test
    @Timeout(5)
    void autoDropsBelowTheThreshold() {
        FakeRunner runner = new FakeRunner(ok("unused"));
        context.beginTurn(config(CompactionStrategyEnum.AUTO, 8192), runner);
        List<ChatMessage> history = history(12);

        TurnContext.Compaction compaction = context.compact(history, "SYSTEM", false);

        assertTrue(compaction.changed());
        assertTrue(runner.calls.isEmpty(), "below the summarise threshold Auto drops");
        assertTrue(compaction.notices().stream().anyMatch(n -> n.startsWith("Context trimmed (")));
        assertNull(hints.summary);
        assertTrue(hints.trimmed, "the Summary button says the context was trimmed");
    }

    @Test
    @Timeout(5)
    void autoOnAnUnconfirmedWindowDropsEvenWhenForced() {
        FakeRunner runner = new FakeRunner(ok("unused"));
        TurnContext.Config assumedLarge = new TurnContext.Config(CompactionStrategyEnum.AUTO, true, true, 0, 20000,
                                                                 6144, "GENERIC", "m", "http://h");
        context.beginTurn(assumedLarge, runner);

        assertEquals(ResolvedCompactionEnum.DROP, context.resolve(context.window()));
        context.compact(history(30), "SYSTEM", true);

        assertTrue(runner.calls.isEmpty());
    }

    @Test
    @Timeout(5)
    void anExplicitSummariseSettingSummarisesBelowTheThreshold() {
        FakeRunner runner = new FakeRunner(ok("S"));
        context.beginTurn(config(CompactionStrategyEnum.SUMMARISE, 8192), runner);

        context.compact(history(12), "SYSTEM", false);

        assertEquals(1, runner.calls.size());
    }

    @Test
    @Timeout(5)
    void withoutASummaryModelForTheTurnItDrops() {
        context.beginTurn(config(CompactionStrategyEnum.SUMMARISE, 16384), null);

        assertEquals(ResolvedCompactionEnum.DROP, context.resolve(context.window()));
    }

    @Test
    @Timeout(5)
    void aFailedSummaryFallsBackToDrop() {
        FakeRunner runner = new FakeRunner(failed());
        context.beginTurn(config(CompactionStrategyEnum.AUTO, 16384), runner);
        List<ChatMessage> history = history(24);
        int before = history.size();

        TurnContext.Compaction compaction = context.compact(history, "SYSTEM", false);

        assertEquals(1, runner.calls.size());
        assertTrue(compaction.changed());
        assertTrue(history.size() < before);
        assertTrue(compaction.notices().stream().anyMatch(n -> n.startsWith("Context trimmed (")),
                   compaction.notices().toString());
        assertTrue(compaction.notices().stream().anyMatch(n -> n.startsWith("Couldn't summarise (summary request failed); dropped ")),
                   compaction.notices().toString());
        assertNull(context.summary());
        assertTrue(hints.trimmed);
    }

    @Test
    @Timeout(5)
    void aFailedSummaryIncludesItsReasonInTheFallbackNotice() {
        FakeRunner runner = new FakeRunner(new Summariser.Result(false, null, "HTTP 503: overloaded"));
        context.beginTurn(config(CompactionStrategyEnum.AUTO, 16384), runner);
        TurnContext.Compaction compaction = context.compact(history(24), "SYSTEM", false);

        assertTrue(compaction.notices().stream().anyMatch(n -> n.contains("HTTP 503: overloaded")),
                   compaction.notices().toString());
    }

    @Test
    @Timeout(5)
    void aLaterSummaryUpdatesThePreviousOneAndReplacesItsMessage() {
        FakeRunner runner = new FakeRunner(ok("S1"), ok("S2"));
        context.beginTurn(config(CompactionStrategyEnum.AUTO, 16384), runner);
        List<ChatMessage> history = history(24);
        context.compact(history, "SYSTEM", false);
        ChatMessage firstSummary = history.get(0);
        for (int i = 25; i <= 48; i++) {
            history.add(new ChatMessage(ChatRole.ASSISTANT, "b".repeat(3000), List.of(), null));
            history.add(new ChatMessage(ChatRole.USER, "question " + i, List.of(), null));
        }

        context.compact(history, "SYSTEM", false);

        assertEquals(2, runner.calls.size());
        assertEquals("S1", runner.previous.get(1), "update mode builds on the previous summary");
        assertTrue(runner.calls.get(1).stream().noneMatch(m -> m == firstSummary),
                   "the old summary message is not summarised again");
        assertEquals(TurnContext.SUMMARY_PREFIX + "S2", history.get(0).content());
        assertEquals(1, history.stream().filter(m -> m.content() != null
                     && m.content().startsWith(TurnContext.SUMMARY_PREFIX)).count());
    }

    @Test
    @Timeout(5)
    void theFreshContextAfterASwitchCarriesTheSummary() {
        assertTrue(context.freshHistory().isEmpty(), "no summary yet: an empty context");
        FakeRunner runner = new FakeRunner(ok("S1"));
        context.beginTurn(config(CompactionStrategyEnum.AUTO, 16384), runner);
        context.compact(history(24), "SYSTEM", false);

        List<ChatMessage> fresh = context.freshHistory();

        assertEquals(1, fresh.size());
        assertSame(ChatRole.USER, fresh.get(0).role());
        assertEquals(TurnContext.SUMMARY_PREFIX + "S1", fresh.get(0).content());
    }
}

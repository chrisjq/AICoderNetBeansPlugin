package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.TokenEstimator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.CompactionStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.SessionDecisions;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.HintState.HintKind;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TurnContextTest {

    /**
     * Records the hint slots as the info bar would hold them.
     */
    private static final class Hints implements TurnContext.HintSink {

        final Map<HintKind, String> active = new EnumMap<>(HintKind.class);

        @Override
        public void set(HintKind kind, String text, String tooltip) {
            active.put(kind, tooltip);
        }

        @Override
        public void clear(HintKind kind) {
            active.remove(kind);
        }
    }

    private final Hints hints = new Hints();
    private final SessionDecisions decisions = new SessionDecisions("g1");
    private final TurnContext context = new TurnContext(new TokenEstimator(), decisions, hints);

    private static TurnContext.Config config(CompactionStrategyEnum strategy, boolean auto, int override,
                                             String kind) {
        return new TurnContext.Config(strategy, auto, true, override, 8192, 6144, kind, "model-x", "http://h:1");
    }

    private static TurnContext.Config config(int override) {
        return config(CompactionStrategyEnum.AUTO, true, override, "GENERIC");
    }

    /**
     * {@code turns} old exchanges with a 3 000-character answer each, then the current prompt.
     */
    private static List<ChatMessage> longHistory(int turns) {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 1; i <= turns; i++) {
            history.add(new ChatMessage(ChatRole.USER, "question " + i, List.of(), null));
            history.add(new ChatMessage(ChatRole.ASSISTANT, "a".repeat(3000), List.of(), null));
        }
        history.add(new ChatMessage(ChatRole.USER, "now", List.of(), null));
        return history;
    }

    private static void addToolTurn(List<ChatMessage> history, int n, int resultChars) {
        history.add(new ChatMessage(ChatRole.USER, "read " + n, List.of(), null));
        history.add(new ChatMessage(ChatRole.ASSISTANT, null,
                                    List.of(new ChatToolCall("c" + n, "Read", "{}")), null));
        history.add(new ChatMessage(ChatRole.TOOL, "r".repeat(resultChars), List.of(), "c" + n));
    }

    // ---- window ----
    @Test
    @Timeout(5)
    void theOverrideIsAConfirmedWindowAndTheAssumedOneIsNot() {
        context.beginTurn(config(0));
        WindowReading assumed = context.window();
        assertEquals(8192, assumed.tokens());
        assertFalse(assumed.confirmed());

        context.beginTurn(config(16384));

        assertEquals(16384, context.window().tokens());
        assertTrue(context.window().confirmed());
    }

    @Test
    @Timeout(5)
    void aStatedLimitIsLearnedAsASessionDecisionAndConfirmsTheWindow() {
        context.beginTurn(config(0));

        context.learnWindow(4096);

        assertEquals(4096, decisions.learnedWindow());
        assertEquals(4096, context.window().tokens());
        assertTrue(context.window().confirmed());
        assertEquals(WindowReading.Source.SERVER_ERROR, context.window().source());
    }

    // ---- compaction ----
    @Test
    @Timeout(5)
    void automaticCompactionWaitsForAConfirmedWindowButManualCompactRuns() {
        context.beginTurn(config(0));
        List<ChatMessage> history = longHistory(12);

        assertFalse(context.compact(history, "SYSTEM", false).changed(), "assumed window: no automatic compaction");

        TurnContext.Compaction manual = context.compact(history, "SYSTEM", true);

        assertTrue(manual.changed());
        assertTrue(manual.notices().stream().anyMatch(n -> n.startsWith("Context trimmed (")
                && n.endsWith(" — window assumed: 8192")), manual.notices().toString());
    }

    @Test
    @Timeout(5)
    void dropTrimsTheOldestExchangesAndTellsTheModel() {
        context.beginTurn(config(4096));
        List<ChatMessage> history = longHistory(6);
        int before = history.size();

        TurnContext.Compaction compaction = context.compact(history, "SYSTEM", false);

        assertTrue(compaction.changed());
        assertTrue(history.size() < before);
        assertTrue(history.get(0).content().startsWith("["), history.get(0).content());
        assertTrue(history.get(0).content().contains("earlier exchanges were trimmed"));
        assertEquals("now", history.get(history.size() - 1).content(), "the current prompt is kept");
        assertTrue(compaction.notices().stream().anyMatch(n -> n.startsWith("Context trimmed (")
                && !n.contains("window assumed")), compaction.notices().toString());
    }

    @Test
    @Timeout(5)
    void thePruneNoticeIsPostedOncePerSession() {
        context.beginTurn(config(4096));
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            addToolTurn(history, i, 5000);
        }
        history.add(new ChatMessage(ChatRole.USER, "now", List.of(), null));

        TurnContext.Compaction first = context.compact(history, "SYSTEM", false);

        assertTrue(first.changed());
        assertTrue(first.notices().contains(TurnContext.PRUNE_NOTICE), first.notices().toString());
        assertTrue(history.stream().anyMatch(m -> m.content() != null && m.content().contains("[pruned: ")));

        for (int i = 4; i <= 6; i++) {
            addToolTurn(history, i, 5000);
        }
        history.add(new ChatMessage(ChatRole.USER, "again", List.of(), null));
        TurnContext.Compaction second = context.compact(history, "SYSTEM", false);

        assertTrue(second.changed());
        assertFalse(second.notices().contains(TurnContext.PRUNE_NOTICE), "only the first prune says so");
    }

    @Test
    @Timeout(5)
    void noneNeverCompactsEvenOnDemand() {
        context.beginTurn(config(CompactionStrategyEnum.NONE, true, 4096, "GENERIC"));
        List<ChatMessage> history = longHistory(8);
        int before = history.size();

        assertFalse(context.compact(history, "SYSTEM", true).changed());
        assertEquals(before, history.size());
        assertTrue(context.compactionOff());
    }

    @Test
    @Timeout(5)
    void autoCompactOffLeavesOnlyManualCompact() {
        context.beginTurn(config(CompactionStrategyEnum.DROP, false, 4096, "GENERIC"));
        List<ChatMessage> history = longHistory(8);

        assertFalse(context.compact(history, "SYSTEM", false).changed());
        assertTrue(context.compact(history, "SYSTEM", true).changed());
    }

    // ---- overhead ----
    @Test
    @Timeout(5)
    void aWindowBelowTheMinimumForToolsNamesTheServersRemedy() {
        context.beginTurn(config(CompactionStrategyEnum.AUTO, true, 4096, "LLAMA_CPP"));

        String message = context.overheadProblem(5000, 4500, true);

        assertEquals("Context window 4096 is too small for tools — raise it to at least 8192 (recommended; 6144 is "
                + "the minimum). Start it with -c.", message);
        assertEquals(message, hints.active.get(HintKind.TOOLS_DO_NOT_FIT), "hint rank 3");
    }

    @Test
    @Timeout(5)
    void toolsTooBigForALargeWindowSuggestTheMinimalTemplate() {
        context.beginTurn(config(16384));

        String message = context.overheadProblem(20000, 18000, true);

        assertTrue(message.startsWith("Tool definitions alone need ~20000 tokens of your 16384-token window. Apply "
                + "the 'Minimal' MCP template"), message);
        assertTrue(hints.active.containsKey(HintKind.TOOLS_DO_NOT_FIT));
    }

    @Test
    @Timeout(5)
    void anOverheadThatFitsClearsTheHintAndAnUnconfirmedWindowIsNotChecked() {
        context.beginTurn(config(16384));
        context.overheadProblem(20000, 18000, true);

        assertNull(context.overheadProblem(100, 50, true));
        assertFalse(hints.active.containsKey(HintKind.TOOLS_DO_NOT_FIT));

        context.beginTurn(config(0));
        assertNull(context.overheadProblem(100_000, 90_000, true), "the assumed window is never held to the check");
    }

    // ---- usage and the truncation guard ----
    @Test
    @Timeout(5)
    void aReportAboveTheWindowOnANonTruncatingServerCompactsNext() {
        context.beginTurn(config(4096));

        assertFalse(context.onUsage(3000, 2900).compactNext());
        assertTrue(context.onUsage(5000, 3100).compactNext());
    }

    @Test
    @Timeout(5)
    void anOllamaServerCuttingThePromptCompactsOnceThenPausesWithTheRemedy() {
        TurnContext.Config ollama = config(CompactionStrategyEnum.AUTO, true, 8192, "OLLAMA");
        context.beginTurn(ollama);
        TurnContext.UsageVerdict first = context.onUsage(8190, 7000);
        assertTrue(first.compactNext());
        assertNull(first.message());

        context.beginTurn(ollama);
        TurnContext.UsageVerdict second = context.onUsage(8191, 7500);

        assertFalse(second.compactNext());
        assertTrue(second.message().startsWith("The server seems to cut the prompt at about 8191 tokens"),
                   second.message());
        assertTrue(second.message().contains("OLLAMA_CONTEXT_LENGTH"), second.message());
        assertTrue(context.truncationPaused());
        assertTrue(hints.active.containsKey(HintKind.SERVER_CUTTING_PROMPT), "hint rank 4");
        List<ChatMessage> history = longHistory(12);
        assertFalse(context.compact(history, "SYSTEM", false).changed(), "automatic compaction is paused");

        context.beginTurn(config(CompactionStrategyEnum.AUTO, true, 4096, "OLLAMA"));

        assertFalse(context.truncationPaused(), "lifted when the override changes");
        assertFalse(hints.active.containsKey(HintKind.SERVER_CUTTING_PROMPT));
    }

    @Test
    @Timeout(5)
    void reportedUsageNearUsableWindowTriggersCompactionBeforeServerLimit() {
        context.beginTurn(config(CompactionStrategyEnum.AUTO, true, 150_000, "LM_STUDIO"));
        assertTrue(context.onUsage(133_310, 120_000).compactNext(),
                   "15% reserve makes the usable prompt about 127.5k");
    }

    @Test
    @Timeout(5)
    void lmStudioThresholdCompactsAndCalibratesWhenGuardTrustsReport() {
        TokenEstimator estimator = new TokenEstimator();
        TurnContext local = new TurnContext(estimator, new SessionDecisions("lm"), hints);
        local.beginTurn(config(CompactionStrategyEnum.AUTO, true, 150_000, "LM_STUDIO"));
        assertTrue(local.onUsage(133_310, 120_000).compactNext());
        assertTrue(estimator.calibrationRatio() > 1.0d, "guard CALIBRATE path updates the estimator");
    }

    @Test
    @Timeout(5)
    void genericThresholdCompactsAndCalibrates() {
        TokenEstimator estimator = new TokenEstimator();
        TurnContext local = new TurnContext(estimator, new SessionDecisions("generic"), hints);
        local.beginTurn(config(CompactionStrategyEnum.AUTO, true, 150_000, "GENERIC"));
        assertTrue(local.onUsage(133_310, 120_000).compactNext());
        assertTrue(estimator.calibrationRatio() > 1.0d);
    }

    @Test
    @Timeout(5)
    void lmStudioIsGuardedButLlamaCppIsNot() {
        context.beginTurn(config(CompactionStrategyEnum.AUTO, true, 8192, "LM_STUDIO"));
        assertTrue(context.onUsage(8192, 7000).compactNext(), "possible truncation still requests compaction");

        TurnContext other = new TurnContext(new TokenEstimator(), new SessionDecisions("g2"), hints);
        other.beginTurn(config(CompactionStrategyEnum.AUTO, true, 8192, "LLAMA_CPP"));
        assertTrue(other.onUsage(8192, 7000).compactNext(), "reserve threshold now triggers compaction at the window");
    }
}

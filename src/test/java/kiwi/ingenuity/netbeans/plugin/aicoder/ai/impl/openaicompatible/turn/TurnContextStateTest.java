package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.TokenEstimator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.CompactionStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.ResolvedCompactionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.SessionDecisions;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowSources;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.HintState.HintKind;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TurnContextStateTest {

    private static final class RecordingHints implements TurnContext.HintSink {

        final List<String> summaries = new ArrayList<>();

        @Override
        public void set(HintKind kind, String text, String tooltip) {
        }

        @Override
        public void clear(HintKind kind) {
        }

        @Override
        public void summary(String summaryOrNull, boolean trimmedNotSummarised) {
            summaries.add(summaryOrNull);
        }
    }

    private static TurnContext context(RecordingHints hints) {
        return new TurnContext(new TokenEstimator(), new SessionDecisions(""), hints);
    }

    private static JsonObject savedState() {
        JsonObject state = new JsonObject();
        state.addProperty("compactionSummary", "Goal: ship it.");
        state.addProperty("calibrationRatio", 1.4);
        state.addProperty("pruneNoticeShown", true);
        state.addProperty("prunedCount", 5);
        JsonArray messages = new JsonArray();
        JsonObject summary = new JsonObject();
        summary.addProperty("role", "USER");
        summary.addProperty("content", TurnContext.SUMMARY_PREFIX + "Goal: ship it.");
        messages.add(summary);
        JsonObject question = new JsonObject();
        question.addProperty("role", "USER");
        question.addProperty("content", "and then?");
        messages.add(question);
        JsonObject answer = new JsonObject();
        answer.addProperty("role", "ASSISTANT");
        answer.addProperty("content", "[pruned: 9000 chars]");
        messages.add(answer);
        state.add("messages", messages);
        return state;
    }

    @Test
    @Timeout(5)
    void importRestoresTheHistoryTheSummaryAndTheBookkeeping() {
        RecordingHints hints = new RecordingHints();
        TurnContext context = context(hints);

        List<ChatMessage> history = context.importState(savedState());

        assertEquals(3, history.size());
        assertEquals("[pruned: 9000 chars]", history.get(2).content());
        assertEquals("Goal: ship it.", context.summary());
        assertEquals(5, context.prunedCount());
        assertEquals(List.of("Goal: ship it."), hints.summaries, "the Summary button is told");
    }

    @Test
    @Timeout(5)
    void exportRoundTripsEverythingThatImportRestored() {
        TurnContext first = context(new RecordingHints());
        List<ChatMessage> history = first.importState(savedState());

        JsonObject exported = first.exportState(history);
        TurnContext second = context(new RecordingHints());
        List<ChatMessage> again = second.importState(exported);

        assertEquals("Goal: ship it.", exported.get("compactionSummary").getAsString());
        assertTrue(exported.get("pruneNoticeShown").getAsBoolean());
        assertEquals(5, exported.get("prunedCount").getAsInt());
        assertEquals(history.size(), again.size());
        for (int i = 0; i < history.size(); i++) {
            assertEquals(history.get(i).role(), again.get(i).role());
            assertEquals(history.get(i).content(), again.get(i).content());
        }
        assertEquals("Goal: ship it.", second.summary());
        assertEquals(5, second.prunedCount());
        assertEquals(exported.get("calibrationRatio").getAsDouble(),
                     second.exportState(again).get("calibrationRatio").getAsDouble(), 1e-9);
    }

    @Test
    @Timeout(5)
    void toolCallsAndResultsSurviveTheRoundTrip() {
        TurnContext context = context(new RecordingHints());
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage(ChatRole.USER, "read it", List.of(), null));
        history.add(new ChatMessage(ChatRole.ASSISTANT, null,
                                    List.of(new ChatToolCall("call_1", "GetFileContent", "{\"path\":\"a\"}")), null));
        history.add(new ChatMessage(ChatRole.TOOL, "contents", List.of(), "call_1"));

        List<ChatMessage> restored = context(new RecordingHints()).importState(context.exportState(history));

        assertEquals(3, restored.size());
        assertEquals("call_1", restored.get(1).toolCalls().get(0).id());
        assertEquals("GetFileContent", restored.get(1).toolCalls().get(0).name());
        assertEquals("call_1", restored.get(2).toolCallId());
        assertEquals("contents", restored.get(2).content());
    }

    @Test
    @Timeout(5)
    void anEmptyOrMissingStateRestoresNothingAndNoSummary() {
        RecordingHints hints = new RecordingHints();
        TurnContext context = context(hints);

        assertTrue(context.importState(new JsonObject()).isEmpty());
        assertNull(context.summary());
        assertEquals(0, context.prunedCount());

        assertTrue(context.importState(null).isEmpty());
        assertNull(context.summary());
        assertFalse(hints.summaries.isEmpty());
        assertNull(hints.summaries.get(hints.summaries.size() - 1), "no summary leaves the button off");
    }

    @Test
    @Timeout(5)
    void aSummaryWithoutItsMessageIsKeptForTheButton() {
        JsonObject state = new JsonObject();
        state.addProperty("compactionSummary", "Only the slot.");
        JsonArray messages = new JsonArray();
        JsonObject question = new JsonObject();
        question.addProperty("role", "USER");
        question.addProperty("content", "hello");
        messages.add(question);
        state.add("messages", messages);
        TurnContext context = context(new RecordingHints());

        List<ChatMessage> history = context.importState(state);

        assertEquals(1, history.size());
        assertEquals("Only the slot.", context.summary());
    }

    @Test
    @Timeout(5)
    void theResolvedStrategyFollowsTheSettingsAndTheWindow() {
        TurnContext context = context(new RecordingHints());

        TurnContext.Config none = new TurnContext.Config(CompactionStrategyEnum.NONE, true, true, 0, 8192, 6144,
                                                         "GENERIC", "m", "u");
        TurnContext.Config summarise = new TurnContext.Config(CompactionStrategyEnum.SUMMARISE, true, true, 0, 8192,
                                                              6144, "GENERIC", "m", "u");
        TurnContext.Config autoSmall = new TurnContext.Config(CompactionStrategyEnum.AUTO, true, true, 4096, 8192,
                                                              6144, "GENERIC", "m", "u");
        TurnContext.Config autoLarge = new TurnContext.Config(CompactionStrategyEnum.AUTO, true, true, 131072, 8192,
                                                              6144, "GENERIC", "m", "u");
        TurnContext.Config drop = new TurnContext.Config(CompactionStrategyEnum.DROP, true, true, 131072, 8192, 6144,
                                                         "GENERIC", "m", "u");

        assertEquals(ResolvedCompactionEnum.NONE, context.resolvedStrategy(none));
        assertEquals(ResolvedCompactionEnum.SUMMARISE, context.resolvedStrategy(summarise));
        assertEquals(ResolvedCompactionEnum.DROP, context.resolvedStrategy(autoSmall),
                     "Auto drops against a small confirmed window");
        assertEquals(ResolvedCompactionEnum.SUMMARISE, context.resolvedStrategy(autoLarge),
                     "Auto summarises against a large confirmed window");
        assertEquals(ResolvedCompactionEnum.DROP, context.resolvedStrategy(drop));
    }

    private static TurnContext.Config detectedConfig(String model, String url) {
        return new TurnContext.Config(CompactionStrategyEnum.AUTO, true, true, 0, 8192, 6144, "VLLM", model, url);
    }

    private static TurnContext contextReading(int[] window) {
        TurnContext context = context(new RecordingHints());
        context.setWindowSources(() -> new WindowSources() {
            @Override
            public String kindName() {
                return "VLLM";
            }

            @Override
            public Integer vllmMaxModelLen() {
                return window[0];
            }
        });
        return context;
    }

    @Test
    @Timeout(5)
    void aSmallerDetectedWindowOnlyTakesEffectAfterTheConfiguredNumberOfReadings() {
        int needed = OpenAiCompatiblePluginSettings.getWindowReadingsBeforeShrink();
        int[] window = {16384};
        TurnContext context = contextReading(window);
        TurnContext.Config config = detectedConfig("m", "u");
        context.beginTurn(config);
        assertEquals(16384, context.window().tokens());

        window[0] = 4096;
        for (int i = 1; i < needed; i++) {
            context.beginTurn(config);
            assertEquals(16384, context.window().tokens(), "one smaller reading keeps the old window");
            assertTrue(context.window().confirmed());
        }
        context.beginTurn(config);
        assertEquals(4096, context.window().tokens(), "the configured number of smaller readings shrinks it");
    }

    @Test
    @Timeout(5)
    void aModelChangeAndAnOverrideResetTheRememberedWindow() {
        int[] window = {16384};
        TurnContext context = contextReading(window);
        context.beginTurn(detectedConfig("m", "u"));

        window[0] = 4096;
        context.beginTurn(detectedConfig("other", "u"));
        assertEquals(4096, context.window().tokens(), "a new model starts from its own reading");

        window[0] = 16384;
        context.beginTurn(detectedConfig("other", "u"));
        context.beginTurn(new TurnContext.Config(CompactionStrategyEnum.AUTO, true, true, 2048, 8192, 6144, "VLLM",
                                                 "other", "u"));
        assertEquals(2048, context.window().tokens(), "an override takes effect at once");
        window[0] = 4096;
        context.beginTurn(detectedConfig("other", "u"));
        assertEquals(4096, context.window().tokens(), "dropping the override forgot the earlier reading");
    }
}

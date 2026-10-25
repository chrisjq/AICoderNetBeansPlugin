package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.TokenEstimator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ContextHistoryTest {

    private static ChatMessage user(String text) {
        return new ChatMessage(ChatRole.USER, text, List.of(), null);
    }

    private static ChatMessage assistant(String text) {
        return new ChatMessage(ChatRole.ASSISTANT, text, List.of(), null);
    }

    private static ChatMessage assistantCall(String id, String name) {
        return new ChatMessage(ChatRole.ASSISTANT, null,
                               List.of(new ChatToolCall(id, name, "{\"x\":1}")), null);
    }

    private static ChatMessage toolResult(String id, String text) {
        return new ChatMessage(ChatRole.TOOL, text, List.of(), id);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void turnsGroupEachUserMessageWithItsFollowingMessages() {
        ContextHistory h = new ContextHistory("pinned");
        h.append(user("one"));
        h.append(assistant("a1"));
        h.append(user("two"));
        h.append(assistant("a2"));

        List<List<ChatMessage>> turns = h.turns();
        assertEquals(2, turns.size());
        assertEquals(List.of("one", "a1"), turns.get(0).stream().map(ChatMessage::content).toList());
        assertEquals(List.of("two", "a2"), turns.get(1).stream().map(ChatMessage::content).toList());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void turnsNeverSplitToolCallFromItsResult() {
        ContextHistory h = new ContextHistory();
        h.append(user("do it"));
        h.append(assistantCall("c1", "GetFileContent"));
        h.append(toolResult("c1", "file body"));
        h.append(assistant("done"));

        List<List<ChatMessage>> turns = h.turns();
        assertEquals(1, turns.size());
        List<ChatMessage> only = turns.get(0);
        assertEquals(4, only.size());
        assertEquals(ChatRole.ASSISTANT, only.get(1).role());
        assertEquals(ChatRole.TOOL, only.get(2).role());
        assertEquals("c1", only.get(1).toolCalls().get(0).id());
        assertEquals("c1", only.get(2).toolCallId());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void leadingMessagesBeforeFirstUserFormTheirOwnTurn() {
        ContextHistory h = new ContextHistory();
        h.append(assistant("pre"));
        h.append(user("q"));
        h.append(assistant("a"));

        List<List<ChatMessage>> turns = h.turns();
        assertEquals(2, turns.size());
        assertEquals(1, turns.get(0).size());
        assertEquals("pre", turns.get(0).get(0).content());
        assertEquals(2, turns.get(1).size());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void emptyHistoryHasNoTurns() {
        assertTrue(new ContextHistory().turns().isEmpty());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void toRequestMessagesPutsPinnedSystemPromptFirst() {
        ContextHistory h = new ContextHistory("SYS");
        h.append(user("hi"));

        List<ChatMessage> wire = h.toRequestMessages();
        assertEquals(2, wire.size());
        assertEquals(ChatRole.SYSTEM, wire.get(0).role());
        assertEquals("SYS", wire.get(0).content());
        assertEquals("hi", wire.get(1).content());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void toRequestMessagesOmitsSystemPromptWhenUnset() {
        ContextHistory h = new ContextHistory();
        h.append(user("hi"));
        assertEquals(1, h.toRequestMessages().size());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void estimateTokensCountsPinnedAndMessagesAndSummary() {
        TokenEstimator estimator = new TokenEstimator();
        ContextHistory h = new ContextHistory("a".repeat(35));
        h.append(user("b".repeat(35)));

        int pinnedOnly = new TokenEstimator().estimate(new ChatMessage(ChatRole.SYSTEM, "a".repeat(35), List.of(), null));
        int messageOnly = estimator.estimate(user("b".repeat(35)));
        assertEquals(pinnedOnly + messageOnly, h.estimateTokens(estimator));

        int summary = estimator.estimate(new ChatMessage(ChatRole.SYSTEM, "c".repeat(35), List.of(), null));
        h.setCompactionSummary("c".repeat(35));
        assertEquals(pinnedOnly + messageOnly + summary, h.estimateTokens(estimator));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void estimateTokensHandlesNullEstimator() {
        ContextHistory h = new ContextHistory("sys");
        h.append(user("hi"));
        assertEquals(0, h.estimateTokens(null));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void roundTripPreservesPinnedSummaryAndMessages() {
        ContextHistory h = new ContextHistory("SYSTEM PROMPT");
        h.append(user("q1"));
        h.append(assistantCall("c1", "Tool"));
        h.append(toolResult("c1", "body"));
        h.append(assistant("a1"));
        h.setCompactionSummary("summary text");

        JsonObject json = h.toJson(new TokenEstimator());
        ContextHistory back = ContextHistory.fromJson(json, new TokenEstimator());

        assertEquals("SYSTEM PROMPT", back.pinned());
        assertEquals("summary text", back.compactionSummary());
        assertEquals(h.size(), back.size());
        for (int i = 0; i < h.size(); i++) {
            ChatMessage a = h.messages().get(i);
            ChatMessage b = back.messages().get(i);
            assertEquals(a.role(), b.role(), "role at " + i);
            assertEquals(a.content(), b.content(), "content at " + i);
            assertEquals(a.toolCallId(), b.toolCallId(), "toolCallId at " + i);
            assertEquals(a.toolCalls(), b.toolCalls(), "toolCalls at " + i);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void roundTripPreservesPrunePlaceholderVerbatim() {
        ContextHistory h = new ContextHistory();
        String placeholder = "head\n[pruned: 3800 chars]";
        h.append(toolResult("c1", placeholder));

        ContextHistory back = ContextHistory.fromJson(h.toJson(new TokenEstimator()), new TokenEstimator());
        assertEquals(placeholder, back.messages().get(0).content());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void roundTripRestoresCalibrationRatio() {
        TokenEstimator source = new TokenEstimator();
        source.calibrate(100, 180);
        double ratio = source.calibrationRatio();
        assertTrue(ratio > 1.0d, "ratio should have moved above 1.0");

        ContextHistory h = new ContextHistory("sys");
        h.append(user("hi"));
        JsonObject json = h.toJson(source);

        TokenEstimator target = new TokenEstimator();
        ContextHistory.fromJson(json, target);
        assertEquals(ratio, target.calibrationRatio(), 1e-9);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void fromJsonToleratesNullAndGarbage() {
        assertNotNull(ContextHistory.fromJson((JsonObject) null, new TokenEstimator()));
        assertNotNull(ContextHistory.fromJson("", new TokenEstimator()));
        assertEquals(0, ContextHistory.fromJson("{not json", new TokenEstimator()).size());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void fromJsonSkipsMessagesWithUnknownRole() {
        JsonObject root = new JsonObject();
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        JsonObject good = new JsonObject();
        good.addProperty("role", "USER");
        good.addProperty("content", "keep");
        JsonObject bad = new JsonObject();
        bad.addProperty("role", "BOGUS");
        bad.addProperty("content", "drop");
        array.add(good);
        array.add(bad);
        root.add("messages", array);

        ContextHistory back = ContextHistory.fromJson(root, new TokenEstimator());
        assertEquals(1, back.size());
        assertEquals("keep", back.messages().get(0).content());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void fromJsonLeavesSummaryAndPinnedAbsentWhenMissing() {
        ContextHistory back = ContextHistory.fromJson(new JsonObject(), new TokenEstimator());
        assertNull(back.pinned());
        assertNull(back.compactionSummary());
        assertFalse(back.messages().iterator().hasNext());
    }
}

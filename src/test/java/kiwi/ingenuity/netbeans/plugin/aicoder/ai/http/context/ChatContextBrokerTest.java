package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ChatContextBrokerTest {

    private static ChatMessage user(String text) {
        return new ChatMessage(ChatRole.USER, text, List.of(), null);
    }

    @Test
    void pinnedSlotsRenderAsOneLeadingSystemMessageInSlotOrder() {
        TestBroker b = new TestBroker();
        b.upsertPin(PinSlotEnum.TOOLS, "TOOLS-TEXT");
        b.upsertPin(PinSlotEnum.IDENTITY, "IDENTITY-TEXT");
        b.upsertPin(PinSlotEnum.BASELINE, "BASELINE-TEXT");

        List<ChatMessage> snap = b.snapshot();

        assertEquals(1, snap.size());
        assertEquals(ChatRole.SYSTEM, snap.get(0).role());
        String content = snap.get(0).content();
        assertTrue(content.indexOf("IDENTITY-TEXT") < content.indexOf("BASELINE-TEXT"));
        assertTrue(content.indexOf("BASELINE-TEXT") < content.indexOf("TOOLS-TEXT"),
                "render order must follow PinSlotEnum, not insertion order");
    }

    @Test
    void blankPinsAreOmitted() {
        TestBroker b = new TestBroker();
        b.upsertPin(PinSlotEnum.IDENTITY, "ID");
        b.upsertPin(PinSlotEnum.BASELINE, "   ");

        assertEquals("ID", b.snapshot().get(0).content().trim());
    }

    @Test
    void upsertReplacesInPlaceRatherThanAccumulating() {
        TestBroker b = new TestBroker();
        b.upsertPin(PinSlotEnum.BASELINE, "file A open");
        b.upsertPin(PinSlotEnum.BASELINE, "file B open");

        List<ChatMessage> snap = b.snapshot();
        assertEquals(1, snap.size());
        assertTrue(snap.get(0).content().contains("file B open"));
        assertTrue(!snap.get(0).content().contains("file A open"));
    }

    @Test
    void unchangedUpsertIsANoOp() {
        TestBroker b = new TestBroker();
        b.upsertPin(PinSlotEnum.IDENTITY, "same");
        String first = b.snapshot().get(0).content();
        b.upsertPin(PinSlotEnum.IDENTITY, "same");

        assertEquals(first, b.snapshot().get(0).content(),
                "an unchanged upsert must leave the rendered prefix byte-identical");
    }

    @Test
    void appendedMessagesFollowThePinnedBlockInSequenceOrder() {
        TestBroker b = new TestBroker();
        b.upsertPin(PinSlotEnum.IDENTITY, "ID");
        b.beginTurn();
        b.append(user("first"));
        b.append(user("second"));
        b.commitTurn();

        List<ChatMessage> snap = b.snapshot();
        assertEquals(3, snap.size());
        assertEquals(ChatRole.SYSTEM, snap.get(0).role());
        assertEquals("first", snap.get(1).content());
        assertEquals("second", snap.get(2).content());
    }

    @Test
    void snapshotReturnsCopiesThatLaterMutationCannotAffect() {
        TestBroker b = new TestBroker();
        b.beginTurn();
        b.append(user("original"));
        b.commitTurn();

        List<ChatMessage> snap = b.snapshot();
        b.upsertPin(PinSlotEnum.IDENTITY, "added afterwards");

        assertEquals("original", snap.get(0).content());
        assertNotSame(snap.get(0), b.snapshot().get(1));
    }

    @Test
    void entryCountIgnoresPinnedSlots() {
        TestBroker b = new TestBroker();
        b.upsertPin(PinSlotEnum.IDENTITY, "ID");
        assertEquals(0, b.entryCount());

        b.beginTurn();
        b.append(user("hi"));
        b.commitTurn();
        assertEquals(1, b.entryCount());
    }

    @Test
    void clearHistoryDropsEvictableEntriesButKeepsPins() {
        TestBroker b = new TestBroker();
        b.upsertPin(PinSlotEnum.IDENTITY, "ID");
        b.beginTurn();
        b.append(user("forget me"));
        b.commitTurn();

        b.clearHistory();

        assertEquals(0, b.entryCount());
        List<ChatMessage> snap = b.snapshot();
        assertEquals(1, snap.size());
        assertEquals(ChatRole.SYSTEM, snap.get(0).role());
        assertTrue(snap.get(0).content().contains("ID"));
    }

    @Test
    void evictionNeverTouchesTheInFlightGroup() {
        TestBroker b = new TestBroker();
        b.beginTurn();
        b.append(user("turn one"));
        b.commitTurn();

        b.beginTurn();
        b.append(user("in flight"));

        assertTrue(b.evictOldestCommittedGroup(), "the committed group is fair game");
        assertFalse(b.evictOldestCommittedGroup(), "only the in-flight group remains");
        assertEquals(1, b.entryCount());
        assertEquals("in flight", b.snapshot().get(0).content());
    }

    @Test
    void evictionRemovesAWholeGroupAtOnce() {
        TestBroker b = new TestBroker();
        b.beginTurn();
        b.append(user("q"));
        b.append(new ChatMessage(ChatRole.ASSISTANT, null,
                List.of(new ChatToolCall("call_0", "T", "{}")), null));
        b.append(new ChatMessage(ChatRole.TOOL, "r", List.of(), "call_0"));
        b.commitTurn();

        assertTrue(b.evictOldestCommittedGroup());

        assertEquals(0, b.entryCount(),
                "an assistant tool_calls message must never outlive its TOOL results");
    }

    /**
     * FIX 1 — two consecutive ASSISTANT messages in one turn (a narration round followed by a tool-calling
     * round) must fold into a single entry: content joined, tool calls unioned, none lost.
     */
    @Test
    void appendAssistantFoldsConsecutiveAssistantMessagesInTheSameTurn() {
        TestBroker b = new TestBroker();
        b.beginTurn();
        b.append(user("hi"));
        b.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, "I will check", List.of(), null));
        b.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, "off I go",
                List.of(new ChatToolCall("call_0_0", "GetPluginVersion", "{}")), null));

        List<ChatMessage> assistants = b.snapshot().stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT).toList();
        assertEquals(1, assistants.size(), "two in-turn ASSISTANTs must fold into one");
        assertTrue(assistants.get(0).content().contains("I will check"),
                "the earlier text must survive the merge");
        assertTrue(assistants.get(0).content().contains("off I go"),
                "the later text must survive the merge");
        assertEquals(List.of("call_0_0"),
                assistants.get(0).toolCalls().stream().map(ChatToolCall::id).toList(),
                "the tool calls are unioned, so none is lost");
    }

    /**
     * Native tool calling (Ollama) cannot tolerate an assistant message that carries BOTH prose and
     * tool_calls (Mistral TemplateConfig.forbids_assistant_content_with_tools). The FIX 1 fold STAYS, but in
     * native mode the CONTENT gives way when the fold would produce the banned shape: the merged message is a
     * bare call, exactly what the manager commits for a tool round. Splitting the narration into a separate
     * preceding assistant entry is NOT an option — devstral's template's {@code if .Content else if
     * .ToolCalls} drops the call on a content-carrying message, and two assistant turns in a row violate its
     * strict alternation. Two prose-only messages must still fold and keep their text even in native mode,
     * and the merge is unchanged in schema mode, where content and the tool call are distinct protocol
     * fields.
     * <p>
     * Making {@code setNativeToolCalling} a no-op turns this red: the narration text then survives on the
     * folded tool-calling message — the exact banned shape.
     */
    @Test
    void appendAssistantInNativeModeFoldsNarrationButDropsItsContentFromTheToolCall() {
        TestBroker b = new TestBroker();
        b.setNativeToolCalling(true);
        b.beginTurn();
        b.append(user("hi"));
        b.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, "I will check", List.of(), null));
        b.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, null,
                List.of(new ChatToolCall("call_0_0", "GetPluginVersion", "{}")), null));

        List<ChatMessage> assistants = b.snapshot().stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT).toList();
        assertEquals(1, assistants.size(),
                "the narration must fold INTO the tool round — a separate preceding narration entry breaks alternation");
        assertNull(assistants.get(0).content(),
                "the narration content is dropped: the folded message is a bare tool call");
        assertEquals(List.of("call_0_0"),
                assistants.get(0).toolCalls().stream().map(ChatToolCall::id).toList(),
                "the tool call must survive intact");

        TestBroker prose = new TestBroker();
        prose.setNativeToolCalling(true);
        prose.beginTurn();
        prose.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, "one", List.of(), null));
        prose.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, "two", List.of(), null));
        List<ChatMessage> proseAssistants = prose.snapshot().stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT).toList();
        assertEquals(1, proseAssistants.size(),
                "two prose-only assistant messages must still fold and keep their text even in native mode");
        assertEquals("one\n\ntwo", proseAssistants.get(0).content());

        TestBroker schema = new TestBroker();
        schema.beginTurn();
        schema.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, "schema narration", List.of(), null));
        schema.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, null,
                List.of(new ChatToolCall("call_0_0", "GetPluginVersion", "{}")), null));
        List<ChatMessage> schemaAssistants = schema.snapshot().stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT).toList();
        assertEquals(1, schemaAssistants.size(),
                "schema mode must keep folding narration into the tool round, content included");
        assertTrue(schemaAssistants.get(0).content().contains("schema narration"));
        assertEquals(1, schemaAssistants.get(0).toolCalls().size());
        assertEquals("call_0_0", schemaAssistants.get(0).toolCalls().get(0).id());
    }

    /**
     * The merge must be narrowly scoped: never across a committed turn boundary, never across a TOOL result.
     * Both guards are what keep a tool-call round after a narration from swallowing anything it should not.
     */
    @Test
    void appendAssistantNeverMergesAcrossATurnBoundaryOrAcrossAToolResult() {
        TestBroker b = new TestBroker();
        b.beginTurn();
        b.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, "turn one", List.of(), null));
        b.commitTurn();

        b.beginTurn();
        b.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, "start of turn two", List.of(), null));
        b.append(new ChatMessage(ChatRole.TOOL, "result", List.of(), "call_0_0"));
        b.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, "after the tool", List.of(), null));

        List<ChatMessage> snap = b.snapshot();
        List<ChatMessage> assistants = snap.stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT).toList();
        assertEquals(3, assistants.size(),
                "no folding across a committed turn or across a TOOL result");
        assertEquals(List.of("turn one", "start of turn two", "after the tool"),
                assistants.stream().map(ChatMessage::content).toList());
    }

    /**
     * When one side of a merge is blank the other side must win outright; the "\n\n" join only applies when
     * both sides carry text.
     */
    @Test
    void appendAssistantKeepsOneNonBlankSideWhenTheOtherIsBlank() {
        TestBroker b = new TestBroker();
        b.beginTurn();
        b.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, "keep me", List.of(), null));
        b.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, null, List.of(), null));

        List<ChatMessage> assistants = b.snapshot().stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT).toList();
        assertEquals(1, assistants.size());
        assertEquals("keep me", assistants.get(0).content());
    }

    @Test
    void resetCalibrationRestoresTheNeutralRatio() {
        TestBroker b = new TestBroker();
        for (int i = 0; i < 10; i++) {
            b.recordUsage(100, 200);
        }
        b.resetCalibration();
        assertEquals(1.0d, b.calibrationRatio(), 0.0001d);
    }

    static class TestBroker extends AbstractChatContextBroker {

        TestBroker() {
            super("test-session", ContextBrokerSettings.defaults());
        }

        @Override
        protected int contextLimit() {
            return 100000;
        }
    }
}

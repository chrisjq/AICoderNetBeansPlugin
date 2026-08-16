package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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

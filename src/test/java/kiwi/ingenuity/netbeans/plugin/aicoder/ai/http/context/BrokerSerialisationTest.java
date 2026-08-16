package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class BrokerSerialisationTest {

    private static void addTurn(TestBroker b, String text) {
        b.beginTurn();
        b.append(new ChatMessage(ChatRole.USER, text, List.of(), null));
        b.append(new ChatMessage(ChatRole.ASSISTANT, "reply " + text, List.of(), null));
        b.commitTurn();
    }

    @Test
    void toJsonCarriesVersionSessionAndEntries() {
        TestBroker b = new TestBroker();
        addTurn(b, "hello");

        JsonObject json = b.toJson();

        assertEquals(1, json.get("version").getAsInt());
        assertEquals("ser-session", json.get("sessionId").getAsString());
        assertEquals(2, json.getAsJsonArray("entries").size());
    }

    @Test
    void calibrationRatioSurvivesTheRoundTrip() {
        TestBroker a = new TestBroker();
        for (int i = 0; i < 10; i++) {
            a.recordUsage(100, 200);
        }
        double earned = a.calibrationRatio();
        assertTrue(earned > 1.0d);

        TestBroker b = new TestBroker();
        b.restoreFromJson(a.toJson());

        assertEquals(earned, b.calibrationRatio(), 0.0001d,
                "the ratio is earned over a session and must not be re-learned every restart");
    }

    @Test
    void evictableEntriesRestoreVerbatimWithSequenceAndGroup() {
        TestBroker a = new TestBroker();
        addTurn(a, "one");
        addTurn(a, "two");

        TestBroker b = new TestBroker();
        b.restoreFromJson(a.toJson());

        assertEquals(4, b.entryCount());
        List<ChatMessage> snap = b.snapshot();
        assertEquals("one", snap.get(0).content());
        assertEquals("reply one", snap.get(1).content());
        assertEquals("two", snap.get(2).content());
    }

    @Test
    void pinnedSlotsAreNotRestoredFromDisk() {
        TestBroker a = new TestBroker();
        a.upsertPin(PinSlotEnum.IDENTITY, "STALE IDENTITY FROM AN OLD RUN");
        addTurn(a, "hello");

        TestBroker b = new TestBroker();
        b.restoreFromJson(a.toJson());

        for (ChatMessage m : b.snapshot()) {
            assertTrue(m.content() == null
                    || !m.content().contains("STALE IDENTITY FROM AN OLD RUN"),
                    "pins are rebuilt from current state; a stale system prompt is worse "
                    + "than none, because it fails silently and looks correct");
        }
    }

    @Test
    void aGroupMissingItsToolResultsIsDroppedOnLoad() {
        TestBroker a = new TestBroker();
        a.beginTurn();
        a.append(new ChatMessage(ChatRole.USER, "do it", List.of(), null));
        a.append(new ChatMessage(ChatRole.ASSISTANT, null,
                List.of(new ChatToolCall("call_0", "T", "{}")), null));
        // deliberately no TOOL result — simulates a crash mid-write
        a.commitTurn();

        TestBroker b = new TestBroker();
        b.restoreFromJson(a.toJson());

        assertEquals(0, b.entryCount(),
                "an assistant tool_calls message without its results is rejected by the "
                + "endpoint with HTTP 400, so the whole group must be dropped");
    }

    @Test
    void anUnknownVersionIsRefused() {
        TestBroker a = new TestBroker();
        addTurn(a, "hello");
        JsonObject json = a.toJson();
        json.addProperty("version", 99);

        TestBroker b = new TestBroker();
        b.restoreFromJson(json);

        assertEquals(0, b.entryCount(), "an unknown version is unreadable, not guessed at");
    }

    static class TestBroker extends AbstractChatContextBroker {

        TestBroker() {
            super("ser-session", ContextBrokerSettings.defaults());
        }

        @Override
        protected int contextLimit() {
            return 0;
        }
    }
}

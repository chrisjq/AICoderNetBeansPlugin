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

        assertEquals(4, b.entryCount(),
                "the stale-context warning is held in a field, not in entries, so it must not be counted");
        List<ChatMessage> snap = b.snapshot();
        assertTrue(snap.get(0).content().contains("resumed from a previous session"),
                "a restored context leads with the stale-data warning: " + snap.get(0).content());
        assertEquals("one", snap.get(1).content());
        assertEquals("reply one", snap.get(2).content());
        assertEquals("two", snap.get(3).content());
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

    private static long staleWarnings(List<ChatMessage> snapshot) {
        return snapshot.stream()
                .filter(m -> m.content() != null && m.content().contains("resumed from a previous session"))
                .count();
    }

    /**
     * A session reopened after a rebuild answered "1.4.55" from a restored tool result when the installed
     * version was 1.4.57, and never re-read the file it was asked to read. Restored tool results describe the
     * project as it was in an earlier run, so the model is told to re-check rather than trust them.
     * <p>
     * Deleting the marker construction in restoreFromJson turns this red.
     */
    @Test
    void aRestoredContextWarnsThatItsToolResultsMayBeStale() {
        TestBroker a = new TestBroker();
        addTurn(a, "hello");

        TestBroker b = new TestBroker();
        b.restoreFromJson(a.toJson());

        assertEquals(1, staleWarnings(b.snapshot()),
                "a restored conversation must tell the model its tool results may be out of date");
    }

    /**
     * The warning is a one-turn notice, not a permanent tax. Every request within the first turn carries it —
     * a model needing several rounds still sees it — but it is dropped once that turn closes.
     * <p>
     * Removing the null-out in commitTurn turns this red.
     */
    @Test
    void theStaleContextWarningIsDroppedOnceTheFirstTurnCloses() {
        TestBroker a = new TestBroker();
        addTurn(a, "hello");

        TestBroker b = new TestBroker();
        b.restoreFromJson(a.toJson());
        assertEquals(1, staleWarnings(b.snapshot()), "it must be present for the turn that follows a restore");

        b.beginTurn();
        assertEquals(1, staleWarnings(b.snapshot()),
                "still present mid-turn: a multi-round answer must not lose it before it replies");
        b.commitTurn();

        assertEquals(0, staleWarnings(b.snapshot()),
                "one turn is all it gets; it must not tax every later request");
    }

    /**
     * A fresh session has nothing stale to warn about, and the warning costs tokens on a 32k window.
     */
    @Test
    void aFreshSessionCarriesNoStaleContextWarning() {
        TestBroker fresh = new TestBroker();
        addTurn(fresh, "hello");

        assertEquals(0, staleWarnings(fresh.snapshot()),
                "nothing was restored, so there is nothing to warn about");
    }

    /**
     * THE ACCUMULATION GUARD. Held in a field rather than appended to entries, so toJson never serialises it
     * and a second restore cannot stack a second copy. Without that, every open/close cycle would add one
     * more warning until it crowded out the conversation.
     * <p>
     * Appending the marker to entries instead of the field turns this red with 2 warnings.
     */
    @Test
    void repeatedSaveRestoreCyclesNeverStackMoreThanOneWarning() {
        TestBroker first = new TestBroker();
        addTurn(first, "hello");

        TestBroker second = new TestBroker();
        second.restoreFromJson(first.toJson());
        assertEquals(1, staleWarnings(second.snapshot()), "first restore warns once");

        TestBroker third = new TestBroker();
        third.restoreFromJson(second.toJson());

        assertEquals(1, staleWarnings(third.snapshot()),
                "a second save/restore cycle must not stack a second warning");
        assertEquals(2, third.entryCount(),
                "the warning is never persisted, so it cannot come back as a restored entry");
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

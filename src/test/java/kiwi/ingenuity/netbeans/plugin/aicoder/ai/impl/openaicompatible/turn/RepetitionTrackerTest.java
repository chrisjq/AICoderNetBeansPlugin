package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.RepetitionTracker.Verdict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RepetitionTrackerTest {

    @Test
    @Timeout(5)
    void identicalCallAndResultWithinTheWindowIsRepeated() {
        RepetitionTracker tracker = new RepetitionTracker(4, 3);
        JsonObject args = args("path", "/tmp/a");

        assertEquals(Verdict.OK, tracker.record("GetFileContent", args, "hello"));
        assertEquals(Verdict.REPEATED, tracker.record("GetFileContent", args, "hello"));
        assertEquals(
                "You already called GetFileContent with these arguments and got the same result. Use that result or try something different.",
                tracker.repeatedMessage("GetFileContent"));
    }

    @Test
    @Timeout(5)
    void sameCallWithADifferentResultIsOk() {
        RepetitionTracker tracker = new RepetitionTracker(4, 3);
        JsonObject args = args("path", "/tmp/a");

        assertEquals(Verdict.OK, tracker.record("GetFileContent", args, "one"));
        assertEquals(Verdict.OK, tracker.record("GetFileContent", args, "two"));
    }

    @Test
    @Timeout(5)
    void sameResultWithDifferentArgumentsIsNewAndMutationClearsHistory() {
        RepetitionTracker tracker = new RepetitionTracker(4, 3);
        assertEquals(Verdict.OK, tracker.record("GetFileContent", args("path", "/tmp/a"), "same"));
        assertEquals(Verdict.OK, tracker.record("GetFileContent", args("path", "/tmp/b"), "same"));
        assertEquals(Verdict.REPEATED, tracker.record("GetFileContent", args("path", "/tmp/a"), "same"));
        tracker.clear();
        assertEquals(Verdict.OK, tracker.record("GetFileContent", args("path", "/tmp/a"), "same"));
    }

    @Test
    @Timeout(5)
    void alternatingKeysAThenBThenAThenBAreRepeated() {
        RepetitionTracker tracker = new RepetitionTracker(4, 3);
        JsonObject a = args("n", 1);
        JsonObject b = args("n", 2);

        assertEquals(Verdict.OK, tracker.record("t", a, "r1"));
        assertEquals(Verdict.OK, tracker.record("t", b, "r2"));
        assertEquals(Verdict.OK, tracker.record("t", a, "r3"));
        assertEquals(Verdict.REPEATED, tracker.record("t", b, "r4"));
    }

    @Test
    @Timeout(5)
    void argumentKeyOrderDoesNotChangeTheCallKey() {
        RepetitionTracker tracker = new RepetitionTracker(4, 3);
        JsonObject first = new JsonObject();
        first.addProperty("z", 1);
        first.addProperty("a", 2);
        JsonObject second = new JsonObject();
        second.addProperty("a", 2);
        second.addProperty("z", 1);

        assertEquals(Verdict.OK, tracker.record("t", first, "same"));
        assertEquals(Verdict.REPEATED, tracker.record("t", second, "same"));
    }

    @Test
    @Timeout(5)
    void threeUnproductiveRoundsTripThenAProductiveRoundResets() {
        RepetitionTracker tracker = new RepetitionTracker(4, 3);

        assertFalse(tracker.endRound(false));
        assertFalse(tracker.endRound(false));
        assertTrue(tracker.endRound(false));

        assertFalse(tracker.endRound(true));
        assertFalse(tracker.endRound(false));
        assertFalse(tracker.endRound(false));
        assertTrue(tracker.endRound(false));
    }

    @Test
    @Timeout(5)
    void unchangedListBuildsUsesTheStopPollingText() {
        RepetitionTracker tracker = new RepetitionTracker(4, 3);
        JsonObject args = new JsonObject();

        assertEquals(Verdict.OK, tracker.record("ListBuilds", args, "running"));
        assertEquals(Verdict.REPEATED, tracker.record("ListBuilds", args, "running"));
        assertEquals(
                "The build is still running. Its result will arrive as a message when it finishes — stop polling and give your answer now.",
                tracker.repeatedMessage("ListBuilds"));
    }

    private static JsonObject args(String key, Object value) {
        JsonObject object = new JsonObject();
        if (value instanceof Number number) {
            object.addProperty(key, number);
        }
        else {
            object.addProperty(key, String.valueOf(value));
        }
        return object;
    }
}

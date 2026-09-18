package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.session;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.JsonUtils;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PiPersistentSessionPumpTest {

    private JsonObject decode(String line) {
        return new Gson().fromJson(line, JsonObject.class);
    }

    private java.io.InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void pump_completesMatchingPendingFutureAndForwardsTheLine() {
        Map<String, CompletableFuture<JsonObject>> pending = new HashMap<>();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put("abc", future);
        List<String> lines = new ArrayList<>();

        PiPersistentSession.pump(stream("{\"type\":\"response\",\"command\":\"steer\",\"id\":\"abc\",\"success\":true}\n"),
                                 pending, lines::add);

        assertTrue(future.isDone(), "response echoed the id so the future must complete");
        assertEquals("steer", JsonUtils.getString(future.join(), "command"));
        assertEquals(1, lines.size(), "the correlated response is still forwarded to the parser");
        assertEquals("abc", JsonUtils.getString(decode(lines.get(0)), "id"));
    }

    @Test
    void pump_forwardsFramesWithNoPendingIdAndFrameWithoutId() {
        Map<String, CompletableFuture<JsonObject>> pending = new HashMap<>();
        List<String> lines = new ArrayList<>();

        PiPersistentSession.pump(stream(
                "{\"type\":\"agent_start\"}\n"
                + "{\"type\":\"response\",\"command\":\"get_state\",\"id\":\"nobody\",\"success\":true}\n"),
                                 pending, lines::add);

        assertEquals(2, lines.size());
        assertEquals("agent_start", JsonUtils.getString(decode(lines.get(0)), "type"));
        assertEquals("nobody", JsonUtils.getString(decode(lines.get(1)), "id"));
    }

    @Test
    void pump_stripsTrailingCarriageReturn() {
        Map<String, CompletableFuture<JsonObject>> pending = new HashMap<>();
        List<String> lines = new ArrayList<>();

        PiPersistentSession.pump(stream("{\"id\":\"x\",\"ok\":1}\r\n"), pending, lines::add);

        assertEquals(1, lines.size());
        assertFalse(lines.get(0).endsWith("\r"), "trailing CR must be stripped from the LF frame");
        assertEquals(1, decode(lines.get(0)).get("ok").getAsLong());
    }

    @Test
    void pump_skipsMalformedFramesButKeepsGoing() {
        Map<String, CompletableFuture<JsonObject>> pending = new HashMap<>();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put("abc", future);
        List<String> lines = new ArrayList<>();

        PiPersistentSession.pump(stream(
                "not json at all\n"
                + "{\"type\":\"agent_start\"" + "\n" // truncated frame
                + "{\"type\":\"response\",\"id\":\"abc\",\"success\":true}\n"),
                                 pending, lines::add);

        assertEquals(1, lines.size(), "malformed frames are skipped, the well-formed one is forwarded");
        assertTrue(future.isDone(), "a malformed preceding frame must not break id correlation");
        assertEquals("abc", JsonUtils.getString(decode(lines.get(0)), "id"));
        assertTrue(future.isDone());
    }

    @Test
    void pump_preservesUnicodeLineSeparatorInsideJsonStrings() {
        // U+2028 is valid inside a JSON string and must NOT be treated as a frame delimiter.
        String payload = "{\"type\":\"event\",\"text\":\"a" + (char) 0x2028 + "b\"}";
        Map<String, CompletableFuture<JsonObject>> pending = new HashMap<>();
        List<String> lines = new ArrayList<>();

        PiPersistentSession.pump(stream(payload + "\n"), pending, lines::add);

        assertEquals(1, lines.size(), "U+2028 splits nothing: exactly one frame");
        assertEquals(payload, lines.get(0));
        assertEquals("a" + (char) 0x2028 + "b", JsonUtils.getString(decode(lines.get(0)), "text"));
    }

    @Test
    void pump_handlesBlankLinesAndEofWithoutFinalNewline() {
        Map<String, CompletableFuture<JsonObject>> pending = new HashMap<>();
        List<String> lines = new ArrayList<>();

        PiPersistentSession.pump(stream("\n\n{\"id\":\"z\",\"ok\":1}"), pending, lines::add);

        assertEquals(1, lines.size());
        assertEquals("z", JsonUtils.getString(decode(lines.get(0)), "id"));
    }

    @Test
    void pump_failsPendingFuturesOnEof() {
        Map<String, CompletableFuture<JsonObject>> pending = new HashMap<>();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put("lonely", future);
        List<String> lines = new ArrayList<>();

        PiPersistentSession.pump(stream(""), pending, lines::add);

        assertTrue(future.isDone(), "a pending future must be failed when the stream reaches EOF without a response");
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void pumpStderr_deliversEachLine() {
        List<String> stderr = Collections.synchronizedList(new ArrayList<>());
        PiPersistentSession.pumpStderr(stream("first line\nsecond line\n"), stderr::add);
        assertEquals(List.of("first line", "second line"), stderr);
    }
}

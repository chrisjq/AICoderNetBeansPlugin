package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.session;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PiPersistentSessionTest {

    private static boolean awaitTrue(BooleanSupplier cond, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return cond.getAsBoolean();
    }

    @Test
    void frameRequest_usesCommandNameAsTypeAndOmitsCommandField() {
        // pi has no generic "command" command — the command name IS the frame's own "type" (verified live against a
        // real pi process). "command" is an internal-only marker on the caller-built object; it must never reach
        // the wire.
        JsonObject cmd = new JsonObject();
        cmd.addProperty("command", "steer");
        cmd.addProperty("message", "hello \"world\"");
        String line = PiPersistentSession.frameRequest(cmd, "cmd-1");
        assertTrue(line.endsWith("\n"), "frame must be LF-terminated");
        JsonObject o = new Gson().fromJson(line.trim(), JsonObject.class);
        assertEquals("steer", o.get("type").getAsString());
        assertEquals("cmd-1", o.get("id").getAsString());
        assertTrue(!o.has("command"), "the internal-only \"command\" marker must never be written to the wire");
        assertEquals("hello \"world\"", o.get("message").getAsString());
    }

    @Test
    void frameRequest_preservesUnicodeLineSeparatorExactly() {
        // (char) 0x2028 is a valid JSON string character and must survive framing byte-for-byte.
        JsonObject cmd = new JsonObject();
        cmd.addProperty("command", "prompt");
        cmd.addProperty("message", "a" + (char) 0x2028 + "b");
        String line = PiPersistentSession.frameRequest(cmd, "cmd-2");
        JsonObject o = new Gson().fromJson(line.trim(), JsonObject.class);
        assertEquals("a" + (char) 0x2028 + "b", o.get("message").getAsString());
    }

    @Test
    void launch_setsWorkingDirectoryCapturesStderrAndCorrelatesResponses() throws Exception {
        Path dir = Files.createTempDirectory("pi-session-test-");
        String script = """
            printf '%s\\n' '{"type":"cwd","cwd":"'"$PWD"'"}'
            printf '%s\\n' 'SOME STDERR MESSAGE' >&2
            while IFS= read -r line; do
                printf '%s\\n' "$line" | sed 's/"type":"steer"/"type":"response","command":"steer","success":true/'
            done
            """;
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch cwdLatch = new CountDownLatch(1);
        List<String> stderr = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch stderrLatch = new CountDownLatch(1);

        PiPersistentSession session = PiPersistentSession.launch(List.of("sh", "-c", script), dir.toFile(),
                                                                 line -> {
                                                                     lines.add(line);
                                                                     if (line.contains("\"type\":\"cwd\"")) {
                                                                         cwdLatch.countDown();
                                                                     }
                                                                 },
                                                                 s -> {
                                                                     stderr.add(s);
                                                                     if (s.contains("SOME STDERR MESSAGE")) {
                                                                         stderrLatch.countDown();
                                                                     }
                                                                 });
        try {
            assertTrue(session.isAlive());
            assertTrue(cwdLatch.await(10, TimeUnit.SECONDS), "cwd event must arrive on the event line");
            assertTrue(stderrLatch.await(10, TimeUnit.SECONDS), "stderr line must be captured");
            assertTrue(lines.stream().anyMatch(l -> l.contains("pi-session-test-")),
                       "the cwd JSON should reference the working directory");

            JsonObject cmd = new JsonObject();
            cmd.addProperty("command", "steer");
            cmd.addProperty("message", "hi");
            JsonObject resp = session.send(cmd).get(10, TimeUnit.SECONDS);
            assertTrue(resp.get("success").getAsBoolean());
            assertEquals("steer", resp.get("command").getAsString());

            String rawReply = "{\"type\":\"extension_ui_response\",\"id\":\"ui-1\",\"confirmed\":true}";
            assertTrue(session.sendRawLine(rawReply));
            assertTrue(awaitTrue(() -> lines.stream().anyMatch(l -> l.contains("\"extension_ui_response\"")), 10),
                       "the echoed raw line must arrive on the event line");

            assertTrue(stderr.contains("SOME STDERR MESSAGE"));
        }
        finally {
            session.close();
            assertTrue(session.process().waitFor(10, TimeUnit.SECONDS),
                       "closing stdin must let an idle pi process exit");
            assertEquals(0, session.process().exitValue());
        }
    }

    @Test
    void send_afterCloseFailsTheFutureAndSendRawLineReturnsFalse() throws Exception {
        PiPersistentSession session = PiPersistentSession.launch(List.of("sh", "-c", "cat"), null, l -> {
                                                         }, s -> {
                                                         });
        session.close();
        CompletableFuture<JsonObject> future = session.send(new JsonObject());
        assertTrue(future.isDone());
        assertTrue(future.isCompletedExceptionally());
        assertTrue(!session.sendRawLine("{\"type\":\"extension_ui_response\",\"id\":\"x\",\"confirmed\":true}"));
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the on-disk chat-history key names written by {@link HistoryPersistenceManager}.
 * <p>
 * The key names below are literals ON PURPOSE. Reading them from {@link HistoryPersistenceKeyEnum} would make this test
 * follow any rename and assert nothing — it would look like coverage while proving only that the enum equals itself.
 * <p>
 * This is deliberately not a round-trip test. HistoryPersistenceManagerTest already covers save-then-load, but that
 * passes under any rename because both halves move together. Only a hand-written document and a raw-text assertion can
 * catch a key changing.
 * <p>
 * What it protects: the loader drops any message missing role, text or timestamp instead of failing. A renamed key
 * therefore raises no error — it silently empties every conversation already on disk, with a green build.
 */
class HistoryFormatGoldenTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsAHandWrittenHistoryFileUsingTheDocumentedKeyNames() throws IOException {
        Path file = tempDir.resolve("history.json");
        Files.writeString(file, """
            {
              "sessionId": "session-42",
              "workingDir": "/tmp/project",
              "instructionsLoaded": true,
              "messages": [
                {"role": "USER", "text": "hello", "timestamp": 1700000000000},
                {"role": "ASSISTANT", "text": "hi back", "timestamp": 1700000001000}
              ]
            }
            """, StandardCharsets.UTF_8);

        HistoryPersistenceManager.LoadedHistory loaded
                = new HistoryPersistenceManager(file).load();

        assertEquals("session-42", loaded.sessionId());
        assertEquals("/tmp/project", loaded.workingDir());
        assertTrue(loaded.instructionsLoaded());
        assertEquals(2, loaded.messages().size());
        assertEquals("hello", loaded.messages().get(0).markdownText());
        assertEquals(1700000000000L, loaded.messages().get(0).timestamp());
        assertEquals("hi back", loaded.messages().get(1).markdownText());
    }

    @Test
    void writesExactlyTheDocumentedKeyNames() throws IOException {
        Path file = tempDir.resolve("written.json");
        new HistoryPersistenceManager(file)
                .save(List.of(AiMessage.user("body text")), "session-7", "/tmp/wd");

        String json = Files.readString(file, StandardCharsets.UTF_8);

        // Asserted against each object's own key set rather than as substrings
        // of the whole document: a contains() check passes if the name appears
        // anywhere at all, including inside a value or a nested object, so it
        // would keep passing after the key it claims to pin was renamed.
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(Set.of("sessionId", "workingDir", "instructionsLoaded", "messages"),
                root.keySet());

        JsonObject message = root.getAsJsonArray("messages").get(0).getAsJsonObject();
        assertEquals(Set.of("role", "text", "timestamp"), message.keySet());
    }
}

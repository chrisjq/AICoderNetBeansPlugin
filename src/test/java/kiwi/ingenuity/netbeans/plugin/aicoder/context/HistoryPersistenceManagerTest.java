package kiwi.ingenuity.netbeans.plugin.aicoder.context;

import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.HistoryPersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.HistoryPersistenceManager.LoadedHistory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryPersistenceManagerTest {

    @Test
    void saveAndLoad_roundTripsMessagesAndSessionId(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("history.json");
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);

        List<AiMessage> original = List.of(AiMessage.user("Hello claude"),
                AiMessage.assistant("Hi there!")
        );
        mgr.save(original, "test-session-uuid-1234", null);

        LoadedHistory loaded = mgr.load();
        assertEquals(2, loaded.messages().size());
        assertEquals("Hello claude", loaded.messages().get(0).markdownText());
        assertEquals(AiMessage.Role.USER, loaded.messages().get(0).role());
        assertEquals("Hi there!", loaded.messages().get(1).markdownText());
        assertTrue(loaded.messages().get(0).restored());
        assertTrue(loaded.messages().get(1).restored());
        assertEquals("test-session-uuid-1234", loaded.sessionId());
        assertNull(loaded.workingDir());
    }

    @Test
    void saveAndLoad_roundTripsWorkingDir(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("history.json");
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        mgr.save(List.of(AiMessage.user("hi")), "sid", "/home/user/projects/MyProject");

        LoadedHistory loaded = mgr.load();
        assertEquals("/home/user/projects/MyProject", loaded.workingDir());
        assertEquals("sid", loaded.sessionId());
    }

    @Test
    void saveAndLoad_withoutSessionId_returnsNullSessionId(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("history.json");
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        mgr.save(List.of(AiMessage.user("hi")), null, null);

        LoadedHistory loaded = mgr.load();
        assertFalse(loaded.messages().isEmpty());
        assertNull(loaded.sessionId());
        assertNull(loaded.workingDir());
    }

    @Test
    void load_withNoFile_returnsEmptyList(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nonexistent.json");
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        assertTrue(mgr.load().messages().isEmpty());
    }

    @Test
    void delete_removesFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("history.json");
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        mgr.save(List.of(AiMessage.user("x")), null, null);
        assertTrue(file.toFile().exists());
        mgr.delete();
        assertFalse(file.toFile().exists());
    }

    @Test
    void save_emptyList_leavesExistingFileIntact(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("history.json");
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        mgr.save(List.of(AiMessage.user("x")), "some-session", null);
        mgr.save(List.of(), null, null); // empty save is a no-op — file stays
        assertTrue(file.toFile().exists());
    }

    @Test
    void load_withCorruptJSON_returnsEmptyList(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("corrupt.json");
        Files.writeString(file, "{not valid json at all}");
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        assertTrue(mgr.load().messages().isEmpty());
    }

    @Test
    void load_oldBareArrayFormat_isHandledGracefully(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("history.json");
        // Old format: bare JSON array without a wrapper object
        Files.writeString(file, """
            [{"role":"USER","text":"old message","timestamp":1000}]
            """.strip());
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);

        LoadedHistory loaded = mgr.load();
        assertEquals(1, loaded.messages().size());
        assertEquals("old message", loaded.messages().get(0).markdownText());
        assertNull(loaded.sessionId()); // no session ID in old format
    }

    @Test
    void saveAndLoad_roundTripsInstructionsLoaded(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("history.json");
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        mgr.save(List.of(AiMessage.user("hi")), "sid", null, true);

        LoadedHistory loaded = mgr.load();
        assertTrue(loaded.instructionsLoaded());
    }

    @Test
    void saveAndLoad_defaultsInstructionsLoadedFalse(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("history.json");
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        mgr.save(List.of(AiMessage.user("hi")), "sid", null);

        LoadedHistory loaded = mgr.load();
        assertFalse(loaded.instructionsLoaded());
    }

    @Test
    void load_oldBareArrayFormat_instructionsLoadedFalse(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("history.json");
        Files.writeString(file, "[{\"role\":\"USER\",\"text\":\"old\",\"timestamp\":1000}]");
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        assertFalse(mgr.load().instructionsLoaded());
    }

    @Test
    void load_nonBooleanInstructionsLoaded_defaultsFalseAndKeepsMessages(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("history.json");
        // instructionsLoaded is a malformed (non-primitive) value — must not throw
        // away the whole history; flag defaults to false, messages are preserved.
        Files.writeString(file, "{\"instructionsLoaded\":{},\"messages\":"
                + "[{\"role\":\"USER\",\"text\":\"hi\",\"timestamp\":1000}]}");
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        LoadedHistory loaded = mgr.load();
        assertFalse(loaded.instructionsLoaded());
        assertEquals(1, loaded.messages().size());
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextPersistenceManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void savedContextIsReadBack() throws IOException {
        ContextPersistenceManager mgr = new ContextPersistenceManager(tempDir);
        JsonObject payload = new JsonObject();
        payload.addProperty("version", 1);
        payload.addProperty("marker", "hello");

        mgr.save("session-a", payload);
        JsonObject loaded = mgr.load("session-a");

        assertEquals("hello", loaded.get("marker").getAsString());
    }

    @Test
    void theFileLandsBesideTheSessionHistory() throws IOException {
        ContextPersistenceManager mgr = new ContextPersistenceManager(tempDir);
        JsonObject payload = new JsonObject();
        payload.addProperty("version", 1);

        mgr.save("session-b", payload);

        assertTrue(Files.exists(tempDir.resolve("session-b").resolve("context.json")),
                "context.json sits alongside history.json in the per-session directory");
    }

    @Test
    void missingFileLoadsAsNullRatherThanThrowing() throws IOException {
        ContextPersistenceManager mgr = new ContextPersistenceManager(tempDir);
        assertNull(mgr.load("never-saved"));
    }

    @Test
    void corruptFileLoadsAsNullRatherThanThrowing() throws IOException {
        ContextPersistenceManager mgr = new ContextPersistenceManager(tempDir);
        Path dir = Files.createDirectories(tempDir.resolve("session-c"));
        Files.writeString(dir.resolve("context.json"), "{ this is not json");

        assertNull(mgr.load("session-c"),
                "losing model-side context degrades a session; refusing to start it is worse");
    }

    @Test
    void deleteRemovesTheFile() throws IOException {
        ContextPersistenceManager mgr = new ContextPersistenceManager(tempDir);
        JsonObject payload = new JsonObject();
        payload.addProperty("version", 1);
        mgr.save("session-d", payload);

        mgr.delete("session-d");

        assertNull(mgr.load("session-d"));
    }
}

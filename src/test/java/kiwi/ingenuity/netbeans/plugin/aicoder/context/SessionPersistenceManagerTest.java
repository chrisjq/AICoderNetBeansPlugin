package kiwi.ingenuity.netbeans.plugin.aicoder.context;

import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionPersistenceManagerTest {

    @TempDir
    Path tmp;

    SessionPersistenceManager mgr() {
        return new SessionPersistenceManager(tmp);
    }

    @Test
    void emptyOnFirstLoad() throws IOException {
        assertTrue(mgr().loadAll().isEmpty());
    }

    @Test
    void saveAndLoadRoundtrip() throws IOException {
        SessionPersistenceManager m = mgr();
        AiSession s = AiSession.create("/projects/MyApp", kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE);
        m.save(s);
        List<AiSession> all = m.loadAll();
        assertEquals(1, all.size());
        assertEquals(s.id(), all.get(0).id());
        assertEquals(s.name(), all.get(0).name());
        assertEquals(s.projectPath(), all.get(0).projectPath());
    }

    @Test
    void deleteRemovesSessionAndHistoryFile() throws IOException {
        SessionPersistenceManager m = mgr();
        AiSession s = AiSession.create("/projects/X", kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE);
        m.save(s);
        Path hist = m.historyPath(s.id());
        Files.createDirectories(hist.getParent());
        Files.writeString(hist, "{\"messages\":[]}");
        m.delete(s.id());
        assertTrue(m.loadAll().isEmpty());
        assertFalse(Files.exists(hist));
    }

    @Test
    void historyPathIsInsideSessionSubdir() {
        SessionPersistenceManager m = mgr();
        String id = "abc-123";
        Path hp = m.historyPath(id);
        assertTrue(hp.toString().contains(id));
        assertEquals("history.json", hp.getFileName().toString());
    }

}

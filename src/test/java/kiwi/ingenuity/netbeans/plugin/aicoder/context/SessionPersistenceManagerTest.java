package kiwi.ingenuity.netbeans.plugin.aicoder.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.SessionInstructionsDeliveryEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
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

    @Test
    void modelRoundtrips() throws IOException {
        SessionPersistenceManager m = mgr();
        AiSession s = AiSession.create("/projects/MyApp", kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE);
        ((AiModelSessionSettings) s.settings()).setModel("claude-opus-4-8");
        m.save(s);
        List<AiSession> all = m.loadAll();
        assertEquals(1, all.size());
        AiSessionSettings loaded = all.get(0).settings();
        assertTrue(loaded instanceof AiModelSessionSettings);
        assertEquals("claude-opus-4-8", ((AiModelSessionSettings) loaded).model());
    }

    @Test
    void sessionInstructionsRoundtrip() throws IOException {
        SessionPersistenceManager m = mgr();
        AiSession s = AiSession.create("/projects/MyApp", kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE);
        s.settings().setSessionInstructions("always answer in haiku");
        m.save(s);
        List<AiSession> all = m.loadAll();
        assertEquals(1, all.size());
        assertEquals("always answer in haiku", all.get(0).settings().sessionInstructions());
    }

    @Test
    void startupInstructionDeliveryDefaultsPersist() throws IOException {
        SessionPersistenceManager m = mgr();
        AiSession s = AiSession.create("/projects/MyApp", kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE);

        assertEquals(SessionInstructionsDeliveryEnum.ON_FIRST_REQUEST, s.sessionInstructionsDelivery());
        assertFalse(s.isStartupInstructionsInjected());

        m.save(s);

        AiSession loaded = m.loadAll().get(0);
        assertEquals(SessionInstructionsDeliveryEnum.ON_FIRST_REQUEST, loaded.sessionInstructionsDelivery());
        assertFalse(loaded.isStartupInstructionsInjected());
    }

    @Test
    void startupInstructionsDeliveryStateRoundtrips() throws IOException {
        SessionPersistenceManager m = mgr();
        AiSession s = AiSession.create("/projects/MyApp", kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE);
        s.setSessionInstructionsDelivery(SessionInstructionsDeliveryEnum.ON_START);
        s.setStartupInstructionsInjected(true);

        m.save(s);

        AiSession loaded = m.loadAll().get(0);
        assertEquals(SessionInstructionsDeliveryEnum.ON_START, loaded.sessionInstructionsDelivery());
        assertTrue(loaded.isStartupInstructionsInjected());
    }

    @Test
    void allowWebRequestsRoundtrips() throws IOException {
        SessionPersistenceManager m = mgr();
        AiSession s = AiSession.create("/projects/MyApp", kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE);
        s.settings().setAllowWebRequests(true);
        s.settings().setAllowWebRequestAccess(WebRequestAccessOptionEnum.HEADERS, false);
        m.save(s);
        List<AiSession> all = m.loadAll();
        assertEquals(1, all.size());
        assertEquals(true, all.get(0).settings().allowWebRequests());
        assertEquals(false, all.get(0).settings().allowWebRequestAccess(
                WebRequestAccessOptionEnum.HEADERS));
    }

    @Test
    void delete_collectsUnreferencedOrphanDirectories() throws IOException {
        SessionPersistenceManager m = mgr();
        AiSession keep = AiSession.create("/projects/Keep", AiTypeEnum.CLAUDE);
        AiSession gone = AiSession.create("/projects/Gone", AiTypeEnum.CLAUDE);
        m.save(keep);
        m.save(gone);

        Path keepDir = tmp.resolve(keep.id());
        Files.createDirectories(keepDir);
        Files.writeString(keepDir.resolve("history.json"), "{\"messages\":[]}");

        // A past crash orphaned this tree: no sessions.json entry references it.
        Path ghost = tmp.resolve("ghost-session");
        Files.createDirectories(ghost.resolve("nested"));
        Files.writeString(ghost.resolve("history.json"), "{\"messages\":[]}");
        Files.writeString(ghost.resolve("nested").resolve("junk.txt"), "x");

        // The deleted session's own dir holds an extra file, so the old empty-only removal
        // would have left it behind; the sweep must take the whole tree.
        Path goneDir = tmp.resolve(gone.id());
        Files.createDirectories(goneDir);
        Files.writeString(goneDir.resolve("extra.txt"), "y");

        m.delete(gone.id());

        assertFalse(Files.exists(ghost), "orphaned directory collected during delete");
        assertFalse(Files.exists(goneDir), "deleted session's non-empty directory fully removed");
        assertTrue(Files.isDirectory(keepDir), "directory of a remaining session survives");
        assertTrue(Files.exists(keepDir.resolve("history.json")));
        List<AiSession> remaining = m.loadAll();
        assertEquals(1, remaining.size());
        assertEquals(keep.id(), remaining.get(0).id());
    }

    @Test
    void delete_leavesRegularFilesInBaseDirAlone() throws IOException {
        SessionPersistenceManager m = mgr();
        AiSession s = AiSession.create("/projects/X", AiTypeEnum.CLAUDE);
        m.save(s);

        m.delete(s.id());

        assertTrue(Files.exists(tmp.resolve("sessions.json")), "sessions index untouched by sweep");
        assertTrue(Files.exists(tmp.resolve("sessions.lock")), "lock file untouched by sweep");
    }

    // Regression pin: a MISSING sessions.json proves nothing about orphanhood — the dirs
    // under baseDir may be intact histories whose index was lost separately. The old sweep
    // treated an empty loaded list as "everything unreferenced" and one unrelated delete()
    // destroyed every recoverable conversation.
    @Test
    void delete_missingIndex_neverPurgesHistoryDirectories() throws IOException {
        SessionPersistenceManager m = mgr();

        Path intactA = tmp.resolve("history-a");
        Path intactB = tmp.resolve("history-b");
        Files.createDirectories(intactA.resolve("nested"));
        Files.writeString(intactA.resolve("history.json"), "{\"messages\":[]}");
        Files.writeString(intactA.resolve("nested").resolve("junk.txt"), "x");
        Files.createDirectories(intactB);
        Files.writeString(intactB.resolve("context.json"), "{}");

        // No sessions.json written at all. Delete something that cannot be in it.
        m.delete("never-existed");

        assertTrue(Files.isDirectory(intactA), "first history dir must survive a delete() with no index present");
        assertTrue(Files.exists(intactA.resolve("nested").resolve("junk.txt")));
        assertTrue(Files.isDirectory(intactB), "second history dir must survive too");
        assertTrue(Files.exists(tmp.resolve("sessions.json")),
                "the fresh (empty) index IS still written — future deletes sweep normally again");
        assertTrue(m.loadAll().isEmpty());
    }

    // The flip side, pinned so the safety guard can never silently disable the sweep:
    // when the index EXISTS and references nothing, every directory is provably unreferenced
    // and the sweep must still collect it (legitimate "index is genuinely empty" case).
    @Test
    void delete_existingEmptyIndex_stillSweepsUnreferencedDirectories() throws IOException {
        SessionPersistenceManager m = mgr();
        Files.writeString(tmp.resolve("sessions.json"), "[]");

        Path ghost = tmp.resolve("ghost-session");
        Files.createDirectories(ghost.resolve("nested"));
        Files.writeString(ghost.resolve("history.json"), "{\"messages\":[]}");

        m.delete("never-existed");

        assertFalse(Files.exists(ghost),
                "with an existing empty index the orphan sweep must run exactly as before");
    }

    @Test
    void loadAll_skipsMalformedEntriesAndKeepsGoodOnes() throws IOException {
        SessionPersistenceManager m = mgr();
        Files.writeString(tmp.resolve("sessions.json"), """
            [
              {"id":"s1","name":"Good","aiType":"CLAUDE",
               "createdAt":"2026-01-01T00:00:00Z","lastUsedAt":"2026-01-02T00:00:00Z"},
              {"id":"s2","name":"NoAiType",
               "createdAt":"2026-01-01T00:00:00Z","lastUsedAt":"2026-01-02T00:00:00Z"},
              {"id":"s3","name":"BadAiType","aiType":"NOT_A_REAL_TYPE",
               "createdAt":"2026-01-01T00:00:00Z","lastUsedAt":"2026-01-02T00:00:00Z"},
              {"id":"s4","name":"MissingName","aiType":"CLAUDE"}
            ]
            """);

        List<AiSession> all = m.loadAll();

        assertEquals(1, all.size());
        assertEquals("s1", all.get(0).id());
    }

}

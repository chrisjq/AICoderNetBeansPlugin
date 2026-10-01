package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SessionDeletion;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A session with an open tab is deleted by that tab; the picker must not also delete it itself, on its own
 * thread, while the tab may still be saving it.
 */
class SessionDeletionTest {

    @TempDir
    Path tmp;

    private final List<String> deleted = new ArrayList<>();

    private SessionPersistenceManager recordingManager() {
        return new SessionPersistenceManager(tmp) {
            @Override
            public synchronized void delete(String sessionId) {
                deleted.add(sessionId);
            }
        };
    }

    @Test
    void aSessionWithAnOpenTabIsLeftToTheTabToDelete() throws Exception {
        CompletableFuture<Void> tabDeletion = new CompletableFuture<>();

        CompletableFuture<Void> result = SessionDeletion.delete("s1", id -> tabDeletion, recordingManager());

        assertSame(tabDeletion, result, "the caller must wait on the tab's own deletion");
        assertEquals(List.of(), deleted, "the picker must not delete a session that an open tab is deleting");
    }

    @Test
    void aSessionWithNoOpenTabIsDeletedDirectly() throws Exception {
        CompletableFuture<Void> result = SessionDeletion.delete("s2", id -> null, recordingManager());

        assertEquals(List.of("s2"), deleted);
        assertEquals(true, result.isDone());
    }
}

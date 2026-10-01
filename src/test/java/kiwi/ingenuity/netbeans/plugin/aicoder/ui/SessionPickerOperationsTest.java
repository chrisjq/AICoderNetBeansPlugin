package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SessionPersistenceManager.loadAll/save/delete each take a cross-process file lock with no timeout, so
 * SessionPickerOperations must never run them on the caller's thread (the EDT, in production) and must never
 * fail silently if the shared executor has been retired.
 */
class SessionPickerOperationsTest {

    private static final long WAIT_SECONDS = 10;

    @TempDir
    Path tmp;

    @AfterEach
    void tearDown() {
        // A prior test that shut the executor down must not leave later tests in this JVM without one.
        SessionPickerOperations.resetExecutorForTests();
    }

    private static AiSession newSession(String id) {
        return new AiSession(id, id, null, AiTypeEnum.CLAUDE, null,
                new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudeSessionSettings(),
                Instant.now(), Instant.now());
    }

    /**
     * Stands in for the real file-backed manager: an in-memory list a delete actually removes from, so a
     * later loadAll reflects it — the same contract the real one has, without touching disk.
     */
    private static class RecordingManager extends SessionPersistenceManager {

        final List<AiSession> stored;
        final List<Thread> loadCalledOn = new CopyOnWriteArrayList<>();
        final List<Thread> deleteCalledOn = new CopyOnWriteArrayList<>();
        final List<Thread> saveCalledOn = new CopyOnWriteArrayList<>();
        volatile IOException loadFailure;
        volatile String deleteFailureFor;
        volatile String deleteRuntimeFailureFor;
        volatile String saveFailureFor;

        RecordingManager(Path dir, List<AiSession> initial) {
            super(dir);
            stored = new CopyOnWriteArrayList<>(initial);
        }

        @Override
        public synchronized List<AiSession> loadAll() throws IOException {
            loadCalledOn.add(Thread.currentThread());
            if (loadFailure != null) {
                throw loadFailure;
            }
            return new ArrayList<>(stored);
        }

        @Override
        public synchronized void delete(String sessionId) throws IOException {
            deleteCalledOn.add(Thread.currentThread());
            if (sessionId.equals(deleteFailureFor)) {
                throw new IOException("disk full");
            }
            if (sessionId.equals(deleteRuntimeFailureFor)) {
                throw new IllegalStateException("boom");
            }
            stored.removeIf(s -> s.id().equals(sessionId));
        }

        @Override
        public synchronized void save(AiSession session) throws IOException {
            saveCalledOn.add(Thread.currentThread());
            if (session.id().equals(saveFailureFor)) {
                throw new IOException("disk full");
            }
            stored.add(session);
        }
    }

    private static SessionPickerOperations operationsFor(SessionPersistenceManager manager) {
        return new SessionPickerOperations(manager, id -> null);
    }

    @Test
    void loadAllRunsOffTheEdtNotOnIt() throws Exception {
        RecordingManager manager = new RecordingManager(tmp, List.of());
        SessionPickerOperations operations = operationsFor(manager);
        Thread[] edtThread = new Thread[1];
        CompletableFuture<List<AiSession>>[] future = new CompletableFuture[1];

        SwingUtilities.invokeAndWait(() -> {
            edtThread[0] = Thread.currentThread();
            future[0] = operations.loadAll();
        });
        future[0].get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, manager.loadCalledOn.size());
        assertFalse(manager.loadCalledOn.get(0) == edtThread[0],
                "loadAll must not run on the EDT thread that requested it");
        assertFalse(manager.loadCalledOn.get(0).getName().startsWith("AWT-EventQueue"),
                "loadAll must not run on the EDT");
    }

    @Test
    void loadAllReturnsWhatTheManagerHasStored() throws Exception {
        AiSession a = newSession("s1");
        AiSession b = newSession("s2");
        RecordingManager manager = new RecordingManager(tmp, List.of(a, b));
        SessionPickerOperations operations = operationsFor(manager);

        List<AiSession> result = operations.loadAll().get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(a, b), result);
    }

    @Test
    void loadAllFailureCompletesTheFutureExceptionallyRatherThanSilently() throws Exception {
        RecordingManager manager = new RecordingManager(tmp, List.of());
        manager.loadFailure = new IOException("locked");
        SessionPickerOperations operations = operationsFor(manager);

        CompletableFuture<List<AiSession>> future = operations.loadAll();

        ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> future.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("locked", failure.getCause().getMessage());
    }

    @Test
    void loadAllFailsVisiblyWhenTheExecutorHasBeenRetired() throws Exception {
        RecordingManager manager = new RecordingManager(tmp, List.of());
        SessionPickerOperations operations = operationsFor(manager);
        SessionPickerOperations.executor().shutdown();
        assertTrue(SessionPickerOperations.executor().awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));

        CompletableFuture<List<AiSession>> future = operations.loadAll();

        assertTrue(future.isCompletedExceptionally(),
                "a retired executor must fail the load visibly, not hang or silently return nothing");
        assertEquals(0, manager.loadCalledOn.size(), "the manager must never have been called at all");
    }

    @Test
    void deleteAllRunsOffTheEdtNotOnIt() throws Exception {
        RecordingManager manager = new RecordingManager(tmp, List.of(newSession("s1")));
        SessionPickerOperations operations = operationsFor(manager);
        Thread[] edtThread = new Thread[1];
        CompletableFuture<List<String>>[] future = new CompletableFuture[1];

        SwingUtilities.invokeAndWait(() -> {
            edtThread[0] = Thread.currentThread();
            future[0] = operations.deleteAll(List.of(newSession("s1")));
        });
        future[0].get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, manager.deleteCalledOn.size());
        assertFalse(manager.deleteCalledOn.get(0) == edtThread[0],
                "delete must not run on the EDT thread that requested it");
        assertFalse(manager.deleteCalledOn.get(0).getName().startsWith("AWT-EventQueue"),
                "delete must not run on the EDT");
    }

    @Test
    void deleteAllRemovesEverySessionAndTheNextLoadReflectsIt() throws Exception {
        AiSession keep = newSession("keep");
        AiSession gone = newSession("gone");
        RecordingManager manager = new RecordingManager(tmp, List.of(keep, gone));
        SessionPickerOperations operations = operationsFor(manager);

        List<String> failed = operations.deleteAll(List.of(gone)).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(List.of(), failed, "the delete must have succeeded");

        List<AiSession> remaining = operations.loadAll().get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(List.of(keep), remaining, "the list must reflect the completed delete");
    }

    @Test
    void deleteAllReportsOnlyTheSessionThatFailedNotTheOthers() throws Exception {
        AiSession ok = newSession("ok");
        AiSession bad = newSession("bad");
        RecordingManager manager = new RecordingManager(tmp, List.of(ok, bad));
        manager.deleteFailureFor = "bad";
        SessionPickerOperations operations = operationsFor(manager);

        List<String> failed = operations.deleteAll(List.of(ok, bad)).get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(bad.name()), failed, "must report exactly the session that failed to delete");
        List<AiSession> remaining = operations.loadAll().get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(List.of(bad), remaining, "the successfully deleted session must actually be gone");
    }

    /**
     * Review finding: a RuntimeException (not just IOException) from manager.delete used to escape the loop
     * before allOf(...).whenComplete was installed, so the future never completed and the picker stayed
     * disabled forever. This must complete within the bounded wait, not hang.
     */
    @Test
    void deleteAllCompletesRatherThanHangingWhenDeleteThrowsARuntimeException() throws Exception {
        AiSession ok = newSession("ok");
        AiSession bad = newSession("bad");
        RecordingManager manager = new RecordingManager(tmp, List.of(ok, bad));
        manager.deleteRuntimeFailureFor = "bad";
        SessionPickerOperations operations = operationsFor(manager);

        List<String> failed = operations.deleteAll(List.of(ok, bad)).get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(bad.name()), failed,
                "a RuntimeException from delete must be reported as a failed session, not left unhandled");
    }

    @Test
    void deleteAllLeavesASessionWithAnOpenTabToCloseAndDeleteTabInstead() throws Exception {
        AiSession openTab = newSession("open-tab");
        RecordingManager manager = new RecordingManager(tmp, List.of(openTab));
        CompletableFuture<Void> tabDeletion = new CompletableFuture<>();
        SessionPickerOperations operations = new SessionPickerOperations(manager, id -> tabDeletion);

        CompletableFuture<List<String>> future = operations.deleteAll(List.of(openTab));
        assertFalse(future.isDone(), "must wait on the tab's own deletion, not return early");
        tabDeletion.complete(null);

        assertEquals(List.of(), future.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0, manager.deleteCalledOn.size(),
                "a session an open tab is deleting must not also be deleted directly here");
    }

    @Test
    void deleteAllFailsVisiblyWhenTheExecutorHasBeenRetired() throws Exception {
        RecordingManager manager = new RecordingManager(tmp, List.of(newSession("s1")));
        SessionPickerOperations operations = operationsFor(manager);
        SessionPickerOperations.executor().shutdown();
        assertTrue(SessionPickerOperations.executor().awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));

        CompletableFuture<List<String>> future = operations.deleteAll(List.of(newSession("s1")));

        assertTrue(future.isCompletedExceptionally(),
                "a retired executor must fail the delete visibly, not hang or silently do nothing");
        assertEquals(0, manager.deleteCalledOn.size());
    }

    @Test
    void createAllRunsOffTheEdtNotOnIt() throws Exception {
        RecordingManager manager = new RecordingManager(tmp, List.of());
        SessionPickerOperations operations = operationsFor(manager);
        Thread[] edtThread = new Thread[1];
        CompletableFuture<List<AiSession>>[] future = new CompletableFuture[1];

        SwingUtilities.invokeAndWait(() -> {
            edtThread[0] = Thread.currentThread();
            future[0] = operations.createAll(List.of(newSession("s1")));
        });
        future[0].get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, manager.saveCalledOn.size());
        assertFalse(manager.saveCalledOn.get(0) == edtThread[0],
                "createAll must not run on the EDT thread that requested it");
        assertFalse(manager.saveCalledOn.get(0).getName().startsWith("AWT-EventQueue"),
                "createAll must not run on the EDT");
    }

    @Test
    void createAllFailsVisiblyWhenTheExecutorHasBeenRetired() throws Exception {
        RecordingManager manager = new RecordingManager(tmp, List.of());
        SessionPickerOperations operations = operationsFor(manager);
        SessionPickerOperations.executor().shutdown();
        assertTrue(SessionPickerOperations.executor().awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));

        CompletableFuture<List<AiSession>> future = operations.createAll(List.of(newSession("s1")));

        assertTrue(future.isCompletedExceptionally(),
                "a retired executor must fail the create visibly, not hang or silently do nothing");
        assertEquals(0, manager.saveCalledOn.size());
    }

    @Test
    void shutdownExecutorCompletesQueuedWorkThenReleasesWorkerThreads() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        SessionPickerOperations.executor().execute(() -> {
            started.countDown();
            try {
                Thread.sleep(300);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            completed.set(true);
        });
        assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS));

        SessionPickerOperations.shutdownExecutor();

        assertTrue(completed.get(), "in-flight work must complete during shutdown, not be discarded");
        assertTrue(SessionPickerOperations.executor().isTerminated());
    }
}

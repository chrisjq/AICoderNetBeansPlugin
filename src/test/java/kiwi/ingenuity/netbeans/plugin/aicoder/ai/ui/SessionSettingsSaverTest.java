package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The off-EDT settings save: the caller never waits for the file lock, and the file still ends up holding the
 * latest state with no request lost.
 */
class SessionSettingsSaverTest {

    private static final long WAIT_SECONDS = 10;

    @TempDir
    Path tmp;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /**
     * Returns once every task already queued on the pool has finished: the single worker runs them in order,
     * so a no-op queued behind them cannot start before they are done.
     */
    private void drain() throws Exception {
        pool.submit(() -> {
        }).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static void runAll(Queue<Runnable> tasks) {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            task.run();
        }
    }

    private static AiSession newSession() {
        return AiSession.create("/projects/MyApp", AiTypeEnum.CLAUDE);
    }

    /**
     * A manager whose save blocks until released, standing in for a save waiting on the cross-process file
     * lock.
     */
    private static final class GatedManager extends SessionPersistenceManager {

        final List<String> savedDescriptions = new CopyOnWriteArrayList<>();
        final CountDownLatch firstSaveStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirstSave = new CountDownLatch(1);
        final AtomicInteger saveCalls = new AtomicInteger();
        final List<String> events = new CopyOnWriteArrayList<>();

        GatedManager(Path dir) {
            super(dir);
        }

        @Override
        public synchronized void delete(String sessionId) {
            events.add("delete");
        }

        @Override
        public synchronized void save(AiSession session) throws IOException {
            boolean first = saveCalls.incrementAndGet() == 1;
            events.add("save");
            savedDescriptions.add(session.description());
            if (first) {
                firstSaveStarted.countDown();
                try {
                    releaseFirstSave.await(WAIT_SECONDS, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Test
    void requestSaveOnTheEdtReturnsWhileTheSaveIsStillBlockedOnTheLock() throws Exception {
        GatedManager manager = new GatedManager(tmp);
        AiSession session = newSession();
        SessionSettingsSaver saver = new SessionSettingsSaver(manager, session, () -> pool);
        CountDownLatch returned = new CountDownLatch(1);

        SwingUtilities.invokeLater(() -> {
            saver.requestSave();
            returned.countDown();
        });

        assertTrue(returned.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "requestSave must not wait for a save that is blocked on the file lock");
        assertTrue(manager.firstSaveStarted.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "the save must still run, off the calling thread");
        manager.releaseFirstSave.countDown();
    }

    @Test
    void aSaveRunsOnAnExecutorThreadNotTheCaller() throws Exception {
        Thread[] savedOn = new Thread[1];
        CountDownLatch saved = new CountDownLatch(1);
        SessionPersistenceManager manager = new SessionPersistenceManager(tmp) {
            @Override
            public synchronized void save(AiSession session) {
                savedOn[0] = Thread.currentThread();
                saved.countDown();
            }
        };
        new SessionSettingsSaver(manager, newSession(), () -> pool).requestSave();

        assertTrue(saved.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertFalse(savedOn[0] == Thread.currentThread(), "the save must not run on the requesting thread");
    }

    @Test
    void aRequestMadeWhileASaveIsInFlightIsNotLost() throws Exception {
        GatedManager manager = new GatedManager(tmp);
        AiSession session = newSession();
        SessionSettingsSaver saver = new SessionSettingsSaver(manager, session, () -> pool);

        session.setDescription("first");
        saver.requestSave();
        assertTrue(manager.firstSaveStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        session.setDescription("second");
        saver.requestSave();
        manager.releaseFirstSave.countDown();

        drain();
        assertEquals(List.of("first", "second"), manager.savedDescriptions,
                "the change made during the first save must be written by a second one");
    }

    @Test
    void requestsQueuedBehindASaveAreFoldedIntoOneAndWriteTheLatestState() throws Exception {
        GatedManager manager = new GatedManager(tmp);
        AiSession session = newSession();
        SessionSettingsSaver saver = new SessionSettingsSaver(manager, session, () -> pool);

        session.setDescription("v0");
        saver.requestSave();
        assertTrue(manager.firstSaveStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        for (int i = 1; i <= 50; i++) {
            session.setDescription("v" + i);
            saver.requestSave();
        }
        manager.releaseFirstSave.countDown();

        drain();
        assertEquals(2, manager.saveCalls.get(), "fifty queued requests must fold into a single follow-up save");
        assertEquals("v50", manager.savedDescriptions.get(1), "the follow-up save must write the latest state");
    }

    @Test
    void manyChangesLeaveTheLatestOneInTheFile() throws Exception {
        SessionPersistenceManager manager = new SessionPersistenceManager(tmp);
        AiSession session = newSession();
        SessionSettingsSaver saver = new SessionSettingsSaver(manager, session, () -> pool);

        for (int i = 0; i < 200; i++) {
            session.setDescription("v" + i);
            saver.requestSave();
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));

        List<AiSession> loaded = manager.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("v199", loaded.get(0).description(), "the file must hold the last change made");
    }

    @Test
    void deleteAndAbandonOnTheEdtReturnsWhileASaveIsHeldOnTheFileLock() throws Exception {
        GatedManager manager = new GatedManager(tmp);
        AiSession session = newSession();
        SessionSettingsSaver saver = new SessionSettingsSaver(manager, session, () -> pool);
        saver.requestSave();
        assertTrue(manager.firstSaveStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        CountDownLatch returned = new CountDownLatch(1);
        CompletableFuture<Void>[] deletion = new CompletableFuture[1];

        SwingUtilities.invokeLater(() -> {
            deletion[0] = saver.deleteAndAbandon();
            returned.countDown();
        });

        assertTrue(returned.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "deleteAndAbandon must not wait for a save that is blocked on the file lock");
        assertFalse(deletion[0].isDone(), "the delete must not have run while the save still holds the lock");
        manager.releaseFirstSave.countDown();
        deletion[0].get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(List.of("save", "delete"), manager.events);
    }

    @Test
    void theDeleteRunsAfterTheSaveInFlightAndNoSaveFollowsIt() throws Exception {
        GatedManager manager = new GatedManager(tmp);
        AiSession session = newSession();
        SessionSettingsSaver saver = new SessionSettingsSaver(manager, session, () -> pool);
        saver.requestSave();
        assertTrue(manager.firstSaveStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        saver.requestSave();

        CompletableFuture<Void> deletion = saver.deleteAndAbandon();
        saver.requestSave();
        manager.releaseFirstSave.countDown();
        deletion.get(WAIT_SECONDS, TimeUnit.SECONDS);
        drain();

        assertEquals(List.of("save", "delete"), manager.events,
                "a save queued before the delete and one requested after it must both be skipped");
    }

    @Test
    void aSaveQueuedBeforeTheDeleteCannotBringTheDeletedSessionBack() throws Exception {
        Queue<Runnable> held = new ArrayDeque<>();
        SessionPersistenceManager manager = new SessionPersistenceManager(tmp);
        AiSession session = newSession();
        manager.save(session);
        SessionSettingsSaver saver = new SessionSettingsSaver(manager, session, () -> held::add);
        saver.requestSave();

        CompletableFuture<Void> deletion = saver.deleteAndAbandon();
        saver.requestSave();
        runAll(held);

        assertTrue(deletion.isDone() && !deletion.isCompletedExceptionally());
        assertEquals(0, manager.loadAll().size(), "the deleted session must not be written back by its tab");
    }

    @Test
    void aSaveRequestedAfterTheDeleteCannotBringTheDeletedSessionBack() throws Exception {
        Queue<Runnable> held = new ArrayDeque<>();
        SessionPersistenceManager manager = new SessionPersistenceManager(tmp);
        AiSession session = newSession();
        manager.save(session);
        SessionSettingsSaver saver = new SessionSettingsSaver(manager, session, () -> held::add);

        saver.deleteAndAbandon();
        runAll(held);
        assertEquals(0, manager.loadAll().size(), "the delete itself must have removed the session");
        saver.requestSave();
        runAll(held);

        assertEquals(0, manager.loadAll().size(), "a save requested after the delete must not write the session back");
    }

    @Test
    void aRetiredExecutorDeletesOnTheCaller() throws Exception {
        SessionPersistenceManager manager = new SessionPersistenceManager(tmp);
        AiSession session = newSession();
        manager.save(session);
        Executor retired = task -> {
            throw new RejectedExecutionException("retired");
        };

        CompletableFuture<Void> deletion = new SessionSettingsSaver(manager, session, () -> retired).deleteAndAbandon();

        assertTrue(deletion.isDone() && !deletion.isCompletedExceptionally());
        assertEquals(0, manager.loadAll().size(), "a delete refused by a retired pool must still happen");
    }

    @Test
    void aFailedDeleteCompletesTheFutureExceptionally() throws Exception {
        SessionPersistenceManager manager = new SessionPersistenceManager(tmp) {
            @Override
            public synchronized void delete(String sessionId) throws IOException {
                throw new IOException("locked");
            }
        };
        SessionSettingsSaver saver = new SessionSettingsSaver(manager, newSession(), () -> pool);

        CompletableFuture<Void> deletion = saver.deleteAndAbandon();

        ExecutionException failure = assertThrows(ExecutionException.class, () -> deletion.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("locked", failure.getCause().getMessage());
    }

    @Test
    void aRetiredExecutorFallsBackToSavingOnTheCallerSoNothingIsDropped() {
        AtomicInteger saves = new AtomicInteger();
        SessionPersistenceManager manager = new SessionPersistenceManager(tmp) {
            @Override
            public synchronized void save(AiSession session) {
                saves.incrementAndGet();
            }
        };
        Executor retired = task -> {
            throw new RejectedExecutionException("retired");
        };
        SessionSettingsSaver saver = new SessionSettingsSaver(manager, newSession(), () -> retired);

        saver.requestSave();
        assertEquals(1, saves.get(), "a save refused by a retired pool must run inline");

        saver.requestSave();
        assertEquals(2, saves.get(), "a refused request must not leave the saver stuck as 'already queued'");
    }

    @Test
    void aFailedSaveIsLoggedAndTheNextRequestStillSaves() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SessionPersistenceManager manager = new SessionPersistenceManager(tmp) {
            @Override
            public synchronized void save(AiSession session) throws IOException {
                calls.incrementAndGet();
                throw new IOException("disk full");
            }
        };
        SessionSettingsSaver saver = new SessionSettingsSaver(manager, newSession(), () -> pool);

        saver.requestSave();
        drain();
        assertEquals(1, calls.get());
        saver.requestSave();
        drain();

        assertEquals(2, calls.get(), "a failed save must not wedge later ones");
    }
}

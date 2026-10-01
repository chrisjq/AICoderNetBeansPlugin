package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;

/**
 * Persists one session's settings off the caller's thread. {@code SessionPersistenceManager.save} takes a
 * cross-process file lock with no timeout, so it must not run on the EDT.
 *
 * <p>
 * A save writes the session as it is when the save runs, so requests made while one is already queued are
 * folded into it: the file always ends up holding the latest state, saves never overlap, and none is
 * lost.</p>
 */
final class SessionSettingsSaver {

    private static final Logger LOG = Logger.getLogger(SessionSettingsSaver.class.getName());

    private final SessionPersistenceManager manager;
    private final AiSession session;
    private final Supplier<? extends Executor> executor;
    private final AtomicBoolean queued = new AtomicBoolean();
    private final Object saveLock = new Object();
    private volatile boolean abandoned;

    SessionSettingsSaver(SessionPersistenceManager manager, AiSession session, Supplier<? extends Executor> executor) {
        this.manager = manager;
        this.session = session;
        this.executor = executor;
    }

    /**
     * Queues a save of the session's current state. Returns immediately. The save runs on the calling thread
     * only when the executor has been retired, which happens only during plugin shutdown: at that point
     * losing the save is worse than a brief wait, so the caller blocks on the file lock rather than drop it.
     */
    void requestSave() {
        if (queued.getAndSet(true)) {
            return;
        }
        try {
            executor.get().execute(this::runSave);
        }
        catch (RejectedExecutionException e) {
            runSave();
        }
    }

    /**
     * Refuses every later save, so a session that is being deleted is not written back into the index, then
     * deletes it on the saver's executor. Returns immediately: the delete runs after a save that is already
     * writing. It runs on the caller's thread only when the executor has been retired, which happens only
     * during plugin shutdown, when losing the delete is worse than a brief wait. The future completes when
     * the
     * delete has finished.
     */
    CompletableFuture<Void> deleteAndAbandon() {
        abandoned = true;
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable delete = () -> runDelete(done);
        try {
            executor.get().execute(delete);
        }
        catch (RejectedExecutionException e) {
            delete.run();
        }
        return done;
    }

    private void runDelete(CompletableFuture<Void> done) {
        synchronized (saveLock) {
            try {
                manager.delete(session.id());
                done.complete(null);
            }
            catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not delete session " + session.id(), e);
                done.completeExceptionally(e);
            }
        }
    }

    private void runSave() {
        synchronized (saveLock) {
            queued.set(false);
            if (abandoned) {
                return;
            }
            try {
                manager.save(session);
            }
            catch (IOException e) {
                LOG.log(Level.WARNING, "Could not persist session config update", e);
            }
        }
    }
}

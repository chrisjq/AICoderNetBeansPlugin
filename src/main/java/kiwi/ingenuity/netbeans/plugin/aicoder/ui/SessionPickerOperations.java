package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;

/**
 * Off-EDT loadAll/delete orchestration for {@link SessionPickerDialog}. {@code SessionPersistenceManager}'s
 * loadAll, save and delete each take a cross-process file lock with no timeout, so none of them may run on
 * the EDT — mirrors {@code SessionSettingsSaver}/{@code SessionDeletion}'s identical reasoning for the same
 * manager. The dialog stays thin wiring: it calls in here and applies the result on the EDT itself.
 */
public final class SessionPickerOperations {

    private static final Logger LOG = Logger.getLogger(SessionPickerOperations.class.getName());
    private static volatile ExecutorService EXECUTOR = newExecutor();

    private static ExecutorService newExecutor() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "aicoder-session-picker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Retires the shared pool at plugin shutdown, mirroring {@code AiTopComponent.shutdownPersistExecutor()}:
     * lets an already-queued load/delete finish inside a bounded window, then releases the worker threads.
     * Called only from the module installer's shutdown path.
     */
    public static void shutdownExecutor() {
        ExecutorService pool = EXECUTOR;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(TimeoutEnum.PERSIST_EXECUTOR_SHUTDOWN_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS)) {
                LOG.warning("Session-picker tasks still running at plugin shutdown; abandoning the bounded wait");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The live shared pool (test aid).
     */
    static ExecutorService executor() {
        return EXECUTOR;
    }

    /**
     * Replaces a retired pool with a fresh one so later tests sharing this JVM still have a working executor
     * (test aid — production shuts down exactly once, at plugin shutdown).
     */
    static void resetExecutorForTests() {
        EXECUTOR = newExecutor();
    }

    private final SessionPersistenceManager manager;
    private final Function<String, CompletableFuture<Void>> closeAndDeleteTab;

    SessionPickerOperations(SessionPersistenceManager manager, Function<String, CompletableFuture<Void>> closeAndDeleteTab) {
        this.manager = manager;
        this.closeAndDeleteTab = closeAndDeleteTab;
    }

    /**
     * Loads every stored session off the EDT. The returned future completes exceptionally — never silently
     * — if the executor has been retired (plugin shutdown while the picker was open) or the load itself
     * failed.
     */
    CompletableFuture<List<AiSession>> loadAll() {
        CompletableFuture<List<AiSession>> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(manager.loadAll());
            }
            catch (IOException | RuntimeException e) {
                future.completeExceptionally(e);
            }
        };
        try {
            EXECUTOR.execute(task);
        }
        catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Deletes every session in {@code toDelete} — one with an open tab is left to
     * {@code closeAndDeleteTab}/{@code SessionDeletion}, any other is deleted directly by
     * {@code SessionPersistenceManager.delete}, off the EDT either way. The returned future completes with
     * the names of any sessions that could not be deleted (empty means all of them were), or exceptionally
     * only if the executor itself has been retired.
     */
    CompletableFuture<List<String>> deleteAll(List<AiSession> toDelete) {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                List<String> failedNames = new CopyOnWriteArrayList<>();
                List<CompletableFuture<Void>> deletions = new ArrayList<>();
                for (AiSession session : toDelete) {
                    CompletableFuture<Void> deletion;
                    try {
                        deletion = SessionDeletion.delete(session.id(), closeAndDeleteTab, manager);
                    }
                    catch (IOException | RuntimeException e) {
                        LOG.log(Level.WARNING, "Could not delete session " + session.id(), e);
                        failedNames.add(session.name());
                        continue;
                    }
                    deletions.add(deletion.exceptionally(ex -> {
                        LOG.log(Level.WARNING, "Could not delete session " + session.id(), ex);
                        failedNames.add(session.name());
                        return null;
                    }));
                }
                CompletableFuture.allOf(deletions.toArray(CompletableFuture[]::new))
                        .whenComplete((ok, err) -> future.complete(List.copyOf(failedNames)));
            }
            // Outer guard: the loop above already turns every expected failure (IOException/RuntimeException
            // from SessionDeletion.delete, or an exceptional per-session future) into a failed name rather
            // than a thrown exception, but anything that still escapes must not leave the future — and the
            // dialog it's disabling controls for — hanging forever.
            catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        };
        try {
            EXECUTOR.execute(task);
        }
        catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Saves each already-built session in {@code toCreate} off the EDT, same as {@code loadAll}/
     * {@code deleteAll}. Returns the ones that saved successfully, in order; one that failed to save is
     * logged and dropped — never included as if it had succeeded — so callers only ever open/track sessions
     * that are actually on disk. Completes exceptionally, never silently, only if the executor itself has
     * been retired.
     */
    CompletableFuture<List<AiSession>> createAll(List<AiSession> toCreate) {
        CompletableFuture<List<AiSession>> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                List<AiSession> created = new ArrayList<>();
                for (AiSession session : toCreate) {
                    try {
                        manager.save(session);
                        created.add(session);
                    }
                    catch (IOException | RuntimeException e) {
                        LOG.log(Level.WARNING, "Could not save session " + session.name(), e);
                    }
                }
                future.complete(List.copyOf(created));
            }
            catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        };
        try {
            EXECUTOR.execute(task);
        }
        catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;

/**
 * Single owner of every temp file the plugin creates (pasted images, spooled tool results) and of the directories
 * holding them.
 *
 * <p>
 * Layout — each session gets one registry-owned tree next to its own config data:
 * {@code ~/.ai-coder/{type}/{sessionId}/tmp/}, with optional subdirectories (e.g. {@code tool_results}). Because the
 * tree sits inside the session's config directory, the per-session scope exemption
 * ({@code SessionFileScopeRegistry.isOwnSessionConfigFile}) lets the owning AI read its own temp content back through
 * the file tools even under restrict-to-project. Nothing outside this class may create or delete anything in the
 * {@code tmp} tree.
 *
 * <p>
 * Naming — every created file embeds its owner for at-a-glance attribution: {@code <name>-<sessionId>-<random><ext>}
 * (e.g. {@code ai-coder-paste-b1946ac9-….png}). The session-id portion is sanitized to {@code [A-Za-z0-9._-]} so a
 * hostile id can never inject separators into the name.
 *
 * <p>
 * Lifetime — two independent mechanisms guarantee nothing is left behind:
 * <ol>
 * <li><b>Whole-tree removal</b> on session close ({@link #cleanupSession}/{@link #cleanupSessionAsync}) and on IDE
 * shutdown / plugin uninstall ({@link #cleanupAll}), deleting the entire {@code tmp} directory recursively.</li>
 * <li><b>Age sweep</b> — a lazily started daemon thread (the {@code McpServerRegistry} pattern: started on first
 * registration, self-stopping when nothing is left to watch) periodically deletes individual tracked files older than
 * {@link #maxAgeMillis}, measured from the recorded creation time. It bounds accumulation in very long-lived sessions;
 * directories are never swept.</li>
 * </ol>
 *
 * <p>
 * Threading — all registry state ({@link #TRACKED_FILES}, {@link #TRACKED_SESSION_TMP_ROOTS}, {@link #sweeper}) is
 * guarded by {@link #LOCK}. Sweeping iterates a deep copy taken under the lock, so concurrent registrations can never
 * corrupt an iteration, and file deletion happens outside the lock.
 *
 * <p>
 * Failure policy — creation and deletion are best-effort and never throw: failures are logged at FINE and surface to
 * callers as a {@code null} return (creation) or a silent skip (deletion). Callers treat a {@code null}
 * {@link TempFile} as "no temp support available", never as an error.
 *
 * <p>
 * Testing — the package-private {@link #maxAgeMillis}, {@link #sweepIntervalMillis}, {@link #overrideBasePath} and
 * {@link #deleteHook} fields exist purely for unit tests: tests shorten the durations, point the layout at a scratch
 * directory without needing a live {@link McpServerRegistry}, and observe deletions via the hook. Production code never
 * touches them.
 */
public final class TempFileRegistry {

    private static final Logger LOG = Logger.getLogger(TempFileRegistry.class.getName());

    /**
     * Name of the registry-owned directory created inside each session's config directory. Nothing else in the plugin
     * writes to this name.
     */
    public static final String TEMP_DIR_NAME = "tmp";

    /**
     * Age (measured from the RECORDED creation time, not filesystem mtime) at which a temp file becomes sweepable.
     * Supplier form so tests can shorten it; defaults to {@link TimeoutEnum#TEMP_FILE_MAX_AGE_MILLIS}.
     */
    static volatile TimePeriodMillis maxAgeMillis = () -> TimeoutEnum.TEMP_FILE_MAX_AGE_MILLIS.millis();

    /**
     * Pause between sweeper passes. Supplier form so tests can shorten it; defaults to
     * {@link TimeoutEnum#TEMP_FILE_SWEEP_INTERVAL_MILLIS}.
     */
    static volatile TimePeriodMillis sweepIntervalMillis = () -> TimeoutEnum.TEMP_FILE_SWEEP_INTERVAL_MILLIS.millis();

    /**
     * Test-only replacement for the {@code ~/.ai-coder} configuration root. When non-null, session temp trees are
     * placed at {@code {overrideBasePath}/{sessionId}/tmp/} and resolved WITHOUT consulting {@link McpServerRegistry} —
     * so pure unit tests need no live MCP server. Always null in production.
     */
    static volatile Path overrideBasePath = null;

    /**
     * Test-only observer notified after an individual file is successfully deleted by {@link #deleteTempFile} (which
     * includes the age sweep). Whole-directory cleanups do NOT notify per file. Null outside tests.
     */
    static volatile DeleteHook deleteHook = null;

    /**
     * Test-only replacement for the production config-dir lookup (see {@link SessionConfigDirResolver}). Ignored while
     * {@link #overrideBasePath} is set — that shortcut wins. Always null in production.
     */
    private static volatile SessionConfigDirResolver configDirResolverOverride = null;

    private static final Map<String, Set<TempFile>> TRACKED_FILES = new HashMap<>();

    /**
     * Session id to the registry-owned {@code tmp} root. An entry exists from a session's first temp file until
     * explicit cleanup ({@link #cleanupSession}/{@link #cleanupAll}); the age sweep removes files but deliberately
     * never untracks the root, so the directory stays reachable for both {@link #getSessionTempDir} consumers and the
     * eventual whole-tree cleanup.
     */
    private static final Map<String, Path> TRACKED_SESSION_TMP_ROOTS = new HashMap<>();

    /**
     * Guard for both structures and the sweeper reference; also the sweeper's wait monitor.
     */
    private static final Object LOCK = new Object();

    /**
     * The running sweeper thread, null when idle.
     */
    private static Thread sweeper;

    /**
     * Points production-layout resolution at a fake config-dir source and drops all cached roots, so the next
     * {@code createTempFile} exercises the same code path as production. Call {@link #resetForTests} afterwards.
     */
    static void overrideConfigDirResolverForTests(SessionConfigDirResolver resolver) {
        synchronized (LOCK) {
            configDirResolverOverride = resolver;
            TRACKED_SESSION_TMP_ROOTS.clear();
        }
    }

    /**
     * Creates a tracked temp file directly in the session's {@code tmp} root.
     *
     * @return the tracked file, or null on any failure (see class javadoc failure policy)
     */
    public static TempFile createTempFile(String sessionId, String name, String extension) {
        return createTempFile(sessionId, null, name, extension);
    }

    /**
     * Creates a tracked temp file in the given subdirectory of the session's {@code tmp} root (created on demand), or
     * in the root itself when {@code subDirectory} is null/blank.
     *
     * @return the tracked file, or null on any failure (see class javadoc failure policy)
     */
    public static TempFile createTempFile(String sessionId, String subDirectory, String name, String extension) {
        if (sessionId == null || sessionId.isBlank() || name == null || name.isBlank()) {
            return null;
        }
        try {
            return createImpl(sessionId, subDirectory, name, extension);
        }
        catch (IOException | RuntimeException e) {
            // Defensive net: creation must never throw into tool code, whatever the
            // filesystem or arguments look like. Callers treat null as "no temp support".
            LOG.log(Level.FINE, "Could not create temp file for session " + sessionId, e);
            return null;
        }
    }

    /**
     * Convenience overload taking the session object.
     *
     * @return the tracked file, or null on any failure (see class javadoc failure policy)
     */
    public static TempFile createTempFile(AiSession session, String name, String extension) {
        if (session == null || session.id() == null || session.id().isBlank()) {
            return null;
        }
        return createTempFile(session.id(), name, extension);
    }

    private static TempFile createImpl(String sessionId, String subDir, String prefix, String extension)
            throws IOException {
        Path sessionTmpRoot = resolveSessionTmpRootOrNull(sessionId);
        if (sessionTmpRoot == null) {
            return null;
        }

        Path dir = sessionTmpRoot;
        if (subDir != null && !subDir.isBlank()) {
            dir = sessionTmpRoot.resolve(subDir);
        }
        // Both branches: the tmp root itself does not exist until first use either.
        Files.createDirectories(dir);

        // Names carry the owner for readability at a glance
        // ({@code paste-<session>-<random>.png}); the session id is sanitized so a
        // hostile id can never inject a path separator into the generated name.
        String safeId = sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        File file = File.createTempFile(prefix + "-" + safeId + "-", extension, dir.toFile());

        synchronized (LOCK) {
            Set<TempFile> tracked = TRACKED_FILES.get(sessionId);

            if (tracked == null) {
                tracked = new HashSet<>();
                TRACKED_FILES.put(sessionId, tracked);
            }

            TempFile tempFile = new TempFile(file.toPath(), sessionId, System.currentTimeMillis());
            tracked.add(tempFile);
            ensureSweeperLocked();
            return tempFile;
        }
    }

    /**
     * Resolves (and caches) the registry-owned {@code tmp} root for one session, honouring {@link #overrideBasePath}.
     *
     * @return the root directory, or null when the session cannot be placed (no MCP server in production mode, unknown
     * session type, or unavailable configuration root)
     */
    private static Path resolveSessionTmpRootOrNull(String sessionId) {
        synchronized (LOCK) {
            Path cached = TRACKED_SESSION_TMP_ROOTS.get(sessionId);
            if (cached != null) {
                return cached;
            }

            Path fresh;
            if (overrideBasePath != null) {
                fresh = overrideBasePath.toAbsolutePath().resolve(sessionId).resolve(TEMP_DIR_NAME);
            }
            else {
                if (sessionId == null || sessionId.isBlank()) {
                    return null;
                }
                Path sessionConfigDir;
                SessionConfigDirResolver resolverOverride = configDirResolverOverride;
                if (resolverOverride != null) {
                    sessionConfigDir = resolverOverride.configDirOrNull(sessionId);
                }
                else {
                    McpHookServer server = McpServerRegistry.getServer();
                    sessionConfigDir = server == null ? null : server.sessionConfigDirOrNull(sessionId);
                }
                if (sessionConfigDir == null) {
                    LOG.log(Level.FINE, "No session config dir for session {0}; cannot create temp files", sessionId);
                    return null;
                }
                // {configRoot}/{type}/{sessionId} -> {configRoot}/{type}/{sessionId}/tmp
                // (append, never resolveSibling: the root must stay per-session so
                // cleanupSession can never touch another live session's files and
                // isOwnSessionConfigFile keeps covering everything we create)
                fresh = sessionConfigDir.resolve(TEMP_DIR_NAME);
            }

            TRACKED_SESSION_TMP_ROOTS.put(sessionId, fresh);
            return fresh;
        }
    }

    // ---- Lifecycle cleanup ----
    /**
     * Removes the given session's registered temp directory (recursively) and drops its tracking entries. Other
     * sessions' temp dirs and everything else are untouched by construction — this only ever acts on the one directory
     * {@code sessionId} itself registered. Safe to call for sessions that never created temp files (no-op).
     */
    public static void cleanupSession(String sessionId) {
        Path remove = null;
        synchronized (LOCK) {
            TRACKED_FILES.remove(sessionId);
            remove = TRACKED_SESSION_TMP_ROOTS.remove(sessionId);
        }

        if (remove != null) {
            deleteDirectory(remove);
        }
    }

    /**
     * Runs {@link #cleanupSession} on a fresh daemon thread, for callers on UI or request threads that must not block
     * on recursive disk deletion.
     */
    public static void cleanupSessionAsync(String sessionId) {
        Thread t = new Thread(() -> cleanupSession(sessionId), "ai-coder-tempfile-cleanup-" + sessionId);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Removes EVERY session's registered temp directory and resets the registry to its pristine state (including
     * stopping the sweeper). Called exactly once by the module installer on IDE shutdown / plugin uninstall.
     */
    public static void cleanupAll() {
        List<Path> dirs;
        synchronized (LOCK) {
            dirs = new ArrayList<>(TRACKED_SESSION_TMP_ROOTS.values());
            TRACKED_SESSION_TMP_ROOTS.clear();
            TRACKED_FILES.clear();
            stopSweeperIfEmptyLocked();
        }

        for (Path dir : dirs) {
            deleteDirectory(dir);
        }
    }

    // ---- Sweeper (lazy daemon, McpServerRegistry pattern) ----
    private static void ensureSweeperLocked() {
        if (sweeper != null && sweeper.isAlive()) {
            return;
        }
        sweeper = new Thread(TempFileRegistry::sweepLoop, "ai-coder-tempfile-sweeper");
        sweeper.setDaemon(true);
        sweeper.start();
    }

    private static void stopSweeperIfEmptyLocked() {
        if (!TRACKED_FILES.isEmpty()) {
            return;
        }
        Thread t = sweeper;
        sweeper = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private static void sweepLoop() {
        try {
            while (true) {
                for (TempFile tf : collectExpired()) {
                    deleteTempFile(tf);
                }

                boolean exit;
                synchronized (LOCK) {
                    if (TRACKED_FILES.isEmpty()) {
                        // Nothing left tracked (everything swept or cleaned up): shut
                        // down until the next registration starts us afresh.
                        if (sweeper == Thread.currentThread()) {
                            sweeper = null;
                        }
                        exit = true;
                    }
                    else {
                        exit = false;
                    }
                }
                if (exit) {
                    return;
                }

                Thread.sleep(sweepIntervalMillis.millis());
            }
        }
        catch (InterruptedException e) {
            // Stopped via interrupt (registry emptied or tests reset): exit quietly.
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Snapshot of all tracked files older than {@link #maxAgeMillis}, taken via a DEEP copy under {@link #LOCK} so the
     * caller can inspect sets without holding the lock while other threads register or remove entries.
     */
    private static List<TempFile> collectExpired() {
        Map<String, Set<TempFile>> snapshot;
        synchronized (LOCK) {
            snapshot = new HashMap<>(TRACKED_FILES.size());
            for (Map.Entry<String, Set<TempFile>> entry : TRACKED_FILES.entrySet()) {
                snapshot.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
        }

        long cutoff = System.currentTimeMillis() - Math.max(0L, maxAgeMillis.millis());
        List<TempFile> expired = new ArrayList<>();
        for (Set<TempFile> files : snapshot.values()) {
            for (TempFile tf : files) {
                if (tf.createdAt() < cutoff) {
                    expired.add(tf);
                }
            }
        }
        return expired;
    }

    /**
     * Untracks and deletes one file. No-op (never throws) when the file was already untracked, e.g. by a racing
     * {@link #cleanupSession}. Fires {@link #deleteHook} only when the file was actually deleted from disk.
     */
    public static void deleteTempFile(TempFile tempFile) {
        if (tempFile == null || tempFile.sessionId() == null) {
            return;
        }

        boolean removed = false;
        synchronized (LOCK) {
            Set<TempFile> tracked = TRACKED_FILES.get(tempFile.sessionId());
            if (tracked != null) {
                removed = tracked.remove(tempFile);
                if (tracked.isEmpty()) {
                    TRACKED_FILES.remove(tempFile.sessionId());
                    // The tmp ROOT deliberately stays tracked here: the directory may
                    // still hold other content (e.g. tool_results), and keeping the
                    // mapping is what lets a later cleanupSession/cleanupAll find and
                    // delete it. Only lifecycle cleanup removes the mapping.
                }
            }

            stopSweeperIfEmptyLocked();
        }

        if (removed) {
            if (deleteFile(tempFile.path())) {
                DeleteHook hook = deleteHook;
                if (hook != null) {
                    hook.onDeleted(tempFile);
                }
            }
        }
    }

    /**
     * Delete a directory, best effort.
     */
    private static void deleteDirectory(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(TempFileRegistry::deleteFile);
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Could not walk temp directory " + dir, e);
        }
    }

    /**
     * Delete a file if it exists, best effort.
     *
     * @return true iff the file existed and was deleted
     */
    private static boolean deleteFile(Path path) {
        try {
            return Files.deleteIfExists(path);
        }
        catch (IOException | SecurityException e) {
            LOG.log(Level.FINE, "Could not delete temp file " + path, e);
        }

        return false;
    }

    /**
     * The registry-owned {@code tmp} root for a session, or null when the session has not created any registry temp
     * content yet (or was already cleaned up). Consumers such as {@code TmpMarkerExpander} must treat null as "expand
     * to nothing".
     */
    public static Path getSessionTempDir(String id) {
        synchronized (LOCK) {
            return TRACKED_SESSION_TMP_ROOTS.get(id);
        }
    }

    // ---- Test observability ----
    // Everything below exists purely for unit tests (see class javadoc): it resets state between tests and exposes
    // internal counts/thread state that are otherwise unobservable. Never call from production code.
    /**
     * Returns the registry to its pristine state: clears all tracking, interrupts the sweeper, restores the production
     * durations and clears the test overrides ({@link #overrideBasePath}, {@link #deleteHook}). Files already on disk
     * are NOT deleted — point {@link #overrideBasePath} at a scratch directory and clean it up yourself.
     */
    static void resetForTests() {
        synchronized (LOCK) {
            TRACKED_FILES.clear();
            TRACKED_SESSION_TMP_ROOTS.clear();
            stopSweeperIfEmptyLocked();
        }
        maxAgeMillis = () -> TimeoutEnum.TEMP_FILE_MAX_AGE_MILLIS.millis();
        sweepIntervalMillis = () -> TimeoutEnum.TEMP_FILE_SWEEP_INTERVAL_MILLIS.millis();
        overrideBasePath = null;
        deleteHook = null;
        configDirResolverOverride = null;
    }

    /**
     * Number of individually tracked files across all sessions (test aid).
     */
    static int trackedFileCount() {
        synchronized (LOCK) {
            return TRACKED_FILES.values().stream().mapToInt(Set::size).sum();
        }
    }

    /**
     * Number of tracked registry-owned temp roots (test aid).
     */
    static int ownedDirCount() {
        synchronized (LOCK) {
            return TRACKED_SESSION_TMP_ROOTS.size();
        }
    }

    /**
     * True when the lazy sweeper daemon is currently alive (test aid).
     */
    static boolean sweeperRunning() {
        synchronized (LOCK) {
            Thread t = sweeper;
            return t != null && t.isAlive();
        }
    }

    private TempFileRegistry() {
    }

    /**
     * Resolves the per-session configuration directory ({@code ~/.ai-coder/{type}/{sessionId}/}) for one session id, or
     * null when the session cannot be placed. Production backs this with {@link McpHookServer#sessionConfigDirOrNull};
     * tests may substitute a fake so the production layout branch is exercisable without a live MCP server.
     */
    interface SessionConfigDirResolver {

        Path configDirOrNull(String sessionId);
    }

    /**
     * Supplies a duration in milliseconds. Exists so tests can substitute short values for the
     * {@link TimeoutEnum}-backed defaults without touching production constants.
     */
    public interface TimePeriodMillis {

        long millis();
    }

    /**
     * Observer for individual temp-file deletions (test aid — see {@link #deleteHook}).
     */
    public interface DeleteHook {

        void onDeleted(TempFile file);
    }
}

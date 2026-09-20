package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import org.openide.util.NbPreferences;

/**
 * Coordinates the one OpenCode start that may migrate a shared database after an OpenCode CLI version change. The lock
 * is an operating-system file lock, so it also protects another IDE or an OpenCode process outside this JVM.
 */
final class OpenCodeStartupCoordinator implements AutoCloseable {

    static final long DEFAULT_WAIT_MILLIS = 30_000L;
    private static final Logger LOG = Logger.getLogger(OpenCodeStartupCoordinator.class.getName());
    private static final Preferences PREFERENCES = NbPreferences.forModule(OpenCodeStartupCoordinator.class);

    private final String version;
    private final String versionKey;
    private final FileChannel channel;
    private final FileLock lock;
    private final boolean leader;

    private OpenCodeStartupCoordinator(String version, String versionKey, FileChannel channel, FileLock lock, boolean leader) {
        this.version = version;
        this.versionKey = versionKey;
        this.channel = channel;
        this.lock = lock;
        this.leader = leader;
    }

    static OpenCodeStartupCoordinator acquire(Path database, String version) {
        return acquire(database, version, DEFAULT_WAIT_MILLIS);
    }

    static OpenCodeStartupCoordinator acquire(Path database, String version, long waitMillis) {
        if (database == null || version == null || version.isBlank()) {
            return none(null);
        }
        String versionKey = versionKey(database);
        // The steady-state path is intentionally only an in-memory preferences
        // lookup: no lock-file open, directory creation, or coordination I/O.
        if (hasDatabaseContent(database) && version.equals(PREFERENCES.get(versionKey, null))) {
            return none(version);
        }
        Path lockFile = database.resolveSibling(database.getFileName() + ".aicoder-start.lock");
        try {
            Path parent = lockFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
            do {
                try {
                    FileLock lock = channel.tryLock();
                    if (lock != null) {
                        if (hasDatabaseContent(database) && version.equals(PREFERENCES.get(versionKey, null))) {
                            lock.release();
                            channel.close();
                            return none(version);
                        }
                        return new OpenCodeStartupCoordinator(version, versionKey, channel, lock, true);
                    }
                }
                catch (OverlappingFileLockException e) {
                    // Another session in this JVM is the leader; wait just as for
                    // an external process.
                }
                try {
                    Thread.sleep(100L);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    channel.close();
                    return none(version);
                }
            }
            while (System.nanoTime() < deadline);
            channel.close();
            LOG.log(Level.WARNING, "Timed out waiting for OpenCode startup migration lock; starting without coordination");
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Could not acquire OpenCode startup migration lock; starting without coordination", e);
        }
        return none(version);
    }

    boolean isLeader() {
        return leader;
    }

    boolean supportsAcpPortFlag() {
        return version != null && (version.equals("1") || version.startsWith("1."));
    }

    void recordSuccessfulStart() {
        if (leader) {
            PREFERENCES.put(versionKey, version);
        }
    }

    @Override
    public void close() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Could not release OpenCode startup migration lock", e);
        }
        try {
            if (channel != null) {
                channel.close();
            }
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Could not close OpenCode startup migration lock file", e);
        }
    }

    static void clearStoredVersionForTests(Path database) {
        PREFERENCES.remove(versionKey(database));
    }

    private static OpenCodeStartupCoordinator none(String version) {
        return new OpenCodeStartupCoordinator(version, null, null, null, false);
    }

    private static boolean hasDatabaseContent(Path database) {
        try {
            return Files.isRegularFile(database) && Files.size(database) > 0L;
        }
        catch (IOException e) {
            return false;
        }
    }

    private static String versionKey(Path database) {
        return "ai.opencode.sharedDatabaseVersion." + Integer.toUnsignedString(
                database.toAbsolutePath().normalize().toString().hashCode(), 36);
    }
}

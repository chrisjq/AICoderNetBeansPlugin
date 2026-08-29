package kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile;

import java.nio.file.Path;

/**
 * A single temp file {@link TempFileRegistry} has created: its path, the owning session id, and the recorded creation
 * time the registry's age sweep measures against (deliberately not filesystem mtime — tests manipulate ages via the
 * registry's {@code maxAgeMillis} override, and some filesystems have coarse timestamp granularity).
 *
 * <p>
 * Equality and hashing are based on {@code path} alone: a given filesystem path is tracked at most once, so adding an
 * equal instance to a session's tracked set is a no-op rather than a duplicate cache entry.
 */
public final class TempFile {

    private final Path path;
    private final String sessionId;
    private final long createdAt;

    TempFile(Path path, String sessionId, long createdAt) {
        this.path = path;
        this.sessionId = sessionId;
        this.createdAt = createdAt;
    }

    /**
     * The file's location on disk. Stable for the lifetime of this object; may already be deleted by the time it is
     * read (the registry deletes best-effort and never resurrects paths).
     */
    public Path path() {
        return path;
    }

    String sessionId() {
        return sessionId;
    }

    long createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TempFile other && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

}

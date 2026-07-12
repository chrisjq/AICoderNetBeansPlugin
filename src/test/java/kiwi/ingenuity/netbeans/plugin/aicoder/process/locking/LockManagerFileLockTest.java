package kiwi.ingenuity.netbeans.plugin.aicoder.process.locking;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class LockManagerFileLockTest {

    // Unique paths per test — LockManager is a process-wide singleton, so tests
    // must not share file paths or they'll see each other's locks.
    private static String uniquePath() {
        return "/tmp/lock-test-" + UUID.randomUUID() + ".txt";
    }

    @Test
    void acquireFileLock_differentFiles_bothSucceedIndependently() {
        LockManager lm = LockManager.getInstance();
        String fileA = uniquePath();
        String fileB = uniquePath();

        assertTrue(lm.acquireFileLock("sessionA", fileA));
        assertTrue(lm.acquireFileLock("sessionB", fileB));

        lm.releaseFileLock("sessionA", fileA);
        lm.releaseFileLock("sessionB", fileB);
    }

    @Test
    void acquireFileLock_sameFileDifferentSession_blockedUntilReleased() {
        LockManager lm = LockManager.getInstance();
        String file = uniquePath();

        assertTrue(lm.acquireFileLock("sessionA", file));
        assertFalse(lm.acquireFileLock("sessionB", file));

        lm.releaseFileLock("sessionA", file);

        assertTrue(lm.acquireFileLock("sessionB", file));
        lm.releaseFileLock("sessionB", file);
    }

    @Test
    void acquireFileLock_sameSessionReacquire_succeeds() {
        LockManager lm = LockManager.getInstance();
        String file = uniquePath();

        assertTrue(lm.acquireFileLock("sessionA", file));
        assertTrue(lm.acquireFileLock("sessionA", file));

        lm.releaseFileLock("sessionA", file);
    }

    @Test
    void getFileLockHolder_reflectsCurrentHolder() {
        LockManager lm = LockManager.getInstance();
        String file = uniquePath();

        assertTrue(lm.acquireFileLock("sessionA", file));
        assertEquals("sessionA", lm.getFileLockHolder(file));

        lm.releaseFileLock("sessionA", file);
        assertNull(lm.getFileLockHolder(file));
    }
}

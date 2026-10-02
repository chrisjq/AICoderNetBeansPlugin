package kiwi.ingenuity.netbeans.plugin.aicoder.process.locking;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class LockManagerFileLockTest {

    // LockManager.getInstance() is a process-wide singleton, and GIT_LOCK/BUILD_LOCK/etc.
    // are GLOBAL locks shared by every test class in this JVM, not just within this file —
    // unique paths per test (below) only protect the FILE-lock tests from each other.
    // A global lock acquired and not released here, or by any other test class running in
    // the same JVM, poisons every test in this file that touches that same lock type.
    private static String uniquePath() {
        return "/tmp/lock-test-" + UUID.randomUUID() + ".txt";
    }

    /**
     * A contended file-lock attempt waits out the full file-lock wait before refusing; shorten it so a test
     * asserting the refusal doesn't spend that whole wait.
     */
    private static boolean tryContendedFileLock(LockManager lm, String sessionId, String file) {
        LockManager.waitTimeoutOverrideMillisForTests = 50L;
        try {
            return lm.acquireFileLock(sessionId, file);
        }
        finally {
            LockManager.waitTimeoutOverrideMillisForTests = null;
        }
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
        assertFalse(tryContendedFileLock(lm, "sessionB", file));

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

    // Regression pin: a nested re-acquire used to overwrite the path mapping with a NEW
    // ResourceLock while the old object stayed in the session's tracking set forever — so
    // bookkeeping (releaseAllLocks, active-lock views) drifted from reality.
    @Test
    void sameSessionReacquire_supersedesCleanly_withoutLeakingTheOldLockObject() {
        LockManager lm = LockManager.getInstance();
        String file = uniquePath();

        assertTrue(lm.acquireFileLock("sessionA", file));
        assertTrue(lm.acquireFileLock("sessionA", file));

        lm.releaseFileLock("sessionA", file);

        assertNull(lm.getFileLockHolder(file), "one release must fully clear the path");
        assertEquals(0, lm.getAllActiveLocks().stream()
                .filter(l -> "sessionA".equals(l.getSessionId())
                             && l.getLockedPaths().contains(file))
                .count(),
                "the superseded lock object must be retired from session tracking");
    }

    /**
     * A partially-superseded MULTI-path lock keeps guarding its remaining paths until they go too.
     */
    @Test
    void multiPathPartialSupersede_oldLockStillGuardsUntouchedPaths() {
        LockManager lm = LockManager.getInstance();
        String f1 = uniquePath();
        String f2 = uniquePath();

        assertTrue(lm.acquireFileLocks("sessionM", Set.of(f1, f2)));
        assertTrue(lm.acquireFileLocks("sessionM", Set.of(f1)));

        lm.releaseFileLock("sessionM", f1);

        assertNull(lm.getFileLockHolder(f1), "superseding lock's path is released");
        assertEquals("sessionM", lm.getFileLockHolder(f2),
                "the original multi-path lock must still guard the untouched path");

        assertTrue(lm.acquireFileLock("sessionB", f1));
        assertFalse(tryContendedFileLock(lm, "sessionB", f2));

        lm.releaseFileLock("sessionB", f1);
        lm.releaseFileLock("sessionM", f2);

        assertTrue(lm.acquireFileLock("sessionB", f2), "nothing may leak after the last release");
        lm.releaseFileLock("sessionB", f2);
    }

    @Test
    void acquireLock_waitsUntilMachineSpeedHolderReleases() throws Exception {
        LockManager lm = LockManager.getInstance();
        assertTrue(lm.acquireLock("holder", LockTypeEnum.GIT_LOCK));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> contender = executor.submit(
                    () -> lm.acquireLock("contender", LockTypeEnum.GIT_LOCK));
            Thread.sleep(100);
            lm.releaseLock("holder", LockTypeEnum.GIT_LOCK);

            try {
                assertTrue(contender.get(2, TimeUnit.SECONDS));
            }
            finally {
                // Unconditional and outside the assertion: if get() above throws
                // (timeout, interrupt, or a failing assertion), the contender task can
                // still go on to acquire GIT_LOCK afterward and never release it —
                // leaking a global lock that would poison every other test in this JVM
                // that touches GIT_LOCK. Releasing here is a no-op if "contender" never
                // actually held it.
                lm.releaseLock("contender", LockTypeEnum.GIT_LOCK);
            }
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void acquireLock_contendedBuildLockFailsAndReportsNamedHolder() {
        // Was acquireLock_zeroWaitReportsNamedHolder — named for a BUILD_LOCK acquisition
        // wait that no longer exists. The timeout consolidation gave BUILD_LOCK a real
        // 120s wait (TimeoutEnum.BUILD_LOCK_WAIT_MILLIS), so this test silently started
        // sleeping out the full two minutes on every run before correctly asserting the
        // same property it always had: a contended global lock fails to acquire and
        // reports its current holder by name. The 120s production wait is deliberate
        // (a build tool waiting for a build slot) and must not change for this test's
        // sake — LockManager.waitTimeoutOverrideMillisForTests exists so this can assert
        // the property in milliseconds instead.
        LockManager lm = LockManager.getInstance();
        LockManager.waitTimeoutOverrideMillisForTests = 50L;
        try {
            assertTrue(lm.acquireLock("build-holder", LockTypeEnum.BUILD_LOCK));
            try {
                assertFalse(lm.acquireLock("contender", LockTypeEnum.BUILD_LOCK));
                assertEquals("build-holder", lm.getLockHolder(LockTypeEnum.BUILD_LOCK));
            }
            finally {
                lm.releaseLock("build-holder", LockTypeEnum.BUILD_LOCK);
            }
        }
        finally {
            LockManager.waitTimeoutOverrideMillisForTests = null;
        }
    }

    /**
     * BigP_1 n1, direction 1: a read must be blocked by a directory lock covering its path, the same way a
     * write already is.
     */
    @Test
    void readUnderAnActiveDirectoryLockIsContended() {
        LockManager lm = LockManager.getInstance();
        String dir = "/tmp/lock-test-dir-" + UUID.randomUUID();
        String file = dir + "/child.txt";

        assertTrue(lm.acquireDirectoryLock("dir-holder", dir));
        try {
            LockManager.waitTimeoutOverrideMillisForTests = 50L;
            try {
                assertNull(lm.acquireFileReadLocks("reader", List.of(file), null),
                        "a read under an active directory lock must be contended, not silently allowed");
            }
            finally {
                LockManager.waitTimeoutOverrideMillisForTests = null;
            }
        }
        finally {
            lm.releaseFileLock("dir-holder", dir);
        }
    }

    /**
     * BigP_1 n1, direction 2: a directory lock must wait out an active read anywhere under it.
     */
    @Test
    void directoryLockIsContendedByAnActiveReadUnderIt() {
        LockManager lm = LockManager.getInstance();
        String dir = "/tmp/lock-test-dir-" + UUID.randomUUID();
        String file = dir + "/child.txt";

        ResourceLock readLock = lm.acquireFileReadLocks("reader", List.of(file), null);
        assertNotNull(readLock, "read must succeed first");
        try {
            LockManager.waitTimeoutOverrideMillisForTests = 50L;
            try {
                assertFalse(lm.acquireDirectoryLock("dir-holder", dir),
                        "a directory lock must wait out an active read under it, not silently proceed");
            }
            finally {
                LockManager.waitTimeoutOverrideMillisForTests = null;
            }
        }
        finally {
            lm.releaseFileReadLock("reader", readLock);
        }
    }

    /**
     * Cosmetic fix: a write refused because readers hold the path used to echo the NORMALISED key back in the
     * message, which can differ from what the caller actually passed (here, a symlink alias resolves to a
     * different real path). The message must show the caller's own spelling.
     */
    @Test
    void writeRefusalByReadersShowsTheCallersOriginalPathNotTheResolvedRealPath() throws Exception {
        Path realFile = Files.createTempFile("lock-test-real-", ".txt");
        Path alias = realFile.resolveSibling(realFile.getFileName() + "-alias");
        CountDownLatch readerEntered = new CountDownLatch(1);
        CountDownLatch readerRelease = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Files.createSymbolicLink(alias, realFile);
            Future<String> reader = executor.submit(() -> McpToolInvoker.withFileRead(
                    "reader", List.of(realFile.toString()), () -> {
                readerEntered.countDown();
                awaitQuietly(readerRelease);
                return "read";
            }));
            assertTrue(readerEntered.await(5, TimeUnit.SECONDS));

            String result;
            LockManager.waitTimeoutOverrideMillisForTests = 50L;
            try {
                result = McpToolInvoker.withFileMutation("writer", List.of(alias.toString()), () -> "should-not-run");
            }
            finally {
                LockManager.waitTimeoutOverrideMillisForTests = null;
            }
            assertTrue(result.contains(alias.toString()),
                    "refusal must show the caller's own path, not the resolved real path: " + result);

            readerRelease.countDown();
            assertEquals("read", reader.get(5, TimeUnit.SECONDS));
        }
        finally {
            readerRelease.countDown();
            executor.shutdownNow();
            Files.deleteIfExists(alias);
            Files.deleteIfExists(realFile);
        }
    }

    /**
     * Cosmetic fix: a directory-lock refusal caused by a reader at a CHILD path used to name a null holder
     * (getFileLockHolder(dirPath) finds nothing at the exact directory key) and fall back to "another
     * in-progress write" — misleading, since nothing is writing. It must say reads were in progress instead,
     * naming the directory the caller asked for.
     */
    @Test
    void directoryMutationRefusalByReadersUnderItNamesReadsNotAPhantomWriter() throws Exception {
        String dir = "/tmp/lock-test-dir-" + UUID.randomUUID();
        String file = dir + "/child.txt";
        CountDownLatch readerEntered = new CountDownLatch(1);
        CountDownLatch readerRelease = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> reader = executor.submit(() -> McpToolInvoker.withFileRead(
                    "reader", List.of(file), () -> {
                readerEntered.countDown();
                awaitQuietly(readerRelease);
                return "read";
            }));
            assertTrue(readerEntered.await(5, TimeUnit.SECONDS));

            String result;
            LockManager.waitTimeoutOverrideMillisForTests = 50L;
            try {
                result = McpToolInvoker.withDirectoryMutation("dir-writer", dir, () -> "should-not-run");
            }
            finally {
                LockManager.waitTimeoutOverrideMillisForTests = null;
            }
            assertTrue(result.contains("reads under") && result.contains(dir),
                    "must say reads were in progress, naming the directory: " + result);
            assertFalse(result.contains("in-progress write"),
                    "must not fall back to the misleading write message: " + result);

            readerRelease.countDown();
            assertEquals("read", reader.get(5, TimeUnit.SECONDS));
        }
        finally {
            readerRelease.countDown();
            executor.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test release");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for test release", e);
        }
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

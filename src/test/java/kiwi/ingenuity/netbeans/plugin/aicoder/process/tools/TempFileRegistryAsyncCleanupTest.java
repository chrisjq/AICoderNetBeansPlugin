package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TempFileRegistry#cleanupSessionAsync(String)} — the variant hooked into
 * {@code AiTopComponent.componentClosed}, as opposed to the synchronous {@link
 * TempFileRegistry#cleanupSession(String)} used by permanent session deletion. Kept in its own file rather than added
 * to {@code TempFileRegistryTest} to avoid touching a file with concurrent in-progress work elsewhere.
 */
class TempFileRegistryAsyncCleanupTest {

    private static AiSession session(String id) {
        return new AiSession(id, "T", null, AiTypeEnum.CLAUDE, null, null,
                Instant.now(), Instant.now());
    }

    private static boolean await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return cond.getAsBoolean();
    }

    @TempDir
    Path root; // stands in for ~/.ai-coder

    @BeforeEach
    void isolate() {
        TempFileRegistry.resetForTests();
        TempFileRegistry.overrideBasePath = root;
    }

    @AfterEach
    void tearDown() {
        TempFileRegistry.resetForTests();
    }

    private Path tmpDirOf(String sessionId) {
        return root.resolve(sessionId).resolve(TempFileRegistry.TEMP_DIR_NAME);
    }

    @Test
    void cleanupSessionAsync_eventuallyRemovesTheDirectory() throws Exception {
        AiSession s = session("ses-a");
        TempFile file = TempFileRegistry.createTempFile(s, "ai-coder-paste", ".png");
        Path tmpDir = file.path().getParent();
        assertTrue(Files.exists(tmpDir));

        TempFileRegistry.cleanupSessionAsync("ses-a");
        assertTrue(await(() -> !Files.exists(tmpDir)), "temp dir was not removed asynchronously");
    }

    @Test
    void cleanupSessionAsync_doesNotBlockTheCallingThread() throws Exception {
        AiSession s = session("ses-a");
        TempFileRegistry.createTempFile(s, "ai-coder-paste", ".png");
        Path tmpDir = TempFileRegistry.getSessionTempDir("ses-a");

        long start = System.nanoTime();
        TempFileRegistry.cleanupSessionAsync("ses-a");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // The call itself must return near-instantly — the recursive delete is the whole
        // point of moving it off-thread. A generous bound (this is a single small temp
        // dir) so the assertion is about "did not do the I/O here", not a tight race.
        assertTrue(elapsedMillis < 500, "cleanupSessionAsync must return before the delete completes, took " + elapsedMillis + "ms");
        assertTrue(await(() -> !Files.exists(tmpDir)), "temp dir must still be removed eventually");
    }

    @Test
    void cleanupSession_doesNotMisattributeByPathCoincidence() throws Exception {
        // Regression pin: ownership used to be inferred by checking whether a directory's
        // PARENT path segment equalled the sessionId string, so an unrelated directory
        // whose parent happened to be named the same as a real session id could be swept
        // by that session's cleanup even though nothing ever attributed it there.
        // Ownership is now recorded explicitly at creation time (and unattributed content
        // can never enter the registry at all), so this must not happen even when the
        // coincidence is deliberately constructed here.
        AiSession s = session("ses-a");
        TempFile realFile = TempFileRegistry.createTempFile(s, "ai-coder-paste", ".png");
        Path realTmpDir = realFile.path().getParent();

        // An unrelated tree whose parent segment coincidentally matches the session id,
        // created WITHOUT the registry (as any real-world stray would be).
        Path coincidentalDir = root.resolve("unrelated-tree").resolve("ses-a")
                .resolve(TempFileRegistry.TEMP_DIR_NAME);
        Files.createDirectories(coincidentalDir);
        Path stray = coincidentalDir.resolve("unrelated.log");
        Files.writeString(stray, "not ours");

        TempFileRegistry.cleanupSessionAsync("ses-a");

        assertTrue(await(() -> !Files.exists(realTmpDir)), "session A's real tmp dir must still be removed");
        assertTrue(Files.exists(coincidentalDir),
                "an unattributed dir must never be swept by an unrelated session's cleanup, "
                + "even when its parent segment happens to match the session id");
        assertTrue(Files.exists(stray));
    }

    @Test
    void cleanupSessionAsync_onlyRemovesTheNamedSessionsDirectory() throws Exception {
        AiSession sessionA = session("ses-a");
        AiSession sessionB = session("ses-b");
        TempFile fileA = TempFileRegistry.createTempFile(sessionA, "ai-coder-paste", ".png");
        TempFile fileB = TempFileRegistry.createTempFile(sessionB, "ai-coder-paste", ".png");

        TempFileRegistry.cleanupSessionAsync("ses-a");

        assertTrue(await(() -> !Files.exists(fileA.path().getParent())), "session A's temp dir must be removed");
        assertTrue(Files.exists(fileB.path()), "session B's temp file must be untouched by session A's cleanup");
        assertTrue(Files.notExists(tmpDirOf("ses-a")));
        assertTrue(Files.exists(tmpDirOf("ses-b")));
    }

    @Test
    void cleanupSessionAsync_unknownSession_isANoOp() {
        // No owned directory is registered for this session id — must not throw.
        TempFileRegistry.cleanupSessionAsync("never-had-any-temp-files");
    }
}

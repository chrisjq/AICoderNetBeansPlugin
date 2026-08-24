package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Set;
import java.util.function.BooleanSupplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lifecycle of the plugin's temp files: in-memory creation-time cache, age sweep driven by that cache, wholesale
 * removal per session and at shutdown, lazy sweeper thread (McpServerRegistry pattern), best-effort deletion, and the
 * structural guarantee that nothing outside registry-created directories — and nothing not born through
 * {@code createTempFile} — is ever swept. Runs entirely against {@link TempFileRegistry#overrideBasePath}, so no live
 * MCP server is involved.
 */
class TempFileRegistryTest {

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
        // Short but non-zero: exercises real waiting without flaky races.
        TempFileRegistry.maxAgeMillis = () -> 80L;
        TempFileRegistry.sweepIntervalMillis = () -> 25L;
    }

    @AfterEach
    void tearDown() {
        TempFileRegistry.resetForTests();
    }

    private Path tmpDirOf(String sessionId) {
        return root.resolve(sessionId).resolve(TempFileRegistry.TEMP_DIR_NAME);
    }

    @Test
    void createTempFile_createsCachedFileInsideSessionTmp_withSessionIdInName()
            throws IOException {
        AiSession s = session("ses-a");
        TempFile f = TempFileRegistry.createTempFile(s, "ai-coder-paste", ".png");
        assertNotNull(f);
        assertEquals(tmpDirOf("ses-a"), f.path().getParent());
        assertTrue(f.path().getFileName().toString().startsWith("ai-coder-paste-"));
        assertTrue(f.path().getFileName().toString().endsWith(".png"));
        assertTrue(Files.isRegularFile(f.path()));
        assertEquals(1, TempFileRegistry.trackedFileCount());
        assertEquals(1, TempFileRegistry.ownedDirCount());
    }

    @Test
    void createTempFile_nullOrBlankSession_returnsNull() {
        assertNull(TempFileRegistry.createTempFile((AiSession) null, "x", ".png"));
        assertNull(TempFileRegistry.createTempFile(session("  "), "x", ".png"));
        assertNull(TempFileRegistry.createTempFile("ses-a", null, ".png"));
        assertNull(TempFileRegistry.createTempFile("ses-a", "  ", ".png"));
        assertEquals(0, TempFileRegistry.trackedFileCount());

        // A one/two-character NAME is not an error: the embedded -<session>- suffix
        // lifts every generated prefix past File.createTempFile's three-character floor.
        TempFile shortName = TempFileRegistry.createTempFile("ses-a", "ab", ".png");
        assertNotNull(shortName);
        assertTrue(shortName.path().getFileName().toString().startsWith("ab-ses-a-"));
    }

    @Test
    void ageSweep_deletesExpiredFiles_basedOnRecordedCreationTime() throws Exception {
        TempFile f = TempFileRegistry.createTempFile(session("ses-a"), "paste", ".png");
        assertNotNull(f);
        // The file's own mtime is irrelevant to the decision: the sweep acts on
        // the cache entry recorded at creation time. Either way it must go.
        assertTrue(await(() -> !Files.exists(f.path())), "expired file was not swept");
        assertTrue(await(() -> TempFileRegistry.trackedFileCount() == 0),
                "cache did not drain after sweep");
    }

    @Test
    void sweep_touchesOnlyCachedFiles_wholesaleTouchesOnlyOwnedDirs() throws Exception {
        TempFile minted = TempFileRegistry.createTempFile(session("ses-a"), "paste", ".png");
        assertNotNull(minted);
        Path tmpDir = tmpDirOf("ses-a");

        // A file sitting INSIDE the owned dir that the registry never created:
        // the age sweep must leave it alone (the cache is the sole truth).
        Path foreignInTmp = tmpDir.resolve("someone-elses.txt");
        Files.writeString(foreignInTmp, "not ours");
        // And one outside tmp entirely, in the session's config dir proper.
        Path siblingOutsideTmp = root.resolve("ses-a/memory.json");
        Files.writeString(siblingOutsideTmp, "{}");

        assertTrue(await(() -> !Files.exists(minted.path())), "created file not swept");

        assertTrue(Files.exists(foreignInTmp), "age sweep deleted an uncached file");
        assertTrue(Files.exists(siblingOutsideTmp));

        // Wholesale cleanup removes the whole owned dir (including the stray)
        // but never reaches outside it.
        TempFileRegistry.cleanupAll();
        assertTrue(Files.notExists(tmpDir));
        assertFalse(Files.exists(foreignInTmp));
        assertTrue(Files.exists(siblingOutsideTmp), "cleanupAll escaped the owned dir!");
    }

    @Test
    void cleanupSession_removesWholeTmpDir_onlyForThatSession() throws Exception {
        TempFile a = TempFileRegistry.createTempFile(session("ses-a"), "paste", ".png");
        TempFile b = TempFileRegistry.createTempFile(session("ses-b"), "paste", ".png");
        assertNotNull(a);
        assertNotNull(b);
        Path bOtherData = root.resolve("ses-b/history.json");
        Files.writeString(bOtherData, "[]");

        TempFileRegistry.cleanupSession("ses-b");

        assertTrue(Files.notExists(tmpDirOf("ses-b")), "session b tmp dir not removed");
        assertTrue(Files.exists(bOtherData), "session b data outside tmp was removed");
        assertTrue(Files.exists(a.path()), "session a file was removed by b's cleanup");
        assertTrue(Files.exists(tmpDirOf("ses-a")));
        assertEquals(1, TempFileRegistry.ownedDirCount(), "cache still holds only a's root");

        // Cache now holds only a's file: once it ages out the thread has
        // nothing left and shuts down.
        assertTrue(await(() -> !TempFileRegistry.sweeperRunning()));
    }

    @Test
    void cleanupSession_stillRemovesDir_evenWhenAgeSweepDrainedItFirst() throws Exception {
        // Leak regression pin: the age sweep removes individual FILES but must never
        // untrack the session's tmp ROOT. If it did, a session closing after its last
        // temp file expired would find no registered directory and leak it forever.
        TempFile c = TempFileRegistry.createTempFile(session("ses-c"), "paste", ".png");
        assertNotNull(c);
        assertTrue(await(() -> TempFileRegistry.trackedFileCount() == 0), "caches did not drain");
        assertTrue(Files.exists(tmpDirOf("ses-c")), "sweep must never delete directories");
        assertEquals(1, TempFileRegistry.ownedDirCount(), "root must stay tracked after a full sweep");

        TempFileRegistry.cleanupSession("ses-c");
        assertTrue(Files.notExists(tmpDirOf("ses-c")), "dir leaked: cleanup found no mapping after sweep");
        assertEquals(0, TempFileRegistry.ownedDirCount());
    }

    @Test
    void cleanupAll_removesEverything_andStopsSweeper() throws Exception {
        TempFileRegistry.createTempFile(session("ses-a"), "paste", ".png");
        TempFileRegistry.createTempFile(session("ses-b"), "paste", ".png");
        TempFileRegistry.cleanupAll();
        assertTrue(Files.notExists(tmpDirOf("ses-a")));
        assertTrue(Files.notExists(tmpDirOf("ses-b")));
        assertEquals(0, TempFileRegistry.trackedFileCount());
        assertEquals(0, TempFileRegistry.ownedDirCount());
        assertTrue(await(() -> !TempFileRegistry.sweeperRunning()));
    }

    @Test
    void undeletableFile_isSkipped_andDoesNotStopOtherDeletions() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(root)
                .supportsFileAttributeView(PosixFileAttributeView.class));

        // Both files are born through the registry (the only way into the
        // cache): one in a directory we will make undeletable, one elsewhere.
        TempFile stuck = TempFileRegistry.createTempFile(session("ses-stuck"), "locked", ".log");
        TempFile deletable = TempFileRegistry.createTempFile(session("ses-other"), "removable", ".log");
        assertNotNull(stuck);
        assertNotNull(deletable);

        // No write permission on the parent directory => unlink fails for the
        // locked file. maxAge 0 makes both entries immediately expired.
        Path stuckParent = stuck.path().getParent();
        TempFileRegistry.maxAgeMillis = () -> 0L;
        Files.setPosixFilePermissions(stuckParent,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            assertTrue(await(() -> TempFileRegistry.trackedFileCount() == 0),
                    "sweep did not process both entries");
            assertTrue(Files.exists(stuck.path()), "undeletable file vanished?!");
            assertFalse(Files.exists(deletable.path()),
                    "the stuck file stopped the deletion of unrelated files");
        }
        finally {
            Files.setPosixFilePermissions(stuckParent, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void createTempFile_inExplicitSubDirectory_becomesCachedAndAgedOut() throws Exception {
        Path dir = tmpDirOf("ses-x").resolve("tool_results");
        TempFile spooled = TempFileRegistry.createTempFile("ses-x", "tool_results", "maven", ".log");

        assertNotNull(spooled);
        assertEquals(dir, spooled.path().getParent());
        assertTrue(spooled.path().getFileName().toString().startsWith("maven-"));
        assertTrue(Files.isRegularFile(spooled.path()));
        assertEquals(1, TempFileRegistry.trackedFileCount());

        // ToolResultSpooler-style: the caller only fills in content afterwards.
        Files.writeString(spooled.path(), "log text");
        assertTrue(await(() -> !Files.exists(spooled.path())), "cached file was not aged out");
    }

    @Test
    void sweeper_startsOnFirstCreation_stopsWhenEmpty_restartsAfresh() throws Exception {
        assertFalse(TempFileRegistry.sweeperRunning(), "thread running with nothing cached");

        TempFile f = TempFileRegistry.createTempFile(session("ses-a"), "paste", ".png");
        assertNotNull(f);
        assertTrue(await(TempFileRegistry::sweeperRunning), "first creation did not start the thread");

        TempFileRegistry.cleanupAll();
        assertTrue(await(() -> !TempFileRegistry.sweeperRunning()),
                "thread kept running after the cache emptied");

        TempFile f2 = TempFileRegistry.createTempFile(session("ses-a"), "paste", ".png");
        assertNotNull(f2);
        assertTrue(await(TempFileRegistry::sweeperRunning),
                "a new creation did not restart the thread");
    }
}

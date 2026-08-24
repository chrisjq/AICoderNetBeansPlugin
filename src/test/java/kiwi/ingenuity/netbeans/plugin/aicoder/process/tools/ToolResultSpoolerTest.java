package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spooling behaviour of {@link ToolResultSpooler}: content round-trip, collision-safe naming, subdirectory creation,
 * and hand-off of every written file to {@link TempFileRegistry}'s creation-time cache (which owns the lifetime — the
 * old prune-to-20 rule is gone). Runs against {@link TempFileRegistry#overrideBasePath}, so no live MCP server is
 * involved.
 */
class ToolResultSpoolerTest {

    @TempDir
    Path root;

    @BeforeEach
    void resetRegistryState() {
        // spool hands every written file to the registry's global cache;
        // isolate tests from each other without touching any real path.
        TempFileRegistry.resetForTests();
        TempFileRegistry.overrideBasePath = root;
    }

    @AfterEach
    void tearDown() {
        TempFileRegistry.resetForTests();
    }

    @Test
    void spool_writesContent_andReturnsReadablePath() throws IOException {
        Path file = ToolResultSpooler.spool("ses-a", "maven", "the complete output");
        assertNotNull(file);
        assertTrue(Files.isRegularFile(file));
        assertEquals("the complete output", Files.readString(file));
        String name = file.getFileName().toString();
        assertTrue(name.startsWith("maven-"));
        assertTrue(name.endsWith(".log"));
    }

    @Test
    void spool_createsMissingSubdirectory() throws IOException {
        Path file = ToolResultSpooler.spool("ses-a", "gradle", "x");
        assertNotNull(file);
        // tool_results lives INSIDE the session's registry-owned tmp tree.
        assertEquals(root.resolve("ses-a").resolve(TempFileRegistry.TEMP_DIR_NAME)
                .resolve(ToolResultSpooler.DIR_NAME), file.getParent());
        assertEquals("x", Files.readString(file));
    }

    @Test
    void spool_neverOverwrites_rapidCallsAllSurvive() throws IOException {
        Set<String> contents = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            Path file = ToolResultSpooler.spool("ses-a", "maven", "run " + i);
            assertNotNull(file);
            contents.add(Files.readString(file));
        }
        // Even when several land in the same millisecond, every write gets its
        // own collision-suffixed file rather than clobbering an earlier one.
        assertEquals(25, contents.size());
        for (int i = 0; i < 25; i++) {
            assertTrue(contents.contains("run " + i));
        }
    }

    @Test
    void spool_registersWrittenFileInCreationTimeCache() throws IOException {
        Path file = ToolResultSpooler.spool("ses-a", "ant", "out");
        assertNotNull(file);
        assertEquals(1, TempFileRegistry.trackedFileCount());
        // And the registry treats it as one of its own for wholesale cleanup:
        TempFileRegistry.cleanupAll();
        assertTrue(Files.notExists(file));
    }

    @Test
    void spool_failureReturnsNull_neverThrows() throws IOException {
        assertNull(ToolResultSpooler.spool(null, "maven", "out"));
        assertNull(ToolResultSpooler.spool("", "maven", "out"));
        assertEquals(0, TempFileRegistry.trackedFileCount());

        // A very short tool tag is fine: the registry's -<session>- suffix keeps the
        // generated filename prefix above File.createTempFile's three-character floor.
        Path shortTag = ToolResultSpooler.spool("ses-a", "ls", "out");
        assertNotNull(shortTag);
        assertTrue(shortTag.getFileName().toString().startsWith("ls-ses-a-"));
        assertEquals("out", Files.readString(shortTag));
    }
}

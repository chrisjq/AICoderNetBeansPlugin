package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.extension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PiExtensionFilesTest {

    private String sessionA;
    private String sessionB;

    @AfterEach
    void cleanup() {
        if (sessionA != null) {
            PiExtensionFiles.delete(sessionA);
        }
        if (sessionB != null) {
            PiExtensionFiles.delete(sessionB);
        }
    }

    private static Path plant(String sessionId) throws IOException {
        Path path = PiExtensionFiles.pathFor(sessionId);
        Files.writeString(path, "// test extension file for " + sessionId, StandardCharsets.UTF_8);
        return path;
    }

    @Test
    void pathFor_isUnderTheSharedExtensionsDirectory() throws IOException {
        sessionA = "path-test-" + UUID.randomUUID();
        Path path = PiExtensionFiles.pathFor(sessionA);
        assertEquals(PiExtensionFiles.directory(), path.getParent());
        assertTrue(path.getFileName().toString().contains(sessionA));
    }

    @Test
    void delete_removesOnlyItsOwnSessionsFile() throws IOException {
        sessionA = "delete-a-" + UUID.randomUUID();
        sessionB = "delete-b-" + UUID.randomUUID();
        Path pathA = plant(sessionA);
        Path pathB = plant(sessionB);

        PiExtensionFiles.delete(sessionA);

        assertFalse(Files.exists(pathA), "session A's file must be gone");
        assertTrue(Files.exists(pathB), "session B's file must be untouched");
    }

    @Test
    void delete_missingFileIsANoOp() {
        sessionA = "delete-missing-" + UUID.randomUUID();
        PiExtensionFiles.delete(sessionA); // must not throw
    }

    @Test
    void deleteAll_removesEveryFileInTheDirectory() throws IOException {
        sessionA = "delete-all-a-" + UUID.randomUUID();
        sessionB = "delete-all-b-" + UUID.randomUUID();
        Path pathA = plant(sessionA);
        Path pathB = plant(sessionB);

        PiExtensionFiles.deleteAll();

        assertFalse(Files.exists(pathA));
        assertFalse(Files.exists(pathB));
    }

    @Test
    void sweepAtStartup_removesLeftoverFiles() throws IOException {
        sessionA = "sweep-" + UUID.randomUUID();
        Path path = plant(sessionA);

        PiExtensionFiles.sweepAtStartup();

        assertFalse(Files.exists(path));
    }

    @Test
    void twoSessionsGetTwoIndependentFiles() throws IOException {
        sessionA = "independent-a-" + UUID.randomUUID();
        sessionB = "independent-b-" + UUID.randomUUID();
        Path pathA = plant(sessionA);
        Path pathB = plant(sessionB);

        assertTrue(Files.exists(pathA));
        assertTrue(Files.exists(pathB));
        assertFalse(pathA.equals(pathB));
    }

    @Test
    void deleteAll_leavesForeignFilesAndSubdirectoriesAlone() throws IOException {
        sessionA = "deleteall-foreign-" + UUID.randomUUID();
        Path ours = plant(sessionA);
        Path foreignFile = PiExtensionFiles.directory().resolve("not-ours-" + UUID.randomUUID() + ".txt");
        Path foreignDir = PiExtensionFiles.directory().resolve("not-ours-dir-" + UUID.randomUUID());
        Files.writeString(foreignFile, "leave me alone", StandardCharsets.UTF_8);
        Files.createDirectory(foreignDir);
        try {
            PiExtensionFiles.deleteAll();

            assertFalse(Files.exists(ours), "our own file must be deleted");
            assertTrue(Files.exists(foreignFile), "a foreign file must survive deleteAll");
            assertTrue(Files.exists(foreignDir), "a subdirectory must survive deleteAll");
        }
        finally {
            Files.deleteIfExists(foreignFile);
            Files.deleteIfExists(foreignDir);
        }
    }

    @Test
    void sweepAtStartup_leavesForeignFilesAndSubdirectoriesAlone() throws IOException {
        sessionA = "sweep-foreign-" + UUID.randomUUID();
        Path ours = plant(sessionA);
        Path foreignFile = PiExtensionFiles.directory().resolve("not-ours-" + UUID.randomUUID() + ".txt");
        Path foreignDir = PiExtensionFiles.directory().resolve("not-ours-dir-" + UUID.randomUUID());
        Files.writeString(foreignFile, "leave me alone", StandardCharsets.UTF_8);
        Files.createDirectory(foreignDir);
        try {
            PiExtensionFiles.sweepAtStartup();

            assertFalse(Files.exists(ours), "our own file must be deleted");
            assertTrue(Files.exists(foreignFile), "a foreign file must survive sweepAtStartup");
            assertTrue(Files.exists(foreignDir), "a subdirectory must survive sweepAtStartup");
        }
        finally {
            Files.deleteIfExists(foreignFile);
            Files.deleteIfExists(foreignDir);
        }
    }

    @Test
    void deleteAll_alsoRemovesOurOwnLeftoverAtomicWriteTempFile() throws IOException {
        sessionA = "deleteall-tmp-" + UUID.randomUUID();
        Path target = PiExtensionFiles.pathFor(sessionA);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, "leftover atomic-write temp file", StandardCharsets.UTF_8);

        PiExtensionFiles.deleteAll();

        assertFalse(Files.exists(tmp), "our own leftover .tmp file must be deleted too");
    }
}

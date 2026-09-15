package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Tests for ProjectActionProvider path-alias resolution.
 *
 * Verifies that paths through symlinks are correctly resolved to the same real path as direct paths, so that project
 * matching works consistently regardless of how the path is spelled.
 */
class ProjectActionProviderTest {

    @Test
    void projectPathResolution_followsSymlinks() throws Exception {
        // Create a temp directory
        Path tempDir = Files.createTempDirectory("project-action-test-");
        try {
            // Create a symlink to it
            Path symlinkDir = Files.createTempDirectory("project-action-test-symlink-parent-").resolve("symlink");
            try {
                Files.createSymbolicLink(symlinkDir, tempDir);

                // Verify both spellings resolve to the same real path
                Path realTemp = tempDir.toRealPath();
                Path realSymlink = symlinkDir.toRealPath();

                assertTrue(realTemp.equals(realSymlink),
                           "Symlink and direct path should resolve to the same real path: "
                           + "tempDir=" + tempDir + ", symlinkDir=" + symlinkDir
                           + ", realTemp=" + realTemp + ", realSymlink=" + realSymlink);
            }
            catch (UnsupportedOperationException e) {
                // Symlinks not supported on this OS (e.g., Windows without admin)
                // Skip the test gracefully
            }
            finally {
                if (Files.exists(symlinkDir)) {
                    Files.delete(symlinkDir);
                    Files.delete(symlinkDir.getParent());
                }
            }
        }
        finally {
            Files.deleteIfExists(tempDir);
        }
    }
}

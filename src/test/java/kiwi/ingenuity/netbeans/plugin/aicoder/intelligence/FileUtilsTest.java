package kiwi.ingenuity.netbeans.plugin.aicoder.intelligence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.FileUtils;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void toRealPathResolvesSymlinkAndHandlesMissingPath() throws IOException {
        assertNotNull(FileUtils.toRealPath(tempDir.resolve("missing").toFile()));
        Path target = Files.createDirectory(tempDir.resolve("target"));
        Path alias = tempDir.resolve("alias");
        try {
            Files.createSymbolicLink(alias, target);
        }
        catch (UnsupportedOperationException | SecurityException e) {
            return;
        }
        catch (IOException e) {
            // Windows and restricted CI environments may refuse symlink creation.
            return;
        }
        assertEquals(FileUtils.toRealPath(target.toFile()), FileUtils.toRealPath(alias.toFile()));
        assertEquals(
                FileUtils.toRealPath(target.resolve("new.txt").toFile()),
                FileUtils.toRealPath(alias.resolve("new.txt").toFile()));
    }

    @Test
    void toIdePathFallsBackToNormalizedInputWhenNoProjectMatches() {
        Path input = tempDir.resolve("nested").resolve("..").resolve("outside.txt");
        assertEquals(input.toFile().getAbsoluteFile().toPath().normalize().toString(), FileUtils.toIdePath(input.toString()));
        assertNotNull(FileUtils.toIdePath((String) null));
    }

    @Test
    void resolveByPathReturnsNullForNull() {
        assertNull(FileUtils.resolveByPath(null));
    }

    @Test
    void resolveByPathReturnsNullForBlank() {
        assertNull(FileUtils.resolveByPath("   "));
    }

    @Test
    void resolveByPathReturnsNullForMissingFile() {
        assertNull(FileUtils.resolveByPath("/nonexistent/path/to/Foo.java"));
    }

    @Test
    void locateSourceFileReturnsNullForUnknownClass() {
        assertNull(FileUtils.locateSourceFile("com.example.DoesNotExist"));
    }
}

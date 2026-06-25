package kiwi.ingenuity.netbeans.plugin.aicoder.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeExecutableLocator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeExecutableLocatorTest {

    @Test
    void findInCandidates_findsExistingExecutable(@TempDir Path dir) throws IOException {
        Path exe = dir.resolve("claude");
        Files.writeString(exe, "#!/bin/sh\necho claude 1.0");
        exe.toFile().setExecutable(true);

        String found = ClaudeExecutableLocator.findInCandidates(new String[]{
            dir.resolve("nothere").toString(),
            exe.toString()
        });

        assertEquals(exe.toString(), found);
    }

    @Test
    void findInCandidates_returnsNullWhenNoneFound() {
        String found = ClaudeExecutableLocator.findInCandidates(new String[]{
            "/definitely/does/not/exist/claude"
        });
        assertNull(found);
    }

    @Test
    void isExecutableFile_returnsTrueForExecutable(@TempDir Path dir) throws IOException {
        Path exe = dir.resolve("claude");
        Files.writeString(exe, "#!/bin/sh");
        exe.toFile().setExecutable(true);
        assertTrue(ClaudeExecutableLocator.isExecutableFile(exe.toString()));
    }

    @Test
    void isExecutableFile_returnsFalseForDirectory(@TempDir Path dir) {
        assertFalse(ClaudeExecutableLocator.isExecutableFile(dir.toString()));
    }

    @Test
    void isExecutableFile_returnsFalseForMissing() {
        assertFalse(ClaudeExecutableLocator.isExecutableFile("/no/such/path/claude"));
    }
}

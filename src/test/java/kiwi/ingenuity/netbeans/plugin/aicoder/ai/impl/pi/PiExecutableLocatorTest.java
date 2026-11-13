package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PiExecutableLocatorTest {

    @Test
    void managedLauncherIsFoundAndTrustedOnPath(@TempDir Path home) throws IOException {
        String originalHome = System.getProperty("user.home");
        String originalOs = System.getProperty("os.name");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("os.name", "Linux");
            Path executable = home.resolve(".pi/agent/bin/pi");
            Files.createDirectories(executable.getParent());
            Files.writeString(executable, "#!/bin/sh\n");
            assertTrue(executable.toFile().setExecutable(true));
            String[] candidates = PiExecutableLocator.platformCandidates(null, null);
            assertEquals(executable.toString(), candidates[0]);
            assertEquals(executable.toString(), PiExecutableLocator.findInCandidates(candidates));
            assertEquals(executable.toString(),
                         PiExecutableLocator.findOnPath(executable.getParent().toString()));
        }
        finally {
            System.setProperty("user.home", originalHome);
            System.setProperty("os.name", originalOs);
        }
    }

    @Test
    void agentDirectoryOverridePrecedesDefaultAndBlankIsIgnored(@TempDir Path home) throws IOException {
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            Path override = home.resolve("custom-agent");
            Path executable = override.resolve("bin/pi");
            Files.createDirectories(executable.getParent());
            Files.writeString(executable, "#!/bin/sh\n");
            assertTrue(executable.toFile().setExecutable(true));
            String[] candidates = PiExecutableLocator.platformCandidates(override.toString(), null);
            assertEquals(executable.toString(), candidates[0]);
            assertEquals(home + "/.pi/agent/bin/pi", candidates[1]);
            assertEquals(executable.toString(), PiExecutableLocator.findInCandidates(candidates));
            assertEquals(home + "/.pi/agent/bin/pi",
                         PiExecutableLocator.platformCandidates("  ", null)[0]);
        }
        finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void windowsManagedCandidatesIncludeCmdAndExe() {
        String originalOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            String profile = "C:/Users/example";
            String[] candidates = PiExecutableLocator.platformCandidates(null, profile);
            assertTrue(Arrays.asList(candidates).contains(profile + "\\.pi\\agent\\bin\\pi.cmd"));
            assertTrue(Arrays.asList(candidates).contains(profile + "\\.pi\\agent\\bin\\pi.exe"));
        }
        finally {
            System.setProperty("os.name", originalOs);
        }
    }

    @Test
    void findInCandidates_findsExistingExecutable(@TempDir Path dir) throws IOException {
        Path exe = dir.resolve("pi");
        Files.writeString(exe, "#!/bin/sh\necho 0.85.1");
        exe.toFile().setExecutable(true);
        String found = PiExecutableLocator.findInCandidates(new String[]{
            dir.resolve("nothere").toString(), exe.toString()
        });
        assertEquals(exe.toString(), found);
    }

    @Test
    void findInCandidates_returnsNullWhenNoneFound() {
        String found = PiExecutableLocator.findInCandidates(new String[]{"/definitely/does/not/exist/pi"});
        assertNull(found);
    }

    @Test
    void isExecutableFile_returnsTrueForExecutable(@TempDir Path dir) throws IOException {
        Path exe = dir.resolve("pi");
        Files.writeString(exe, "#!/bin/sh");
        exe.toFile().setExecutable(true);
        assertTrue(PiExecutableLocator.isExecutableFile(exe.toString()));
    }

    @Test
    void isExecutableFile_returnsFalseForDirectory(@TempDir Path dir) {
        assertFalse(PiExecutableLocator.isExecutableFile(dir.toString()));
    }

    @Test
    void isExecutableFile_returnsFalseForMissing() {
        assertFalse(PiExecutableLocator.isExecutableFile("/no/such/path/pi"));
    }

    @Test
    void testExecutable_returnsTrimmedStdoutOnSuccess(@TempDir Path dir) throws Exception {
        Path exe = dir.resolve("pi");
        Files.writeString(exe, "#!/bin/sh\necho 0.85.1\n");
        exe.toFile().setExecutable(true);
        String output = PiExecutableLocator.testExecutable(exe.toString());
        assertEquals("0.85.1", output);
    }

    @Test
    void testExecutable_throwsOnNonZeroExit(@TempDir Path dir) throws Exception {
        Path exe = dir.resolve("pi");
        Files.writeString(exe, "#!/bin/sh\necho boom 1>&2\nexit 1\n");
        exe.toFile().setExecutable(true);
        org.junit.jupiter.api.function.Executable call = () -> PiExecutableLocator.testExecutable(exe.toString());
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, call);
    }

    @Test
    void testExecutable_processKeepsStdoutOpenWithoutExiting_timesOutRatherThanHanging(@TempDir Path dir) throws Exception {
        // Writes some output immediately (so a naive readNBytes-before-waitFor drain would block on this, since
        // readNBytes only returns at EOF) then sleeps far longer than the shortened test budget, holding stdout
        // open the whole time instead of exiting — reproducing the hang this fix targets.
        Path exe = dir.resolve("pi");
        Files.writeString(exe, "#!/bin/sh\necho 0.85.1\nsleep 30\n");
        exe.toFile().setExecutable(true);
        PiExecutableLocator.testExecutableBudgetMillisForTests = 300L;
        try {
            long start = System.nanoTime();
            org.junit.jupiter.api.function.Executable call = () -> PiExecutableLocator.testExecutable(exe.toString());
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class, call);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(elapsedMs < 5000, "must time out against the shortened test budget, not hang: took " + elapsedMs + " ms");
        }
        finally {
            PiExecutableLocator.testExecutableBudgetMillisForTests = null;
        }
    }
}

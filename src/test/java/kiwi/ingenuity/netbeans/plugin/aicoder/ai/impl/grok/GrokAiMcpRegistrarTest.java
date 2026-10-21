package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrokAiMcpRegistrarTest {

    @TempDir
    Path tempDir;

    @Test
    void removeCleansLegacyGlobalMcpEntry() throws Exception {
        Path marker = tempDir.resolve("invocation.txt");
        Path executable = writeExecutable("grok.sh", "#!/bin/sh\nprintf '%s\\n' \"$*\" > \"" + marker + "\"\n");
        GrokAiMcpRegistrar registrar = new GrokAiMcpRegistrar("session-1", executable.toString());

        registrar.removeMcpEndpoint();

        assertTrue(Files.exists(marker), "removeMcpEndpoint must invoke Grok cleanup");
        String args = Files.readString(marker, StandardCharsets.UTF_8);
        assertTrue(args.contains("mcp remove"), args);
        assertTrue(args.contains("aicoder-nb-ki-plugin"), args);
    }

    @Test
    void removeIgnoresLegacyCleanupFailure() throws Exception {
        Path executable = writeExecutable("failing-grok.sh", "#!/bin/sh\nexit 1\n");
        GrokAiMcpRegistrar registrar = new GrokAiMcpRegistrar("session-1", executable.toString());

        assertDoesNotThrow(registrar::removeMcpEndpoint);
    }

    private Path writeExecutable(String name, String content) throws Exception {
        Path executable = tempDir.resolve(name);
        Files.writeString(executable, content, StandardCharsets.UTF_8);
        assertTrue(executable.toFile().setExecutable(true), "test executable must be runnable");
        return executable;
    }
}

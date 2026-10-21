package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeAiMcpRegistrarTest {

    @TempDir
    Path tempDir;

    @Test
    void removeCleansLegacyGlobalEntry() throws Exception {
        Path marker = tempDir.resolve("invocation.txt");
        Path executable = tempDir.resolve("claude.sh");
        Files.writeString(executable,
                          "#!/bin/sh\nprintf '%s\\n' \"$*\" > \"" + marker + "\"\n",
                          StandardCharsets.UTF_8);
        assertTrue(executable.toFile().setExecutable(true), "test executable must be runnable");

        ClaudeAiMcpRegistrar registrar = new ClaudeAiMcpRegistrar("session-1", executable.toString());

        assertFalse(Files.exists(marker), "nothing may run before removeMcpEndpoint");

        registrar.removeMcpEndpoint();
        assertTrue(Files.exists(marker), "removeMcpEndpoint must still invoke Claude cleanup");
        String args = Files.readString(marker, StandardCharsets.UTF_8);
        assertTrue(args.contains("mcp remove"), args);
        assertTrue(args.contains("aicoder-nb-ki-plugin"), args);
    }
}

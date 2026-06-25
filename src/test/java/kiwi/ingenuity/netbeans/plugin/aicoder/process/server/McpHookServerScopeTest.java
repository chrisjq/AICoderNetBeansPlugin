package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpHookServerScopeTest {

    @Test
    void updateSessionScope_widensAllowedProjectDirs(@TempDir Path tmp) throws Exception {
        Path dirA = Files.createDirectory(tmp.resolve("a"));
        Path dirB = Files.createDirectory(tmp.resolve("b"));
        Path fileB = Files.createFile(dirB.resolve("Foo.java"));

        McpHookServer s = new McpHookServer(0);
        try {
            s.registerSession("c1", "claude", List.of(dirA.toFile()), true);
            // Project B not in the start-time scope → rejected.
            assertFalse(s.isFileAllowed("c1", fileB.toString()));
            // Refresh the scope to include a newly-opened project B.
            s.updateSessionScope("c1", List.of(dirA.toFile(), dirB.toFile()), true);
            assertTrue(s.isFileAllowed("c1", fileB.toString()));
        }
        finally {
            s.stop();
        }
    }

    @Test
    void updateSessionScope_unknownSession_isNoop() throws Exception {
        McpHookServer s = new McpHookServer(0);
        try {
            s.updateSessionScope("never-registered", List.of(), true); // must not throw
        }
        finally {
            s.stop();
        }
    }
}

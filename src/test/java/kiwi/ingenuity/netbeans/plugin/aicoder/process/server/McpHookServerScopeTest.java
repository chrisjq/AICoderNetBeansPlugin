package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
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
        s.init();
        try {
            s.registerSession("c1", CLAUDE, List.of(dirA.toFile()), true);
            // Project B not in the start-time scope → rejected.
            assertFalse(s.isFileAllowed("c1", fileB.toString()));
            // Refresh the scope to include a newly-opened project B.
            s.updateSessionScope("c1", CLAUDE, List.of(dirA.toFile(), dirB.toFile()), true);
            assertTrue(s.isFileAllowed("c1", fileB.toString()));
        }
        finally {
            s.stop();
        }
    }

    @Test
    void updateSessionScope_unknownSession_registersSession() throws Exception {
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.updateSessionScope("never-registered", CLAUDE, List.of(), true); // must not throw
        }
        finally {
            s.stop();
        }
    }

    @Test
    void isFileAllowed_restrictOnEmptyDirs_denies(@TempDir Path tmp) throws Exception {
        Path file = Files.createFile(tmp.resolve("Secret.java"));
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession("c1", CLAUDE, List.of(), true);
            assertFalse(s.isFileAllowed("c1", file.toString()));
        }
        finally {
            s.stop();
        }
    }

    @Test
    void isFileAllowed_restrictOff_allowsOutsideProjects(@TempDir Path tmp) throws Exception {
        Path file = Files.createFile(tmp.resolve("Anywhere.java"));
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession("c1", CLAUDE, List.of(), false);
            assertTrue(s.isFileAllowed("c1", file.toString()));
        }
        finally {
            s.stop();
        }
    }
}

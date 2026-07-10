package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the resume-session-lifecycle bug found in review:
 * {@code grok -s <sessionId>} (create) is rejected by the real CLI with
 * {@code Error: Session ID <id> is already in use.} if that id already has an
 * on-disk session (empirically confirmed against a live installed grok CLI).
 * {@code GrokAiImplementation.afterStart()} now consults
 * {@link #isStoredSessionValid} (delegating to
 * {@link GrokUsageSignalsReader#sessionExists}) before deciding whether to
 * treat a start as "new" or "resume" — mirroring
 * {@code ClaudeAiImplementation}'s established pattern for the same failure
 * mode. This locks in the {@code isStoredSessionValid} half of that fix.
 */
class GrokAiImplementationTest {

    private String originalUserHome;

    @TempDir
    Path tempHome;

    @BeforeEach
    void redirectUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void isStoredSessionValid_noOnDiskSession_returnsFalse() {
        GrokAiImplementation impl = new GrokAiImplementation(noopListener());
        assertFalse(impl.isStoredSessionValid(UUID.randomUUID().toString()));
    }

    @Test
    void isStoredSessionValid_existingOnDiskSession_returnsTrue() throws IOException {
        String sessionId = UUID.randomUUID().toString();
        Files.createDirectories(tempHome.resolve(".grok").resolve("sessions")
                .resolve("%2Fsome%2Fencoded%2Fcwd").resolve(sessionId));
        GrokAiImplementation impl = new GrokAiImplementation(noopListener());
        assertTrue(impl.isStoredSessionValid(sessionId));
    }

    private static kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener noopListener() {
        return (AiProcessEvent event) -> {
        };
    }
}

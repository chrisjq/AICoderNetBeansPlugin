package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private static kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener noopListener() {
        return (AiProcessEvent event) -> {
        };
    }

    private static ExecutablePrompter noopPrompter() {
        return (dialogTitle, executableName) -> CompletableFuture.completedFuture(null);
    }

    private static GrokAiImplementation implFor(AiSession session) {
        return new GrokAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };
    }

    private static AiSession newSession(String id, GrokSessionSettings settings) {
        return new AiSession(id, "Test", null, AiTypeEnum.GROK, null, settings, Instant.now(), Instant.now());
    }

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
        GrokAiImplementation impl = new GrokAiImplementation(noopListener(), noopPrompter());
        assertFalse(impl.isStoredSessionValid(UUID.randomUUID().toString()));
    }

    @Test
    void isStoredSessionValid_existingOnDiskSession_returnsTrue() throws IOException {
        String sessionId = UUID.randomUUID().toString();
        Files.createDirectories(tempHome.resolve(".grok").resolve("sessions")
                .resolve("%2Fsome%2Fencoded%2Fcwd").resolve(sessionId));
        GrokAiImplementation impl = new GrokAiImplementation(noopListener(), noopPrompter());
        assertTrue(impl.isStoredSessionValid(sessionId));
    }

    @Test
    void setModel_updatesSessionSettings() {
        GrokSessionSettings settings = new GrokSessionSettings();
        GrokAiImplementation impl = implFor(newSession("grok-setmodel-1", settings));

        impl.setModel("grok-4");

        assertEquals("grok-4", settings.model(), "setModel must update the session settings");
    }

    @Test
    void setModel_doesNotChangeGrokPluginSettingsGlobalDefault() {
        String globalBefore = GrokPluginSettings.getModel();
        GrokSessionSettings settings = new GrokSessionSettings();
        GrokAiImplementation impl = implFor(newSession("grok-setmodel-2", settings));

        impl.setModel("grok-3");

        assertEquals(globalBefore, GrokPluginSettings.getModel(),
                "setModel must NOT write the global plugin default");
    }
}

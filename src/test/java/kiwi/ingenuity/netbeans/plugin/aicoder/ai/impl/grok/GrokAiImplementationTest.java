package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for the resume-session-lifecycle bug found in review: {@code grok -s <sessionId>} (create) is
 * rejected by the real CLI with {@code Error: Session ID <id> is already in use.} if that id already has an on-disk
 * session (empirically confirmed against a live installed grok CLI). {@code GrokAiImplementation.afterStart()} now
 * consults {@link #isStoredSessionValid} (delegating to {@link GrokUsageSignalsReader#sessionExists}) before deciding
 * whether to treat a start as "new" or "resume" — mirroring {@code ClaudeAiImplementation}'s established pattern for
 * the same failure mode. This locks in the {@code isStoredSessionValid} half of that fix.
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

    private static AiSessionHost stubHost(AiSessionSettings settings, AtomicReference<AiSessionSettings> updated) {
        return new AiSessionHost() {
            @Override
            public File resolveWorkDir() {
                return null;
            }

            @Override
            public void suppressNextTurn(String statusMessage, String completionMessage) {
            }

            @Override
            public AiSessionSettings getSessionSettings() {
                return settings;
            }

            @Override
            public void updateSessionSettings(AiSessionSettings newSettings) {
                updated.set(newSettings);
            }
        };
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

    // ---- afterStart(): effective reasoning effort (session wins over global default) ----
    @Test
    void afterStart_appliesTheSessionsOwnReasoningEffort() {
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setModel("grok-4.6");
        settings.setReasoningEffort("high");
        GrokAiImplementation impl = implFor(newSession("grok-afterstart-1", settings));

        impl.afterStart();

        assertEquals(List.of("--reasoning-effort", "high"),
                     impl.delegate().buildReasoningEffortArgs("grok-4.6"),
                     "the session's own reasoning effort must win over any global default");
    }

    @Test
    void afterStart_fallsBackToTheGlobalReasoningEffortWhenTheSessionHasNone() {
        String globalBefore = GrokPluginSettings.getReasoningEffort();
        try {
            GrokPluginSettings.setReasoningEffort("medium");
            GrokSessionSettings settings = new GrokSessionSettings();
            settings.setModel("grok-4.5");
            GrokAiImplementation impl = implFor(newSession("grok-afterstart-2", settings));

            impl.afterStart();

            assertEquals(List.of("--reasoning-effort", "medium"),
                         impl.delegate().buildReasoningEffortArgs("grok-4.5"));
        }
        finally {
            GrokPluginSettings.setReasoningEffort(globalBefore);
        }
    }

    // ---- review finding 1: the manager clearing an unsupported value must also clear the PERSISTED source, not
    // just its own in-memory field, or the same INFO event recurs forever across restarts ----
    @Test
    void unsupportedReasoningEffortClearedAtSendTimeAlsoClearsThePersistedSessionSetting() {
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setModel("grok-4.5");
        settings.setReasoningEffort("xhigh");
        GrokAiImplementation impl = implFor(newSession("grok-clear-1", settings));
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        impl.onStarted(stubHost(settings, updated));

        impl.delegate().configureReasoningEffort("xhigh", true);
        impl.delegate().buildReasoningEffortArgs("grok-4.5");

        assertNull(settings.reasoningEffort(),
                   "a stored-unsupported value must end up null in the persisted session settings, not just the "
                   + "in-memory field");
        assertEquals(settings, updated.get(), "the cleared settings must actually be persisted through the host");
    }

    @Test
    void clearInvalidPersistedReasoningEffort_clearsSessionScopedValue() {
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setReasoningEffort("xhigh");
        GrokAiImplementation impl = implFor(newSession("grok-clear-2", settings));
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        impl.onStarted(stubHost(settings, updated));

        impl.clearInvalidPersistedReasoningEffort();

        assertNull(settings.reasoningEffort());
        assertEquals(settings, updated.get());
    }

    // ---- spec §1 rule 3a (added after review found Grok and Copilot disagreeing here): only a SESSION-pinned
    // value is ever cleared automatically. The global default belongs to the user and to every other
    // session/backend, so one session's model rejecting it must never touch the global default. ----
    @Test
    void clearInvalidPersistedReasoningEffort_neverTouchesTheGlobalDefaultWhenSessionHasNoOverride() {
        String globalBefore = GrokPluginSettings.getReasoningEffort();
        GrokPluginSettings.setReasoningEffort("xhigh");
        try {
            GrokSessionSettings settings = new GrokSessionSettings();
            GrokAiImplementation impl = implFor(newSession("grok-clear-3", settings));

            impl.clearInvalidPersistedReasoningEffort();

            assertEquals("xhigh", GrokPluginSettings.getReasoningEffort(),
                         "with no session-level override, the global default must be left completely untouched — "
                         + "this callback only ever fires for the session-sourced case in the first place");
        }
        finally {
            GrokPluginSettings.setReasoningEffort(globalBefore);
        }
    }

    // ---- coverage gap the reviewer found: no test previously set session AND global to DIFFERENT non-blank
    // values at once, which is exactly what would have caught the wrong-scope bug rule 3a fixes ----
    @Test
    void sessionValueClearedButGlobalDefaultWithADifferentValueIsLeftUntouched() {
        String globalBefore = GrokPluginSettings.getReasoningEffort();
        GrokPluginSettings.setReasoningEffort("medium");
        try {
            GrokSessionSettings settings = new GrokSessionSettings();
            settings.setModel("grok-4.5");
            // xhigh is grok-4.6-only, so it is unsupported on this session's grok-4.5 model — but it is the
            // session's OWN value, so per rule 3a it wins over "medium" and is the one that must be cleared.
            settings.setReasoningEffort("xhigh");
            GrokAiImplementation impl = implFor(newSession("grok-clear-4", settings));
            AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
            impl.onStarted(stubHost(settings, updated));

            impl.afterStart();
            impl.delegate().buildReasoningEffortArgs("grok-4.5");

            assertNull(settings.reasoningEffort(), "the session's own unsupported value must be cleared");
            assertEquals("medium", GrokPluginSettings.getReasoningEffort(),
                         "the global default must be left completely untouched, even though it is also set — "
                         + "spec §1 rule 3a");
        }
        finally {
            GrokPluginSettings.setReasoningEffort(globalBefore);
        }
    }
}

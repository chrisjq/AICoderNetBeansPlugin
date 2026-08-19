package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import java.io.File;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComboBox;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.ui.CodexAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

// Mirrors OpenCodeAiImplementationTest — same bug class the design doc explicitly
// warns Slice 4 to avoid: a session's chosen model must reach settings.model()
// and be used at startup, not silently fall back to the global default.
class CodexAiImplementationTest {

    private static AiSession newSession(String id, CodexSessionSettings settings) {
        return new AiSession(id, "Test", null, AiTypeEnum.CODEX, null, settings, Instant.now(), Instant.now());
    }

    // ---- createInfoBarExtension: must seed from the SESSION's model, not the
    // global default — the exact trap this project already hit once for OpenCode ----
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

    @Test
    void setModel_updatesSessionSettings() {
        CodexSessionSettings settings = new CodexSessionSettings();
        AiSession session = newSession("s-setmodel-1", settings);

        CodexAiImplementation impl = new CodexAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };

        impl.setModel("gpt-5.6-luna");

        assertEquals("gpt-5.6-luna", settings.model(), "setModel must update the session settings");
    }

    @Test
    void setModel_doesNotChangeCodexPluginSettingsGlobalDefault() {
        String globalBefore = CodexPluginSettings.getModel();

        CodexSessionSettings settings = new CodexSessionSettings();
        AiSession session = newSession("s-setmodel-2", settings);

        CodexAiImplementation impl = new CodexAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };

        impl.setModel("gpt-5.5");

        assertEquals(globalBefore, CodexPluginSettings.getModel(), "setModel must NOT write the global plugin default");
    }

    @Test
    void setModel_withNoCurrentSession_doesNotThrow() {
        CodexAiImplementation impl = new CodexAiImplementation(e -> {
        }, null);
        assertDoesNotThrow(() -> impl.setModel("gpt-5.6-terra"));
    }

    // ---- resolveStartupModel: the exact bug design doc §9 warns against repeating ----
    // (Tests the extracted helper directly rather than startWithDiscovery() itself, since
    // that method's other branch depends on CodexExecutableLocator.locate() finding a real
    // executable on disk — environment-dependent and not something a unit test should assume.)
    @Test
    void resolveStartupModel_usesSessionModelNotGlobalDefault() {
        CodexSessionSettings settings = new CodexSessionSettings();
        settings.setModel("gpt-5.6-luna");
        AiSession session = newSession("s-swd-1", settings);

        CodexAiImplementation impl = new CodexAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };

        assertEquals("gpt-5.6-luna", impl.resolveStartupModel(null),
                "startWithDiscovery(null) must fall back to the session's chosen model, "
                + "not CodexPluginSettings.getModel() — this is the per-session-model bug");
    }

    @Test
    void resolveStartupModel_explicitArgumentWinsOverSessionModel() {
        CodexSessionSettings settings = new CodexSessionSettings();
        settings.setModel("gpt-5.6-luna");
        AiSession session = newSession("s-swd-2", settings);

        CodexAiImplementation impl = new CodexAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };

        assertEquals("gpt-5.5", impl.resolveStartupModel("gpt-5.5"),
                "an explicit model argument must still win over the session setting");
    }

    @Test
    void resolveStartupModel_noSessionModelFallsBackToGlobalDefault() {
        CodexSessionSettings settings = new CodexSessionSettings();
        // settings.setModel(...) never called.
        AiSession session = newSession("s-swd-3", settings);

        CodexAiImplementation impl = new CodexAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };

        assertEquals(CodexPluginSettings.getModel(), impl.resolveStartupModel(null),
                "with no session model and no explicit argument, the global default is still correct");
    }

    @Test
    void resolveStartupModel_blankSessionModelFallsBackToGlobalDefault() {
        CodexSessionSettings settings = new CodexSessionSettings();
        settings.setModel("   ");
        AiSession session = newSession("s-swd-4", settings);

        CodexAiImplementation impl = new CodexAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };

        assertEquals(CodexPluginSettings.getModel(), impl.resolveStartupModel(null),
                "a blank (not null) session model must not be treated as a real choice");
    }

    // ---- resumeSession: must use the stored Codex thread id, never the plugin's own UUID ----
    @Test
    void resumeSession_withStoredThreadId_usesThreadIdNotPluginUuid() {
        String threadId = "01a01885-5fba-7932-a9bc-da38712890b6";
        String pluginUuid = "e6523570-b545-4136-ac6b-3bd9d7fce668";

        CodexSessionSettings settings = new CodexSessionSettings();
        settings.setThreadId(threadId);
        AiSession session = newSession("s-resume-1", settings);

        var impl = new CodexAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            CodexAiProcessManager exposedDelegate() {
                return delegate();
            }
        };

        impl.resumeSession(pluginUuid);

        assertEquals(threadId, impl.exposedDelegate().pendingResumeThreadId,
                "resumeSession(pluginUUID) must use the stored thread id, not the plugin UUID");
    }

    @Test
    void resumeSession_withNoStoredThreadId_doesNotAttemptResume() {
        String pluginUuid = "e6523570-b545-4136-ac6b-3bd9d7fce668";

        CodexSessionSettings settings = new CodexSessionSettings();
        AiSession session = newSession("s-resume-2", settings);

        var impl = new CodexAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            CodexAiProcessManager exposedDelegate() {
                return delegate();
            }
        };

        impl.resumeSession(pluginUuid);

        assertNull(impl.exposedDelegate().pendingResumeThreadId,
                "resumeSession must not set pendingResumeThreadId when no thread id is stored");
    }

    @Test
    void afterStartThenResumeSessionWithPluginUuid_pendingResumeIdIsThreadId() {
        // REGRESSION GUARD, mirroring OpenCode's: AiTopComponent ordering is
        // startAiProcess() -> afterStart() (reads the stored thread id) then
        // loadHistory() -> resumeSession(pluginUUID). The plugin UUID must not
        // overwrite the thread id afterStart() already queued.
        String threadId = "01a01885-5fba-7932-a9bc-da38712890b6";
        String pluginUuid = "e6523570-b545-4136-ac6b-3bd9d7fce668";

        CodexSessionSettings settings = new CodexSessionSettings();
        settings.setThreadId(threadId);
        AiSession session = newSession("s-resume-3", settings);

        var impl = new CodexAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            CodexAiProcessManager exposedDelegate() {
                return delegate();
            }
        };

        // Simulate startAiProcess() -> afterStart() setting the stored thread id.
        impl.start("non-existent-codex-executable", "model");
        assertEquals(threadId, impl.exposedDelegate().pendingResumeThreadId,
                "after afterStart(), pendingResumeThreadId must be the stored thread id");

        // Simulate AiTopComponent.loadHistory() calling resumeSession with the plugin UUID.
        impl.resumeSession(pluginUuid);

        assertEquals(threadId, impl.exposedDelegate().pendingResumeThreadId,
                "resumeSession(pluginUUID) must NOT overwrite the thread id set by afterStart()");
    }

    @Test
    void createInfoBarExtension_seedsFromSessionModelNotGlobalDefault() {
        CodexSessionSettings settings = new CodexSessionSettings();
        settings.setModel("gpt-5.6-luna");
        AiSession session = newSession("s-infobar-1", settings);
        CodexAiImplementation impl = new CodexAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };

        AiInfoBarExtension ext = impl.createInfoBarExtension(session, stubHost(settings, new AtomicReference<>()));

        assertTrue(ext instanceof CodexAiInfoBarExtension);
        assertEquals("gpt-5.6-luna", ((CodexAiInfoBarExtension) ext).getSelectedModel(),
                "must seed from the session's own model, not CodexPluginSettings.getModel()");
    }

    @Test
    void createInfoBarExtension_modelChangeListenerPersistsToSessionSettingsAndHost() {
        CodexSessionSettings settings = new CodexSessionSettings();
        settings.setModel("gpt-5.6-terra");
        AiSession session = newSession("s-infobar-2", settings);
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        CodexAiImplementation impl = new CodexAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };

        AiInfoBarExtension ext = impl.createInfoBarExtension(session, stubHost(settings, updated));
        CodexAiInfoBarExtension codexExt = (CodexAiInfoBarExtension) ext;

        // Simulate the user picking a different model in the combo — NOT via
        // setSelectedModel(), which deliberately does not fire the listener.
        JComboBox<?> combo = (JComboBox<?>) codexExt.createComponents().get(0);
        combo.setSelectedItem("gpt-5.5");

        assertEquals("gpt-5.5", settings.model(),
                "a user-initiated selection must persist to the session settings via setModel()");
        assertSame(settings, updated.get(), "host.updateSessionSettings() must be called so the change is saved");
    }

    @Test
    void createInfoBarExtension_modelChangeListenerHandlesNullHostAndSession() {
        CodexAiImplementation impl = new CodexAiImplementation(e -> {
        }, null);
        // currentSession deliberately left unset — mirrors createInfoBarExtension being
        // called with a session object that never became this impl's currentSession.
        AiInfoBarExtension ext = impl.createInfoBarExtension(null, null);
        CodexAiInfoBarExtension codexExt = (CodexAiInfoBarExtension) ext;

        JComboBox<?> combo = (JComboBox<?>) codexExt.createComponents().get(0);
        assertDoesNotThrow(() -> combo.setSelectedItem("gpt-5.5"));
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

// Addendum 2 + Correction: setModel must be session-scoped, not global.
class OpenCodeAiImplementationTest {

    private static JsonArray configOptionsWithModel(String modelValue) {
        JsonObject opt = new JsonObject();
        opt.addProperty("id", "model");
        opt.addProperty("type", "select");
        opt.addProperty("currentValue", modelValue);
        JsonArray cfgOpts = new JsonArray();
        cfgOpts.add(opt);
        return cfgOpts;
    }

    @Test
    void setModel_updatesSessionSettings() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        AiSession session = new AiSession("s-setmodel-1", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        OpenCodeAiProcessManager fakeManager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return false;
            }

            @Override
            public JsonArray configOptions() {
                return null;
            }
        };

        OpenCodeAiImplementation impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            @Override
            protected OpenCodeAiProcessManager delegate() {
                return fakeManager;
            }
        };

        impl.setModel("opencode/other-model");

        assertEquals("opencode/other-model", settings.model(),
                "setModel must update the session settings");
    }

    @Test
    void setModel_doesNotChangeOpenCodePluginSettingsGlobalDefault() {
        String globalBefore = OpenCodePluginSettings.getModel();

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        AiSession session = new AiSession("s-setmodel-2", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        OpenCodeAiProcessManager fakeManager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return false;
            }

            @Override
            public JsonArray configOptions() {
                return null;
            }
        };

        OpenCodeAiImplementation impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            @Override
            protected OpenCodeAiProcessManager delegate() {
                return fakeManager;
            }
        };

        impl.setModel("opencode/new-model");

        assertEquals(globalBefore, OpenCodePluginSettings.getModel(),
                "setModel must NOT write the global plugin default");
    }

    @Test
    void setModel_withLiveSession_differentFromCurrent_callsSetConfigOption() {
        List<String[]> calls = new ArrayList<>();

        JsonArray cfgOpts = configOptionsWithModel("opencode/big-pickle");

        OpenCodeAiProcessManager fakeManager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return true;
            }

            @Override
            public JsonArray configOptions() {
                return cfgOpts;
            }

            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        AiSession session = new AiSession("s-setmodel-3", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        OpenCodeAiImplementation impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            @Override
            protected OpenCodeAiProcessManager delegate() {
                return fakeManager;
            }
        };

        impl.setModel("opencode/other-model");

        assertEquals(1, calls.size(), "setConfigOption must be called when new model differs from current");
        assertEquals("model", calls.get(0)[0]);
        assertEquals("opencode/other-model", calls.get(0)[1]);
    }

    @Test
    void setModel_withLiveSession_sameAsCurrent_skipsSetConfigOption() {
        List<String[]> calls = new ArrayList<>();

        JsonArray cfgOpts = configOptionsWithModel("opencode/big-pickle");

        OpenCodeAiProcessManager fakeManager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return true;
            }

            @Override
            public JsonArray configOptions() {
                return cfgOpts;
            }

            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        AiSession session = new AiSession("s-setmodel-4", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        OpenCodeAiImplementation impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            @Override
            protected OpenCodeAiProcessManager delegate() {
                return fakeManager;
            }
        };

        impl.setModel("opencode/big-pickle");  // same as current

        assertTrue(calls.isEmpty(), "setConfigOption must NOT be called when model is same as current");
    }

    @Test
    void setModel_withNoLiveSession_updatesSettingsAndDoesNotThrow() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        AiSession session = new AiSession("s-setmodel-5", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        OpenCodeAiProcessManager fakeManager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return false;
            }

            @Override
            public JsonArray configOptions() {
                return null;
            }
        };

        OpenCodeAiImplementation impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            @Override
            protected OpenCodeAiProcessManager delegate() {
                return fakeManager;
            }
        };

        assertDoesNotThrow(() -> impl.setModel("opencode/any-model"),
                "setModel with no live session must not throw");
        assertEquals("opencode/any-model", settings.model(),
                "settings must still be updated even with no live session");
    }

    // ---- resolveStartupModel must use the session's chosen model, not the global default ----
    // (Tests the extracted helper directly rather than startWithDiscovery() itself, since that
    // method's other branch depends on OpenCodeExecutableLocator.locate() finding a real
    // executable on disk — environment-dependent and not something a unit test should assume.)
    @Test
    void resolveStartupModel_usesSessionModelNotGlobalDefault() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setModel("opencode/deepseek-v4-flash-free");
        AiSession session = new AiSession("s-swd-1", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        OpenCodeAiImplementation impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };

        assertEquals("opencode/deepseek-v4-flash-free", impl.resolveStartupModel(null),
                "startWithDiscovery(null) must fall back to the session's chosen model, "
                + "not OpenCodePluginSettings.getModel() — this is the per-session-model bug");
    }

    @Test
    void resolveStartupModel_explicitArgumentWinsOverSessionModel() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setModel("opencode/deepseek-v4-flash-free");
        AiSession session = new AiSession("s-swd-2", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        OpenCodeAiImplementation impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };

        assertEquals("opencode/explicit-override", impl.resolveStartupModel("opencode/explicit-override"),
                "an explicit model argument must still win over the session setting");
    }

    @Test
    void resolveStartupModel_noSessionModelFallsBackToGlobalDefault() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        // settings.setModel(...) never called.
        AiSession session = new AiSession("s-swd-3", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        OpenCodeAiImplementation impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };

        assertEquals(OpenCodePluginSettings.getModel(), impl.resolveStartupModel(null),
                "with no session model and no explicit argument, the global default is still correct");
    }

    @Test
    void resumeSession_withStoredAcpId_usesAcpIdNotPluginUuid() {
        String acpId = "ses_abc123";
        String pluginUuid = "e6523570-b545-4136-ac6b-3bd9d7fce668";

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setAcpSessionId(acpId);
        AiSession session = new AiSession("s-resume-1", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        var impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            OpenCodeAiProcessManager exposedDelegate() {
                return delegate();
            }
        };

        impl.resumeSession(pluginUuid);

        assertEquals(acpId, impl.exposedDelegate().pendingAcpResumeId,
                "resumeSession(pluginUUID) must use the stored ACP id, not the plugin UUID");
    }

    @Test
    void resumeSession_withNoStoredAcpId_doesNotAttemptResume() {
        String pluginUuid = "e6523570-b545-4136-ac6b-3bd9d7fce668";

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        AiSession session = new AiSession("s-resume-2", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        var impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            OpenCodeAiProcessManager exposedDelegate() {
                return delegate();
            }
        };

        impl.resumeSession(pluginUuid);

        assertNull(impl.exposedDelegate().pendingAcpResumeId,
                "resumeSession must not set pendingAcpResumeId when no ACP id is stored");
    }

    @Test
    void afterStartThenResumeSessionWithPluginUuid_pendingResumeIdIsAcpId() {
        // REGRESSION GUARD: AiTopComponent ordering is startAiProcess() -> afterStart() (sets ses_... id)
        // then loadHistory() -> resumeSession(pluginUUID). The plugin UUID must NOT overwrite the ACP id.
        String acpId = "ses_ff4f7e79dffeO55jvs3BjVUU88";
        String pluginUuid = "e6523570-b545-4136-ac6b-3bd9d7fce668";

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setAcpSessionId(acpId);
        AiSession session = new AiSession("s-resume-3", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        var impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            OpenCodeAiProcessManager exposedDelegate() {
                return delegate();
            }
        };

        // Simulate startAiProcess() -> afterStart() setting the ACP id
        impl.start("non-existent-opencode-executable", "model");
        assertEquals(acpId, impl.exposedDelegate().pendingAcpResumeId,
                "after afterStart(), pendingAcpResumeId must be the stored ACP id");

        // Simulate AiTopComponent.loadHistory() calling resumeSession with the plugin UUID —
        // this is the bug path. Before the fix, this line overwrites pendingAcpResumeId.
        impl.resumeSession(pluginUuid);

        assertEquals(acpId, impl.exposedDelegate().pendingAcpResumeId,
                "resumeSession(pluginUUID) must NOT overwrite the ACP id set by afterStart()");
    }
}

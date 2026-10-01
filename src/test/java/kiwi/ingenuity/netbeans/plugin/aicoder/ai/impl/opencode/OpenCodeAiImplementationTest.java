package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui.OpenCodeAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

// setModel must be session-scoped, not global.
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

    private static JsonObject selectOption(String id, String current, String... values) {
        JsonObject option = new JsonObject();
        option.addProperty("id", id);
        option.addProperty("type", "select");
        option.addProperty("currentValue", current);
        JsonArray choices = new JsonArray();
        for (String value : values) {
            JsonObject choice = new JsonObject();
            choice.addProperty("value", value);
            choice.addProperty("name", value);
            choices.add(choice);
        }
        option.add("options", choices);
        return option;
    }

    private static JsonArray configOptions(String model, String mode, String effort) {
        JsonArray options = new JsonArray();
        options.add(selectOption("model", model, "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta"));
        options.add(selectOption("mode", mode, "build", "plan"));
        options.add(selectOption("effort", effort, "low", "high"));
        return options;
    }

    private static AiSession sessionWith(OpenCodeSessionSettings settings, String id) {
        return new AiSession(id, "Test", null, AiTypeEnum.OPENCODE, null, settings,
                Instant.now(), Instant.now());
    }

    private static AiSessionHost hostFor(AtomicReference<AiSessionSettings> updated) {
        return new AiSessionHost() {
            @Override
            public File resolveWorkDir() {
                return null;
            }

            @Override
            public AiSessionSettings getSessionSettings() {
                return updated.get();
            }

            @Override
            public void updateSessionSettings(AiSessionSettings newSettings) {
                updated.set(newSettings);
            }
        };
    }

    private static JComboBox<?> comboAt(JPanel panel, int index) {
        return (JComboBox<?>) panel.getComponent(index);
    }

    private record BarHarness(OpenCodeAiInfoBarExtension bar, JPanel panel) {

    }

    private static BarHarness createBar(OpenCodeAiImplementation implementation, AiSession session,
                                        AiSessionHost host) throws Exception {
        implementation.onStarted(host);
        AtomicReference<BarHarness> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            OpenCodeAiInfoBarExtension bar
                                       = (OpenCodeAiInfoBarExtension) implementation.createInfoBarExtension(session, host);
            JPanel panel = (JPanel) bar.createComponents().get(0);
            result.set(new BarHarness(bar, panel));
        });
        return result.get();
    }

    private static final class RecordingManager extends OpenCodeAiProcessManager {

        private final boolean live;
        private JsonArray options;
        private CompletableFuture<JsonArray> nextResponse;
        private int compactCalls;
        private final List<String[]> calls = new ArrayList<>();

        RecordingManager(boolean live, JsonArray options) {
            super(e -> {
            });
            this.live = live;
            this.options = options;
        }

        @Override
        public synchronized boolean isSessionLive() {
            return live;
        }

        @Override
        public synchronized JsonArray configOptions() {
            return options;
        }

        @Override
        public synchronized CompletableFuture<JsonArray> setConfigOption(String id, String value) {
            calls.add(new String[]{id, value});
            return nextResponse != null
                   ? nextResponse
                   : CompletableFuture.completedFuture(options);
        }

        @Override
        public synchronized void compact() {
            compactCalls++;
        }
    }

    @Test
    void createInfoBarExtensionCapturesHostBeforeStartupForIdlePick() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setModel("alpha");
        AiSession session = sessionWith(settings, "impl-early-pick");
        RecordingManager manager = new RecordingManager(false, configOptions("alpha", "build", "low"));
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        OpenCodeAiImplementation implementation = implementationUsing(manager, session);
        AiSessionHost host = hostFor(updated);
        AtomicReference<BarHarness> harness = new AtomicReference<>();

        // Production installs the bar before asynchronous startup invokes onStarted().
        SwingUtilities.invokeAndWait(() -> {
            OpenCodeAiInfoBarExtension bar
                                       = (OpenCodeAiInfoBarExtension) implementation.createInfoBarExtension(session, host);
            JPanel panel = (JPanel) bar.createComponents().get(0);
            harness.set(new BarHarness(bar, panel));
        });
        SwingUtilities.invokeAndWait(() -> comboAt(harness.get().panel(), 0).setSelectedItem("beta"));

        assertEquals("beta", settings.model());
        assertSame(settings, updated.get(),
                "an idle pick made before onStarted must still be persisted through the captured host");
    }

    @Test
    void comboChangeWithNoLiveSessionUpdatesSettingsAndMakesNoRpc() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setModel("alpha");
        AiSession session = sessionWith(settings, "impl-idle-model");
        RecordingManager manager = new RecordingManager(false, configOptions("alpha", "build", "low"));
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        OpenCodeAiImplementation implementation = implementationUsing(manager, session);
        BarHarness harness = createBar(implementation, session, hostFor(updated));

        SwingUtilities.invokeAndWait(() -> comboAt(harness.panel(), 0).setSelectedItem("beta"));

        assertEquals("beta", settings.model());
        assertTrue(manager.calls.isEmpty(), "idle selection must not call the ACP RPC");
        assertSame(settings, updated.get());
    }

    @Test
    void configOptionsEventWithSevenModelsPopulatesTheRealModelCombo() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        AiSession session = sessionWith(settings, "impl-seven-models");
        JsonArray options = configOptions("alpha", "build", "low");
        RecordingManager manager = new RecordingManager(false, options);
        OpenCodeAiImplementation implementation = implementationUsing(manager, session);
        BarHarness harness = createBar(implementation, session, hostFor(new AtomicReference<>()));

        assertEquals(7, comboAt(harness.panel(), 0).getItemCount());
        assertEquals("alpha", comboAt(harness.panel(), 0).getSelectedItem());
    }

    @Test
    void catalogIsPopulatedFromConfigOptionsEvent() {
        List<String> models = List.of("cat-a", "cat-b", "cat-c");
        OpenCodeAiImplementation.modelCatalog().publish(models);
        assertEquals(models, OpenCodeAiImplementation.modelCatalog().getCachedModels());
    }

    @Test
    void propertyBusReceivesAvailableModelsEventThroughCatalog() {
        OpenCodeAiImplementation.modelCatalog().invalidate();
        assertTrue(OpenCodeAiImplementation.modelCatalog().beginRefresh(0));
        assertTrue(OpenCodeAiImplementation.modelCatalog().publish(List.of("event-a", "event-b")));
        assertEquals(List.of("event-a", "event-b"),
                OpenCodeAiImplementation.modelCatalog().getCachedModels());
    }

    @Test
    void discoveredModelsSurviveInMemoryResetAndSeedIdleBar() throws Exception {
        OpenCodePluginSettings.setDiscoveredModels(new String[]{"persisted-a", "persisted-b"});
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setModel("persisted-b");
        AiSession session = sessionWith(settings, "impl-discovered");
        RecordingManager manager = new RecordingManager(false, null);
        OpenCodeAiImplementation implementation = implementationUsing(manager, session);
        BarHarness harness = createBar(implementation, session, hostFor(new AtomicReference<>()));

        assertEquals("persisted-b", comboAt(harness.panel(), 0).getSelectedItem());
        assertEquals("persisted-a", comboAt(harness.panel(), 0).getItemAt(0));
    }

    @Test
    void unrelatedSessionsHandshakeBroadcastDoesNotResetOtherIdleSessionsModel() throws Exception {
        OpenCodeSessionSettings firstSettings = new OpenCodeSessionSettings();
        firstSettings.setModel("handshake-first");
        AiSession firstSession = sessionWith(firstSettings, "impl-handshake-first");
        OpenCodeSessionSettings secondSettings = new OpenCodeSessionSettings();
        secondSettings.setModel("handshake-second");
        AiSession secondSession = sessionWith(secondSettings, "impl-handshake-second");
        OpenCodeAiImplementation first = implementationUsing(
                new RecordingManager(false, configOptions("handshake-first", "build", "low")),
                firstSession);
        OpenCodeAiImplementation second = implementationUsing(
                new RecordingManager(false, configOptions("handshake-second", "build", "low")),
                secondSession);
        BarHarness firstBar = createBar(first, firstSession, hostFor(new AtomicReference<>()));
        BarHarness secondBar = createBar(second, secondSession, hostFor(new AtomicReference<>()));

        AiTypePropertyBus bus = AiTypePropertyBus.getInstance();
        java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(2);
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener listener = event -> {
            // Mirrors AiTopComponent: the type-wide bus dispatches off the EDT, then each real bar is updated on it.
            SwingUtilities.invokeLater(() -> {
                firstBar.bar().onPropertyEvent(event);
                delivered.countDown();
                secondBar.bar().onPropertyEvent(event);
                delivered.countDown();
            });
        };
        List<String> before = OpenCodeAiImplementation.modelCatalog().getCachedModels();
        bus.addListener(AiTypeEnum.OPENCODE, listener);
        try {
            // A successful OpenCode handshake publishes its discovered model list to the real catalog/bus path.
            OpenCodeAiImplementation.modelCatalog().publish(
                    List.of("handshake-first", "handshake-second", "handshake-discovered"));
            assertTrue(delivered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "the handshake discovery must reach both open OpenCode bars through AiTypePropertyBus");
            SwingUtilities.invokeAndWait(() -> {
            });

            assertEquals("handshake-second", comboAt(secondBar.panel(), 0).getSelectedItem(),
                    "a handshake in the first session must not reset the other idle session's real combo");
        }
        finally {
            bus.removeListener(AiTypeEnum.OPENCODE, listener);
            OpenCodeAiImplementation.modelCatalog().publish(before);
        }
    }

    @Test
    void handleConfigChangeLivePathWritesAppliedModeToSettings() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");
        AiSession session = sessionWith(settings, "impl-live-mode");
        JsonArray applied = configOptions("alpha", "plan", "low");
        RecordingManager manager = new RecordingManager(true, configOptions("alpha", "build", "low"));
        manager.nextResponse = CompletableFuture.completedFuture(applied);
        List<Object> events = new ArrayList<>();
        OpenCodeAiImplementation implementation = implementationUsing(manager, session, events);
        BarHarness harness = createBar(implementation, session, hostFor(new AtomicReference<>()));

        SwingUtilities.invokeAndWait(() -> comboAt(harness.panel(), 1).setSelectedItem("plan"));

        assertEquals("mode", manager.calls.get(0)[0]);
        assertEquals("plan", settings.mode());
        assertTrue(events.stream().anyMatch(e -> e instanceof OpenCodeConfigOptionsEvent));
    }

    @Test
    void liveModeChangeDoesNotChangeGlobalPluginSettingsMode() throws Exception {
        String globalBefore = OpenCodePluginSettings.getMode();
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");
        AiSession session = sessionWith(settings, "impl-live-global");
        RecordingManager manager = new RecordingManager(true, configOptions("alpha", "build", "low"));
        manager.nextResponse = CompletableFuture.completedFuture(configOptions("alpha", "plan", "low"));
        OpenCodeAiImplementation implementation = implementationUsing(manager, session);
        BarHarness harness = createBar(implementation, session, hostFor(new AtomicReference<>()));

        SwingUtilities.invokeAndWait(() -> comboAt(harness.panel(), 1).setSelectedItem("plan"));

        assertEquals(globalBefore, OpenCodePluginSettings.getMode());
        assertEquals("plan", settings.mode());
    }

    @Test
    void liveConfigEqualityGuardSkipsRpcWhenSelectionIsAlreadyCurrent() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");
        AiSession session = sessionWith(settings, "impl-live-equal");
        RecordingManager manager = new RecordingManager(true, configOptions("alpha", "build", "low"));
        OpenCodeAiImplementation implementation = implementationUsing(manager, session);
        BarHarness harness = createBar(implementation, session, hostFor(new AtomicReference<>()));

        SwingUtilities.invokeAndWait(() -> comboAt(harness.panel(), 1).setSelectedItem("build"));

        assertTrue(manager.calls.isEmpty());
    }

    @Test
    void liveChangeWritesAppliedValueFromSnapshotWhenServerNormalisesRequestedValue() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");
        AiSession session = sessionWith(settings, "impl-normalised");
        RecordingManager manager = new RecordingManager(true, configOptions("alpha", "build", "low"));
        manager.nextResponse = CompletableFuture.completedFuture(configOptions("alpha", "plan", "low"));
        OpenCodeAiImplementation implementation = implementationUsing(manager, session);
        BarHarness harness = createBar(implementation, session, hostFor(new AtomicReference<>()));

        SwingUtilities.invokeAndWait(() -> comboAt(harness.panel(), 1).setSelectedItem("plan"));

        assertEquals("plan", settings.mode());
    }

    @Test
    void applyToSettingsEffortCaseWritesEffort() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setEffort("low");
        AiSession session = sessionWith(settings, "impl-effort");
        RecordingManager manager = new RecordingManager(false, configOptions("alpha", "build", "low"));
        OpenCodeAiImplementation implementation = implementationUsing(manager, session);
        BarHarness harness = createBar(implementation, session, hostFor(new AtomicReference<>()));

        SwingUtilities.invokeAndWait(() -> comboAt(harness.panel(), 2).setSelectedItem("high"));

        assertEquals("high", settings.effort());
    }

    @Test
    void compactButtonClickInvokesManagerCompact() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        AiSession session = sessionWith(settings, "impl-compact");
        RecordingManager manager = new RecordingManager(false, configOptions("alpha", "build", "low"));
        OpenCodeAiImplementation implementation = implementationUsing(manager, session);
        AtomicReference<BarHarness> harness = new AtomicReference<>();
        implementation.onStarted(hostFor(new AtomicReference<>()));
        SwingUtilities.invokeAndWait(() -> {
            OpenCodeAiInfoBarExtension bar
                                       = (OpenCodeAiInfoBarExtension) implementation.createInfoBarExtension(session, hostFor(new AtomicReference<>()));
            JPanel panel = (JPanel) bar.createComponents().get(0);
            ((javax.swing.JButton) bar.createComponents().get(2)).doClick();
            harness.set(new BarHarness(bar, panel));
        });

        assertEquals(1, manager.compactCalls);
    }

    @Test
    void idlePickPublishesSnapshotSoLaterAvailableModelsEventDoesNotRevertIt() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setModel("alpha");
        AiSession session = sessionWith(settings, "impl-idle-broadcast");
        RecordingManager manager = new RecordingManager(false, configOptions("alpha", "build", "low"));
        List<Object> events = new ArrayList<>();
        OpenCodeAiImplementation implementation = implementationUsing(manager, session, events);
        BarHarness harness = createBar(implementation, session, hostFor(new AtomicReference<>()));

        SwingUtilities.invokeAndWait(() -> comboAt(harness.panel(), 0).setSelectedItem("beta"));
        OpenCodeConfigOptionsEvent snapshot = events.stream()
                .filter(OpenCodeConfigOptionsEvent.class::isInstance)
                .map(OpenCodeConfigOptionsEvent.class::cast)
                .findFirst().orElseThrow();
        harness.bar().onAiProcessImplEvent(snapshot);
        SwingUtilities.invokeAndWait(() -> harness.bar().onPropertyEvent(
                new kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AvailableModelsEvent(
                        List.of("alpha", "beta", "gamma"))));

        assertEquals("beta", comboAt(harness.panel(), 0).getSelectedItem());
    }

    @Test
    void rpcFailureRestoresAuthoritativeSnapshotAndReportsInfoWithoutFailure() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");
        AiSession session = sessionWith(settings, "impl-rpc-failure");
        JsonArray authoritative = configOptions("alpha", "build", "low");
        RecordingManager manager = new RecordingManager(true, authoritative);
        manager.nextResponse = CompletableFuture.failedFuture(new IllegalStateException("rejected"));
        List<Object> events = new ArrayList<>();
        OpenCodeAiImplementation implementation = implementationUsing(manager, session, events);
        BarHarness harness = createBar(implementation, session, hostFor(new AtomicReference<>()));

        SwingUtilities.invokeAndWait(() -> comboAt(harness.panel(), 1).setSelectedItem("plan"));

        assertEquals("build", settings.mode());
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent s
                                                 && s.type() == StatusEventTypeEnum.INFO));
        assertFalse(events.stream().anyMatch(e -> e instanceof StatusEvent s
                                                  && s.type() == StatusEventTypeEnum.FAILED));
    }

    @Test
    void rpcFailureWithNoAuthoritativeSnapshotRestoresFallbackAndReportsInfo() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setModel("alpha");
        settings.setMode("build");
        AiSession session = sessionWith(settings, "impl-rpc-null-authoritative");
        RecordingManager manager = new RecordingManager(true, null);
        manager.nextResponse = CompletableFuture.failedFuture(new IllegalStateException("rejected"));
        List<Object> events = new ArrayList<>();
        OpenCodeAiImplementation implementation = implementationUsing(manager, session, events);
        BarHarness harness = createBar(implementation, session, hostFor(new AtomicReference<>()));

        SwingUtilities.invokeAndWait(() -> comboAt(harness.panel(), 1).setSelectedItem("plan"));

        OpenCodeConfigOptionsEvent fallback = events.stream()
                .filter(OpenCodeConfigOptionsEvent.class::isInstance)
                .map(OpenCodeConfigOptionsEvent.class::cast)
                .findFirst().orElseThrow();
        assertEquals("build", OpenCodeAiInfoBarExtension.parseConfigOptions(fallback.configOptions())
                .stream().filter(spec -> "mode".equals(spec.id())).findFirst().orElseThrow().currentValue());
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent s
                                                 && s.type() == StatusEventTypeEnum.INFO));
    }

    private static OpenCodeAiImplementation implementationUsing(RecordingManager manager, AiSession session) {
        return implementationUsing(manager, session, new ArrayList<>());
    }

    private static OpenCodeAiImplementation implementationUsing(RecordingManager manager, AiSession session,
                                                                List<Object> events) {
        return new OpenCodeAiImplementation(events::add, null) {
            {
                currentSession = session;
            }

            @Override
            protected OpenCodeAiProcessManager delegate() {
                return manager;
            }
        };
    }
}

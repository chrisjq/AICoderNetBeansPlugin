package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AvailableModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiAvailableThinkingLevelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiContextUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiModelChangedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiSessionModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiThinkingLevelChangedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiVersionCheckedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiVersionVerifiedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.session.PiPersistentSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui.PiAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * PiAiImplementation had zero test coverage. Mirrors ClaudeAiImplementationTest's shape (a
 * currentSession-seeded anonymous subclass, no real start()/MCP registration) and covers: afterStart()'s
 * resumeSession + effective-thinking-level wiring, onStarted()'s persistence of a freshly minted pi session
 * id, isStoredSessionValid(), and compact()'s running guard.
 */
class PiAiImplementationTest {

    private static PiAiImplementation implFor(AiSession session) {
        return implFor(session, e -> {
        });
    }

    private static PiAiImplementation implFor(AiSession session, AiProcessEventListener listener) {
        return new PiAiImplementation(listener, null) {
            {
                currentSession = session;
            }
        };
    }

    private static AiSession newSession(String id, PiSessionSettings settings) {
        return new AiSession(id, "Test", null, AiTypeEnum.PI, null, settings, Instant.now(), Instant.now());
    }

    // ---- getCurrentModel(): the reopen path's model resolution (Boss's pre-live-pass check). startWithDiscovery(null)
    // — the only caller on the reopen path, from AiTopComponent — resolves effectiveModel via this exact method before
    // calling start(execPath, effectiveModel), so this is the one untested link in "does a resumed session relaunch
    // with its stored model": start()'s own `this.model = model` assignment is a single unconditional line at the top
    // of PiAiProcessManager.start() (before any validation/MCP/version-check work), already exercised generically by
    // PiAiProcessManagerTest's buildLaunchCommand tests, and startWithDiscovery itself cannot be driven directly here
    // without either a real executable on PATH or triggering start()'s real MCP registration/`pi --version` subprocess
    // — disproportionate for pinning a resolution rule this simple. ----
    @Test
    void getCurrentModel_returnsTheSessionsStoredModelWhenPresent() {
        String globalBefore = PiPluginSettings.getModel();
        try {
            PiPluginSettings.setModel("github-copilot/gpt-5-mini"); // deliberately different from the session's own
            PiSessionSettings settings = new PiSessionSettings();
            settings.setModel("anthropic/claude-sonnet-4-6");
            PiAiImplementation impl = implFor(newSession("pi-model-1", settings));

            assertEquals("anthropic/claude-sonnet-4-6", impl.getCurrentModel(),
                    "a reopened session with its own stored model must win over the global default, not fall back to it");
        }
        finally {
            PiPluginSettings.setModel(globalBefore);
        }
    }

    @Test
    void getCurrentModel_fallsBackToTheGlobalDefaultWhenTheSessionHasNoModel() {
        String globalBefore = PiPluginSettings.getModel();
        try {
            PiPluginSettings.setModel("github-copilot/gpt-5-mini");
            PiAiImplementation impl = implFor(newSession("pi-model-2", new PiSessionSettings()));

            assertEquals("github-copilot/gpt-5-mini", impl.getCurrentModel(),
                    "only a session with no stored model at all should fall back to the global default");
        }
        finally {
            PiPluginSettings.setModel(globalBefore);
        }
    }

    // ---- afterStart(): resumeSession from a stored piSessionId + effective thinking level ----
    @Test
    void afterStart_resumesFromStoredPiSessionIdAndAppliesItsThinkingLevel() {
        PiSessionSettings settings = new PiSessionSettings();
        settings.setPiSessionId("stored-pi-id");
        settings.setThinkingLevel("high");
        PiAiImplementation impl = implFor(newSession("pi-afterstart-1", settings));

        impl.afterStart();

        assertEquals("stored-pi-id", impl.delegate().getPiSessionId(),
                "afterStart() must call resumeSession() with the settings' own stored id");
        List<String> cmd = impl.delegate().buildLaunchCommand("sid", "/tmp/ext.ts");
        int idx = cmd.indexOf("--thinking");
        assertTrue(idx >= 0, cmd.toString());
        assertEquals("high", cmd.get(idx + 1), "the session's own thinking level must win over any global default");
    }

    @Test
    void afterStart_fallsBackToTheGlobalThinkingLevelWhenTheSessionHasNone() {
        String globalBefore = PiPluginSettings.getThinkingLevel();
        try {
            PiPluginSettings.setThinkingLevel("medium");
            PiAiImplementation impl = implFor(newSession("pi-afterstart-2", new PiSessionSettings()));

            impl.afterStart();

            List<String> cmd = impl.delegate().buildLaunchCommand("sid", "/tmp/ext.ts");
            int idx = cmd.indexOf("--thinking");
            assertTrue(idx >= 0, cmd.toString());
            assertEquals("medium", cmd.get(idx + 1));
        }
        finally {
            PiPluginSettings.setThinkingLevel(globalBefore);
        }
    }

    @Test
    void afterStart_doesNotResumeWhenNoPiSessionIdIsStored() {
        PiAiImplementation impl = implFor(newSession("pi-afterstart-3", new PiSessionSettings()));

        impl.afterStart();

        assertNull(impl.delegate().getPiSessionId(), "nothing stored means nothing to resume from");
    }

    // ---- onStarted(): persist a freshly minted pi session id back into the session's settings ----
    @Test
    void onStarted_persistsAFreshlyMintedPiSessionIdBackIntoSettings() {
        PiSessionSettings settings = new PiSessionSettings();
        PiAiImplementation impl = implFor(newSession("pi-onstarted-1", settings));
        // Simulates what start() resolves piSessionId to when nothing was stored yet — see PiAiProcessManager's own
        // resolvePiSessionId(), pinned separately in PiAiProcessManagerTest.
        impl.delegate().resumeSession("freshly-minted-id");
        FakeHost host = new FakeHost();

        impl.onStarted(host);

        assertEquals("freshly-minted-id", settings.piSessionId());
        assertEquals(1, host.updateCalls);
    }

    @Test
    void onStarted_isANoOpWhenSettingsAlreadyMatchTheLiveId() {
        PiSessionSettings settings = new PiSessionSettings();
        settings.setPiSessionId("already-current");
        PiAiImplementation impl = implFor(newSession("pi-onstarted-2", settings));
        impl.delegate().resumeSession("already-current");
        FakeHost host = new FakeHost();

        impl.onStarted(host);

        assertEquals(0, host.updateCalls, "no update needed when the stored id already matches the live one");
    }

    // ---- version check: a bar built after the probe ran is seeded with its result; later probes arrive as events ----
    @Test
    void createInfoBarExtension_withNoVersionCheckYetStartsWithTheWarningButtonHidden() {
        AiSession session = newSession("pi-version-1", new PiSessionSettings());
        PiAiImplementation impl = implFor(session);

        AiInfoBarExtension bar = impl.createInfoBarExtension(session, new FakeHost());

        assertFalse(bar.createComponents().get(3).isVisible(), "no version check yet — the button must start hidden");
    }

    @Test
    void createInfoBarExtension_replaysAVersionCheckTheSessionAlreadyHolds() {
        AiSession session = newSession("pi-version-2", new PiSessionSettings());
        PiAiImplementation impl = implFor(session);
        // 0.1.0 is below every pi release, so it stays an untested major.minor whatever TESTED_MAJOR_MINOR is bumped to.
        impl.delegate().setVersionCheckForTests(new PiVersionCheck("0.1.0"));

        AiInfoBarExtension bar = impl.createInfoBarExtension(session, new FakeHost());

        JButton versionBtn = (JButton) bar.createComponents().get(3);
        assertTrue(versionBtn.isVisible(),
                "a bar opened after the probe already ran has no event to wait for, so it must be seeded with the check");
    }

    // ---- model catalog: the bar hears the type-wide list from the property bus, and a late bar is seeded from the cache ----
    @Test
    void createInfoBarExtension_seedsTheModelComboFromTheCatalogSnapshot() {
        List<String> before = PiAiImplementation.modelCatalog().getCachedModels();
        try {
            PiAiImplementation.modelCatalog().publish(List.of("catalog-p/one", "catalog-p/two"));
            AiSession session = newSession("pi-catalog-1", new PiSessionSettings());
            PiAiImplementation impl = implFor(session);

            AiInfoBarExtension bar = impl.createInfoBarExtension(session, new FakeHost());

            @SuppressWarnings("unchecked")
            javax.swing.JComboBox<String> modelCombo = (javax.swing.JComboBox<String>) bar.createComponents().get(0);
            List<String> shown = new ArrayList<>();
            for (int i = 0; i < modelCombo.getItemCount(); i++) {
                shown.add(modelCombo.getItemAt(i));
            }
            assertEquals(List.of("catalog-p/one", "catalog-p/two"), shown,
                    "the bus does not replay, so a bar opened after discovery must be seeded from the catalog snapshot");
        }
        finally {
            PiAiImplementation.modelCatalog().publish(before);
        }
    }

    @Test
    void modelDiscoveryPublishFiresAnAvailableModelsEventOnThePiPropertyBus() throws Exception {
        String[] knownBefore = PiPluginSettings.getKnownModels();
        List<String> catalogBefore = PiAiImplementation.modelCatalog().getCachedModels();
        java.util.concurrent.BlockingQueue<AvailableModelsEvent> received = new java.util.concurrent.LinkedBlockingQueue<>();
        AiPropertyListener busListener = event -> {
            if (event instanceof AvailableModelsEvent available) {
                received.add(available);
            }
        };
        AiTypePropertyBus.getInstance().addListener(AiTypeEnum.PI, busListener);
        try {
            PiModelDiscovery.publish(List.of("bus-p/alpha", "bus-p/beta"));

            AvailableModelsEvent event = received.poll(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(List.of("bus-p/alpha", "bus-p/beta"), event == null ? null : event.models(),
                    "discovery must publish the list as an AvailableModelsEvent on the pi type's property bus");
        }
        finally {
            AiTypePropertyBus.getInstance().removeListener(AiTypeEnum.PI, busListener);
            PiAiImplementation.modelCatalog().publish(catalogBefore);
            PiPluginSettings.setDiscoveredModels(knownBefore);
        }
    }

    // ---- a bar opened mid-session must show what the session holds NOW, not just the catalog seed ----
    @Test
    @SuppressWarnings("unchecked")
    void createInfoBarExtension_showsTheSessionsCurrentModelsModelLevelAndUsageWhenOpenedMidSession() throws Exception {
        AiSession session = newSession("pi-late-bar-1", new PiSessionSettings());
        PiAiImplementation impl = implFor(session);
        PiAiProcessManager manager = impl.delegate();
        manager.report(new PiSessionModelsEvent(List.of("p1/m1", "p2/m2")));
        manager.report(new PiAvailableThinkingLevelsEvent(List.of("low", "medium", "high")));
        manager.report(new PiModelChangedEvent("p1/m1"));
        manager.report(new PiThinkingLevelChangedEvent("low"));
        manager.report(new PiContextUsageEvent(100, 1000));
        // The session moves on before the bar exists: model and level changed, and the context filled up.
        manager.report(new PiModelChangedEvent("p2/m2"));
        manager.report(new PiThinkingLevelChangedEvent("high"));
        manager.report(new PiContextUsageEvent(900, 1000));

        AiInfoBarExtension bar = impl.createInfoBarExtension(session, new FakeHost());
        SwingUtilities.invokeAndWait(() -> {
        });

        List<javax.swing.JComponent> components = bar.createComponents();
        javax.swing.JComboBox<String> modelCombo = (javax.swing.JComboBox<String>) components.get(0);
        javax.swing.JComboBox<String> levelCombo = (javax.swing.JComboBox<String>) components.get(1);
        javax.swing.JProgressBar gauge = (javax.swing.JProgressBar) components.get(2);
        List<String> models = new ArrayList<>();
        for (int i = 0; i < modelCombo.getItemCount(); i++) {
            models.add(modelCombo.getItemAt(i));
        }
        assertEquals(List.of("p1/m1", "p2/m2"), models, "the session's own model list, not only the catalog seed");
        assertEquals("p2/m2", modelCombo.getSelectedItem(), "the model the session runs now, not the first one it reported");
        assertEquals("high", levelCombo.getSelectedItem(), "the level the session runs now, not the first one it reported");
        assertEquals(90, gauge.getValue(), "the context fill the session has now, not the empty gauge a fresh bar starts with");
    }

    @Test
    @SuppressWarnings("unchecked")
    void infoBarChoicesPersistToTheCreationHostBeforeOnStarted() throws Exception {
        PiSessionSettings settings = new PiSessionSettings();
        AiSession session = newSession("pi-bar-host-1", settings);
        PiAiImplementation impl = implFor(session);
        FakeHost host = new FakeHost();
        AiInfoBarExtension bar = impl.createInfoBarExtension(session, host);

        JComboBox<String> modelCombo = (JComboBox<String>) bar.createComponents().get(0);
        JComboBox<String> thinkingCombo = (JComboBox<String>) bar.createComponents().get(1);
        SwingUtilities.invokeAndWait(() -> {
            modelCombo.addItem("openrouter/anthropic/claude");
            modelCombo.setSelectedItem("openrouter/anthropic/claude");
            thinkingCombo.addItem("high");
            thinkingCombo.setSelectedItem("high");
        });

        assertEquals("openrouter/anthropic/claude", settings.model());
        assertEquals("high", settings.thinkingLevel());
        assertEquals(2, host.updateCalls,
                "the listener must persist each user choice through createInfoBarExtension's host, without onStarted()");
        assertEquals(settings, host.lastUpdatedSettings);
    }

    // ---- the bar only raises the user's choice; the implementation asks pi and persists only what pi confirmed ----
    private static final String FAIL_FIRST_THEN_SUCCEED_SCRIPT = """
            IFS= read -r line
            printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":false,"error":"model not found"/'
            while IFS= read -r line; do
                printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
            done
            """;

    private static final String NEVER_ANSWER_SCRIPT = "cat >/dev/null";

    private static final String SUCCEED_AND_ECHO_REQUESTS_TO_STDERR_SCRIPT = """
            while IFS= read -r line; do
                printf '%s\\n' "$line" >&2
                printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
            done
            """;

    private static final class BarHarness implements AutoCloseable {

        final PiSessionSettings settings = new PiSessionSettings();
        final FakeHost host = new FakeHost();
        final List<AiProcessEvent> allEvents = new CopyOnWriteArrayList<>();
        final BlockingQueue<AiProcessEvent> pendingEvents = new LinkedBlockingQueue<>();
        final BlockingQueue<String> piRequests = new LinkedBlockingQueue<>();
        final PiPersistentSession fakePi;
        final PiAiProcessManager manager;
        final AiInfoBarExtension bar;
        final JComboBox<String> modelCombo;
        final JComboBox<String> levelCombo;

        BarHarness(String script) throws Exception {
            this(script, true);
        }

        @SuppressWarnings("unchecked")
        BarHarness(String script, boolean piSpawned) throws Exception {
            settings.setModel("p1/m1");
            settings.setThinkingLevel("low");
            AtomicReference<AiInfoBarExtension> barRef = new AtomicReference<>();
            AiSession session = newSession("pi-bar-" + UUID.randomUUID(), settings);
            PiAiImplementation impl = implFor(session, e -> {
                if (e instanceof AiProcessImplEvent implEvent && barRef.get() instanceof PiAiInfoBarExtension live) {
                    SwingUtilities.invokeLater(() -> live.onAiProcessImplEvent(implEvent));
                }
                allEvents.add(e);
                pendingEvents.add(e);
            });
            manager = impl.delegate();
            manager.report(new PiSessionModelsEvent(List.of("p1/m1", "p2/m2", "p3/m3", "openrouter/anthropic/claude")));
            manager.report(new PiAvailableThinkingLevelsEvent(List.of("low", "medium", "high")));
            manager.report(new PiModelChangedEvent("p1/m1"));
            manager.report(new PiThinkingLevelChangedEvent("low"));
            manager.setExecutablePathForTests("/bin/pi");
            if (piSpawned) {
                fakePi = PiPersistentSession.launch(List.of("sh", "-c", script), null, line -> {
                }, piRequests::add);
                manager.setRunningForTests(true);
                manager.persistentSession = fakePi;
            }
            else {
                fakePi = null;
            }
            bar = impl.createInfoBarExtension(session, host);
            barRef.set(bar);
            awaitEventThread();
            modelCombo = (JComboBox<String>) bar.createComponents().get(0);
            levelCombo = (JComboBox<String>) bar.createComponents().get(1);
            allEvents.clear();
            pendingEvents.clear();
        }

        void pickModel(String model) throws Exception {
            SwingUtilities.invokeAndWait(() -> modelCombo.setSelectedItem(model));
        }

        void pickThinkingLevel(String level) throws Exception {
            SwingUtilities.invokeAndWait(() -> levelCombo.setSelectedItem(level));
        }

        void awaitEvent(java.util.function.Predicate<AiProcessEvent> wanted) throws Exception {
            while (true) {
                AiProcessEvent next = pendingEvents.poll(10, TimeUnit.SECONDS);
                if (next == null) {
                    throw new AssertionError("timed out waiting for an event; saw " + allEvents);
                }
                if (wanted.test(next)) {
                    awaitEventThread();
                    return;
                }
            }
        }

        static void awaitEventThread() throws Exception {
            SwingUtilities.invokeAndWait(() -> {
            });
        }

        @Override
        public void close() {
            if (fakePi != null) {
                fakePi.close();
            }
        }
    }

    @Test
    void aRejectedModelPickRevertsTheBarAndIsNeverPersisted() throws Exception {
        try (BarHarness h = new BarHarness(FAIL_FIRST_THEN_SUCCEED_SCRIPT)) {
            h.pickModel("p2/m2");
            h.awaitEvent(e -> e instanceof PiModelChangedEvent);

            assertEquals("p1/m1", h.modelCombo.getSelectedItem(), "the bar must go back to the model pi is actually running");

            h.pickModel("p3/m3");
            h.host.awaitUpdate();
            assertEquals(1, h.host.updateCalls, "only pi's confirmed pick is persisted — the rejected one never is");
            assertEquals(List.of("p3/m3|low"), h.host.settingsAtUpdate);
            assertEquals("p3/m3", h.settings.model());
            assertEquals(List.of(StatusEventTypeEnum.INFO), statusTypes(h.allEvents),
                    "a rejected change is an INFO only: no READY/FAILED/EXITED closer, no BUSY");
            StatusEvent info = h.allEvents.stream().filter(StatusEvent.class::isInstance).map(StatusEvent.class::cast).findFirst().orElseThrow();
            assertEquals("pi did not change model p2/m2: model not found", info.text());
            assertEquals(new PiModelChangedEvent("p1/m1"), h.allEvents.stream()
                    .filter(PiModelChangedEvent.class::isInstance).findFirst().orElseThrow(),
                    "the first model event after the rejection is the re-emit of the confirmed model");
        }
    }

    @Test
    void aRejectedThinkingLevelPickRevertsTheBarAndIsNeverPersisted() throws Exception {
        try (BarHarness h = new BarHarness(FAIL_FIRST_THEN_SUCCEED_SCRIPT)) {
            h.pickThinkingLevel("high");
            h.awaitEvent(e -> e instanceof PiThinkingLevelChangedEvent);

            assertEquals("low", h.levelCombo.getSelectedItem(), "the bar must go back to the level pi is actually running");

            h.pickThinkingLevel("medium");
            h.host.awaitUpdate();
            assertEquals(1, h.host.updateCalls, "only pi's confirmed pick is persisted — the rejected one never is");
            assertEquals(List.of("p1/m1|medium"), h.host.settingsAtUpdate);
            assertEquals("medium", h.settings.thinkingLevel());
            assertEquals(List.of(StatusEventTypeEnum.INFO), statusTypes(h.allEvents),
                    "a rejected change is an INFO only: no READY/FAILED/EXITED closer, no BUSY");
            StatusEvent info = h.allEvents.stream().filter(StatusEvent.class::isInstance).map(StatusEvent.class::cast).findFirst().orElseThrow();
            assertEquals("pi did not change thinking level high: model not found", info.text());
            assertEquals(new PiThinkingLevelChangedEvent("low"), h.allEvents.stream()
                    .filter(PiThinkingLevelChangedEvent.class::isInstance).findFirst().orElseThrow());
        }
    }

    @Test
    void aPickPiHasNotAnsweredYetIsNotPersisted() throws Exception {
        try (BarHarness h = new BarHarness(NEVER_ANSWER_SCRIPT)) {
            h.pickModel("p2/m2");
            h.pickThinkingLevel("high");

            assertEquals(0, h.host.updateCalls, "nothing is persisted until pi confirms the change");
            assertEquals("p1/m1", h.settings.model());
            assertEquals("low", h.settings.thinkingLevel());
        }
    }

    @Test
    void aConfirmedModelPickSplitsOnTheFirstSlashAndIsPersistedOnConfirmation() throws Exception {
        try (BarHarness h = new BarHarness(SUCCEED_AND_ECHO_REQUESTS_TO_STDERR_SCRIPT)) {
            h.pickModel("openrouter/anthropic/claude");
            h.host.awaitUpdate();

            String request = h.piRequests.poll(10, TimeUnit.SECONDS);
            assertTrue(request != null && request.contains("\"type\":\"set_model\""), String.valueOf(request));
            assertTrue(request.contains("\"provider\":\"openrouter\""), request);
            assertTrue(request.contains("\"modelId\":\"anthropic/claude\""), request);
            assertEquals(1, h.host.updateCalls);
            assertEquals(List.of("openrouter/anthropic/claude|low"), h.host.settingsAtUpdate);
            assertEquals("openrouter/anthropic/claude", h.settings.model());
        }
    }

    @Test
    void aConfirmedThinkingLevelPickIsPersistedOnConfirmation() throws Exception {
        try (BarHarness h = new BarHarness(SUCCEED_AND_ECHO_REQUESTS_TO_STDERR_SCRIPT)) {
            h.pickThinkingLevel("high");
            h.host.awaitUpdate();

            String request = h.piRequests.poll(10, TimeUnit.SECONDS);
            assertTrue(request != null && request.contains("\"type\":\"set_thinking_level\""), String.valueOf(request));
            assertEquals(1, h.host.updateCalls);
            assertEquals(List.of("p1/m1|high"), h.host.settingsAtUpdate);
            assertEquals("high", h.settings.thinkingLevel());
        }
    }

    private static String flagValue(List<String> command, String flag) {
        int at = command.indexOf(flag);
        assertTrue(at >= 0 && at + 1 < command.size(), flag + " missing from " + command);
        return command.get(at + 1);
    }

    @Test
    void aPickMadeBeforePiHasSpawnedIsPersistedAndIsWhatPiIsLaunchedWith() throws Exception {
        try (BarHarness h = new BarHarness(null, false)) {
            h.pickModel("p2/m2");
            h.host.awaitUpdate();
            h.pickThinkingLevel("high");
            h.host.awaitUpdate();

            assertEquals(List.of("p2/m2|low", "p2/m2|high"), h.host.settingsAtUpdate);
            assertEquals("p2/m2", h.settings.model());
            assertEquals("high", h.settings.thinkingLevel());
            List<String> launch = h.manager.buildLaunchCommand("sid", "/ext");
            assertEquals("p2/m2", flagValue(launch, "--model"),
                    "what the bar and the saved settings show must be what pi starts on");
            assertEquals("high", flagValue(launch, "--thinking"));
        }
    }

    // ---- two live requests can be answered in either order; the bar and the saved settings follow what pi confirmed ----
    private static String outOfOrderScript(String slowModelId, boolean slowSucceeds) {
        String slowVerdict = slowSucceeds ? "\"success\":true" : "\"success\":false,\"error\":\"slow verdict\"";
        String fastVerdict = slowSucceeds ? "\"success\":false,\"error\":\"fast verdict\"" : "\"success\":true";
        return """
                while IFS= read -r line; do
                    case "$line" in
                        *'"modelId":"%s"'*)
                            ( sleep 1; printf '%%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response",%s/' ) &
                            ;;
                        *)
                            printf '%%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response",%s/'
                            ;;
                    esac
                done
                """.formatted(slowModelId, slowVerdict, fastVerdict);
    }

    private static List<PiModelChangedEvent> modelEvents(List<AiProcessEvent> events) {
        return events.stream().filter(PiModelChangedEvent.class::isInstance).map(PiModelChangedEvent.class::cast).toList();
    }

    @Test
    void anEarlierPickRejectedLateRevertsToTheLatestConfirmedModelNotTheOneBeforeThePick() throws Exception {
        try (BarHarness h = new BarHarness(outOfOrderScript("m2", false))) {
            h.pickModel("p2/m2");
            h.pickModel("p3/m3");
            h.awaitEvent(e -> e instanceof PiModelChangedEvent);
            h.host.awaitUpdate();
            assertEquals("p3/m3", h.modelCombo.getSelectedItem());

            h.awaitEvent(e -> e instanceof PiModelChangedEvent);

            assertEquals("p3/m3", h.modelCombo.getSelectedItem(),
                    "A's late rejection must put the bar back on B, which pi confirmed meanwhile — not on the pre-pick p1/m1");
            assertEquals(List.of(new PiModelChangedEvent("p3/m3"), new PiModelChangedEvent("p3/m3")), modelEvents(h.allEvents));
            assertEquals(1, h.host.updateCalls, "only B was ever confirmed");
            assertEquals(List.of("p3/m3|low"), h.host.settingsAtUpdate);
            assertEquals("p3/m3", h.settings.model());
            assertEquals(List.of(StatusEventTypeEnum.INFO), statusTypes(h.allEvents));
            assertEquals("pi did not change model p2/m2: slow verdict",
                    h.allEvents.stream().filter(StatusEvent.class::isInstance).map(StatusEvent.class::cast).findFirst().orElseThrow().text());
        }
    }

    @Test
    void anEarlierPickConfirmedLateWinsOverALaterPickRefusedFirst() throws Exception {
        try (BarHarness h = new BarHarness(outOfOrderScript("m2", true))) {
            h.pickModel("p2/m2");
            h.pickModel("p3/m3");
            h.awaitEvent(e -> e instanceof PiModelChangedEvent);
            assertEquals("p1/m1", h.modelCombo.getSelectedItem(),
                    "B was refused while A is still pending: the bar shows what pi has confirmed so far");
            assertEquals(0, h.host.updateCalls);

            h.awaitEvent(e -> e instanceof PiModelChangedEvent);
            h.host.awaitUpdate();

            assertEquals("p2/m2", h.modelCombo.getSelectedItem());
            assertEquals(List.of(new PiModelChangedEvent("p1/m1"), new PiModelChangedEvent("p2/m2")), modelEvents(h.allEvents));
            assertEquals(1, h.host.updateCalls, "only A was ever confirmed");
            assertEquals(List.of("p2/m2|low"), h.host.settingsAtUpdate);
            assertEquals("p2/m2", h.settings.model());
            assertEquals(List.of(StatusEventTypeEnum.INFO), statusTypes(h.allEvents));
            assertEquals("pi did not change model p3/m3: fast verdict",
                    h.allEvents.stream().filter(StatusEvent.class::isInstance).map(StatusEvent.class::cast).findFirst().orElseThrow().text());
        }
    }

    // ---- a request that timed out stays timed out, even if pi does answer it afterwards ----
    private static final String ANSWER_SET_MODEL_TOO_LATE_SCRIPT = """
            while IFS= read -r line; do
                case "$line" in
                    *'"type":"set_model"'*)
                        ( sleep 1.5; printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/' ) &
                        ;;
                    *'"type":"get_state"'*)
                        printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true,"data":{"model":{"id":"m1","provider":"p1"},"thinkingLevel":"low"}/'
                        ;;
                    *)
                        printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
                        ;;
                esac
            done
            """;

    @Test
    void aSetModelAnsweredAfterItsTimeoutDoesNotRunAgainOrPersistAnything() throws Exception {
        try (BarHarness h = new BarHarness(ANSWER_SET_MODEL_TOO_LATE_SCRIPT)) {
            h.manager.rpcTimeoutMillisForTests = 300;
            h.pickModel("p2/m2");
            h.awaitEvent(e -> e instanceof PiThinkingLevelChangedEvent);
            assertEquals("p1/m1", h.modelCombo.getSelectedItem());

            Thread.sleep(2000);
            BarHarness.awaitEventThread();

            assertEquals(List.of(StatusEventTypeEnum.INFO), statusTypes(h.allEvents),
                    "one timeout, one INFO: the late answer must not run the failure or the success path again");
            assertEquals("pi did not change model p2/m2: pi did not answer in time",
                    h.allEvents.stream().filter(StatusEvent.class::isInstance).map(StatusEvent.class::cast).findFirst().orElseThrow().text());
            assertFalse(modelEvents(h.allEvents).contains(new PiModelChangedEvent("p2/m2")),
                    "the late success of a request the user was already told failed must not move the bar");
            assertEquals("p1/m1", h.modelCombo.getSelectedItem());
            assertEquals(0, h.host.updateCalls, "nothing is persisted for a request that timed out");
            assertEquals("p1/m1", h.settings.model());
        }
    }

    // ---- a timed-out pick's get_state answer is a snapshot; a newer confirmation for the same property beats it ----
    private static final String STALE_STATE_AFTER_MODEL_A = "{\"model\":{\"id\":\"m2\",\"provider\":\"p2\"},\"thinkingLevel\":\"low\"}";
    private static final String STALE_STATE_AFTER_LEVEL_A = "{\"model\":{\"id\":\"m1\",\"provider\":\"p1\"},\"thinkingLevel\":\"high\"}";

    private static String heldGetStateScript(Path release, String neverAnswered, String staleState, String slowAnswered, String slowSeconds) {
        return """
                while IFS= read -r line; do
                    printf '%%s\\n' "$line" >&2
                    case "$line" in
                        *'"type":"get_state"'*)
                            ( while [ ! -e '%s' ]; do sleep 0.05; done; printf '%%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true,"data":%s/' ) &
                            ;;
                        *'%s'*)
                            ;;
                        *'%s'*)
                            ( sleep %s; printf '%%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/' ) &
                            ;;
                        *)
                            printf '%%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
                            ;;
                    esac
                done
                """.formatted(release, staleState, neverAnswered, slowAnswered, slowSeconds);
    }

    private static Path unreleased() throws Exception {
        Path release = Files.createTempFile("pi-get-state-release", "");
        Files.delete(release);
        return release;
    }

    private static void awaitPiRequest(BarHarness h, String fragment) throws Exception {
        while (true) {
            String request = h.piRequests.poll(10, TimeUnit.SECONDS);
            if (request == null) {
                throw new AssertionError("pi never received a request containing " + fragment);
            }
            if (request.contains(fragment)) {
                return;
            }
        }
    }

    @Test
    void aStaleGetStateAnswerDoesNotOverwriteAModelConfirmedWhileItWasHeld() throws Exception {
        Path release = unreleased();
        try (BarHarness h = new BarHarness(heldGetStateScript(release, "\"modelId\":\"m2\"", STALE_STATE_AFTER_MODEL_A, "\"modelId\":\"none\"", "0"))) {
            h.manager.rpcTimeoutMillisForTests = 1500;
            h.pickModel("p2/m2");
            awaitPiRequest(h, "get_state");
            h.pickModel("p3/m3");
            h.awaitEvent(e -> new PiModelChangedEvent("p3/m3").equals(e));
            h.host.awaitUpdate();

            Files.createFile(release);
            Thread.sleep(600);
            BarHarness.awaitEventThread();

            assertEquals("p3/m3", h.modelCombo.getSelectedItem(), "the late, stale get_state must not put the bar back on the timed-out A");
            assertEquals("p3/m3", flagValue(h.manager.buildLaunchCommand("sid", "/ext"), "--model"), "a relaunch must start on B");
            assertEquals("p3/m3", h.settings.model());
            assertEquals(1, h.host.updateCalls);
            assertFalse(modelEvents(h.allEvents).contains(new PiModelChangedEvent("p2/m2")), "the stale snapshot must not even be reported");
        }
        finally {
            Files.deleteIfExists(release);
        }
    }

    @Test
    void aStaleGetStateAnswerDoesNotOverwriteAThinkingLevelConfirmedWhileItWasHeld() throws Exception {
        Path release = unreleased();
        try (BarHarness h = new BarHarness(heldGetStateScript(release, "\"level\":\"high\"", STALE_STATE_AFTER_LEVEL_A, "\"level\":\"none\"", "0"))) {
            h.manager.rpcTimeoutMillisForTests = 1500;
            h.pickThinkingLevel("high");
            awaitPiRequest(h, "get_state");
            h.pickThinkingLevel("medium");
            h.awaitEvent(e -> new PiThinkingLevelChangedEvent("medium").equals(e));
            h.host.awaitUpdate();

            Files.createFile(release);
            Thread.sleep(600);
            BarHarness.awaitEventThread();

            assertEquals("medium", h.levelCombo.getSelectedItem(), "the late, stale get_state must not put the bar back on the timed-out level");
            assertEquals("medium", flagValue(h.manager.buildLaunchCommand("sid", "/ext"), "--thinking"));
            assertEquals("medium", h.settings.thinkingLevel());
            assertEquals(1, h.host.updateCalls);
            assertFalse(h.allEvents.contains(new PiThinkingLevelChangedEvent("high")), "the stale snapshot must not even be reported");
        }
        finally {
            Files.deleteIfExists(release);
        }
    }

    @Test
    void aPickStillInFlightWhenGetStateIsSentIsTheLastWordOnceItIsConfirmed() throws Exception {
        Path release = Files.createTempFile("pi-get-state-release", "");
        try (BarHarness h = new BarHarness(heldGetStateScript(release, "\"modelId\":\"m2\"", STALE_STATE_AFTER_MODEL_A, "\"modelId\":\"m3\"", "0.8"))) {
            h.manager.rpcTimeoutMillisForTests = 1000;
            h.pickModel("p2/m2");
            Thread.sleep(400);
            h.pickModel("p3/m3");
            h.awaitEvent(e -> new PiModelChangedEvent("p3/m3").equals(e));
            h.host.awaitUpdate();

            assertEquals(List.of(new PiModelChangedEvent("p1/m1"), new PiModelChangedEvent("p2/m2"), new PiModelChangedEvent("p3/m3")),
                    modelEvents(h.allEvents),
                    "A's timeout re-emits p1/m1, the get_state answer (sent while B was pending) reports p2/m2, then B's confirmation has the last word");
            assertEquals("p3/m3", h.modelCombo.getSelectedItem());
            assertEquals("p3/m3", flagValue(h.manager.buildLaunchCommand("sid", "/ext"), "--model"));
            assertEquals("p3/m3", h.settings.model());
            assertEquals(1, h.host.updateCalls);
        }
        finally {
            Files.deleteIfExists(release);
        }
    }

    // ---- the version dialog's answers are handled by the implementation, not the bar ----
    private static void answerVersionDialog(AiInfoBarExtension bar, PiVersionCheck check, boolean verified) throws Exception {
        PiAiInfoBarExtension piBar = (PiAiInfoBarExtension) bar;
        SwingUtilities.invokeAndWait(() -> piBar.onAiProcessImplEvent(new PiVersionCheckedEvent(check)));
        piBar.setVersionDialogPresenter((parent, shown, onVerified, onMarkedNotWorking)
                -> (verified ? onVerified : onMarkedNotWorking).run());
        JButton warningBtn = (JButton) piBar.createComponents().get(3);
        SwingUtilities.invokeAndWait(warningBtn::doClick);
    }

    @Test
    void verifyingTheVersionInTheDialogStoresItAndFiresTheVersionEvent() throws Exception {
        String before = PiPluginSettings.getVerifiedVersion();
        BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        AiPropertyListener busListener = event -> {
            if (event instanceof PiVersionVerifiedEvent) {
                received.add(event);
            }
        };
        AiTypePropertyBus.getInstance().addListener(AiTypeEnum.PI, busListener);
        try {
            AiSession session = newSession("pi-verify-1", new PiSessionSettings());
            AiInfoBarExtension bar = implFor(session).createInfoBarExtension(session, new FakeHost());
            PiVersionCheck check = new PiVersionCheck("0.0.77-verify-test");

            answerVersionDialog(bar, check, true);

            assertEquals("0.0.77-verify-test", PiPluginSettings.getVerifiedVersion());
            assertTrue(received.poll(10, TimeUnit.SECONDS) != null, "the verified version must be announced on the pi property bus");
        }
        finally {
            AiTypePropertyBus.getInstance().removeListener(AiTypeEnum.PI, busListener);
            PiPluginSettings.setVerifiedVersion(before);
        }
    }

    @Test
    void markingTheVersionNotWorkingInTheDialogRemembersItAndFiresTheVersionEvent() throws Exception {
        BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        AiPropertyListener busListener = event -> {
            if (event instanceof PiVersionVerifiedEvent) {
                received.add(event);
            }
        };
        AiTypePropertyBus.getInstance().addListener(AiTypeEnum.PI, busListener);
        try {
            AiSession session = newSession("pi-verify-2", new PiSessionSettings());
            AiInfoBarExtension bar = implFor(session).createInfoBarExtension(session, new FakeHost());
            PiVersionCheck check = new PiVersionCheck("0.0.78-not-working-test");
            assertFalse(check.isMarkedNotWorkingThisSession());

            answerVersionDialog(bar, check, false);

            assertTrue(check.isMarkedNotWorkingThisSession());
            assertTrue(received.poll(10, TimeUnit.SECONDS) != null, "the answer must be announced on the pi property bus");
        }
        finally {
            AiTypePropertyBus.getInstance().removeListener(AiTypeEnum.PI, busListener);
        }
    }

    // ---- isStoredSessionValid(): pi's --session-id creates-or-resumes transparently, so always true ----
    @Test
    void isStoredSessionValid_alwaysTrue() {
        PiAiImplementation impl = implFor(newSession("pi-valid-1", new PiSessionSettings()));

        assertTrue(impl.isStoredSessionValid("any-id"));
        assertTrue(impl.isStoredSessionValid(null));
    }

    // ---- compact()'s running guard, exercised through the info bar's real Compact button ----
    @Test
    void compact_refusesWhileNotRunning() {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        AiSession session = newSession("pi-compact-1", new PiSessionSettings());
        PiAiImplementation impl = implFor(session, events::add);
        FakeHost host = new FakeHost();

        AiInfoBarExtension bar = impl.createInfoBarExtension(session, host);
        clickCompact(bar);

        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                                                 && se.type() == StatusEventTypeEnum.INFO && se.text().contains("Wait for pi to finish")),
                "must refuse with an INFO notice while delegate().isRunning() is false");
    }

    // ---- compact()'s accept/reject paths, once past the running guard. ----
    // pi emits no echo turn after compacting (raw JSON logging against pi 0.87: compaction_start, compaction_end,
    // the compact response, then nothing), so the session must be released by runWork's READY alone.
    @Test
    void compact_acceptedPathReleasesTheSession() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        AiSession session = newSession("pi-compact-2", new PiSessionSettings());
        PiAiImplementation impl = implFor(session, events::add);
        impl.delegate().setRunningForTests(true);
        PiPersistentSession fakeSession = PiPersistentSession.launch(
                List.of("sh", "-c", SUCCESS_ECHO_SCRIPT), null, line -> {
        }, line -> {
        });
        impl.delegate().persistentSession = fakeSession;
        FakeHost host = new FakeHost();

        try {
            AiInfoBarExtension bar = impl.createInfoBarExtension(session, host);
            clickCompact(bar);

            awaitTrue(() -> events.stream().anyMatch(e -> e instanceof StatusEvent se
                                                          && se.type() == StatusEventTypeEnum.READY), "the session to be released after pi accepts the compact");
            assertEquals(List.of(StatusEventTypeEnum.BUSY, StatusEventTypeEnum.READY), statusTypes(events),
                    "runWork must report exactly one BUSY and one READY");
        }
        finally {
            fakeSession.close();
        }
    }

    @Test
    void compact_nothingToCompactIsHarmlessReady() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiSession session = newSession("pi-compact-3", new PiSessionSettings());
        PiAiImplementation impl = implFor(session, events::add);
        impl.delegate().setRunningForTests(true);
        PiPersistentSession fakeSession = PiPersistentSession.launch(
                List.of("sh", "-c", FAILURE_ECHO_SCRIPT), null, line -> {
        }, line -> {
        });
        impl.delegate().persistentSession = fakeSession;
        FakeHost host = new FakeHost();

        try {
            AiInfoBarExtension bar = impl.createInfoBarExtension(session, host);
            clickCompact(bar);

            awaitTrue(() -> events.stream().anyMatch(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.READY),
                    "nothing-to-compact must close with READY");
            assertEquals(List.of(StatusEventTypeEnum.BUSY, StatusEventTypeEnum.READY), statusTypes(events),
                    "runWork must report exactly one BUSY and one FAILED");
        }
        finally {
            fakeSession.close();
        }
    }

    @Test
    void compact_alreadyCompactedIsHarmlessReady() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        AiSession session = newSession("pi-compact-already", new PiSessionSettings());
        PiAiImplementation impl = implFor(session, events::add);
        impl.delegate().setRunningForTests(true);
        PiPersistentSession fakeSession = PiPersistentSession.launch(
                List.of("sh", "-c", ALREADY_COMPACTED_ECHO_SCRIPT), null, line -> {
        }, line -> {
        });
        impl.delegate().persistentSession = fakeSession;
        try {
            clickCompact(impl.createInfoBarExtension(session, new FakeHost()));
            awaitTrue(() -> events.stream().anyMatch(e -> e instanceof StatusEvent se
                                                          && se.type() == StatusEventTypeEnum.READY), "already-compacted must close with READY");
            assertEquals(List.of(StatusEventTypeEnum.BUSY, StatusEventTypeEnum.READY), statusTypes(events));
        }
        finally {
            fakeSession.close();
        }
    }

    // ---- compact()'s refusals: the two that must leave the busy contract untouched (INFO only, no BUSY, no RPC) ----
    @Test
    void compact_secondClickWhileOneIsInFlightIsInfoOnlyAndSendsNoSecondRpc() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        AiSession session = newSession("pi-compact-twice", new PiSessionSettings());
        PiAiImplementation impl = implFor(session, events::add);
        impl.delegate().setRunningForTests(true);
        Path rpcLog = Files.createTempFile("pi-compact-rpc", ".log");
        PiPersistentSession fakeSession = PiPersistentSession.launch(
                List.of("sh", "-c", recordingScript(rpcLog)), null, line -> {
        }, line -> {
        });
        impl.delegate().persistentSession = fakeSession;
        try {
            AiInfoBarExtension bar = impl.createInfoBarExtension(session, new FakeHost());
            clickCompact(bar);
            awaitTrue(() -> compactRpcCount(rpcLog) == 1, "the first compact RPC to reach pi");
            assertTrue(impl.delegate().isWorkInFlight(), "the first compaction is unanswered, so it is still in flight");

            clickCompact(bar);
            Thread.sleep(300); // a wrongly sent second RPC would land within this window

            assertEquals(List.of(StatusEventTypeEnum.BUSY, StatusEventTypeEnum.INFO), statusTypes(events),
                    "the second click must add one INFO and no second BUSY");
            StatusEvent info = events.stream().filter(StatusEvent.class::isInstance).map(StatusEvent.class::cast)
                    .filter(e -> e.type() == StatusEventTypeEnum.INFO).findFirst().orElseThrow();
            assertEquals("Compaction already in progress", info.text());
            assertEquals(1, compactRpcCount(rpcLog), "the refused click must not send a second compact RPC");
        }
        finally {
            impl.delegate().stop();
            Files.deleteIfExists(rpcLog);
        }
    }

    @Test
    void compact_whileAwaitingTheCancelResultIsInfoOnlyAndSendsNoRpc() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        AiSession session = newSession("pi-compact-cancelling", new PiSessionSettings());
        PiAiImplementation impl = implFor(session, events::add);
        impl.delegate().setRunningForTests(true);
        impl.delegate().cancelWatchdogMillis = 600_000; // the watchdog must not clear the wait mid-test
        Path rpcLog = Files.createTempFile("pi-compact-rpc", ".log");
        PiPersistentSession fakeSession = PiPersistentSession.launch(
                List.of("sh", "-c", recordingScript(rpcLog)), null, line -> {
        }, line -> {
        });
        impl.delegate().persistentSession = fakeSession;
        try {
            impl.sendPrompt("hello", null, List.of()); // reuses the fake session; pi never answers, so a turn is in flight
            awaitTrue(() -> impl.delegate().isProcessing(), "the queued send to reach pi and start the turn");
            impl.interrupt(InterruptTypeEnum.Cancel);
            awaitTrue(() -> rpcLines(rpcLog).stream().anyMatch(l -> l.contains("\"abort\"")),
                    "the abort RPC to reach pi");
            // Non-vacuity: with the awaitingCancelResult clause gone, the remaining guard (isBusy && !workInFlight)
            // is false here, so only that clause can be what refuses the click.
            assertTrue(impl.delegate().isAwaitingCancelResult());
            assertFalse(impl.isBusy(), "the cancel released the turn, so busy alone would not refuse the compact");
            events.clear();

            clickCompact(impl.createInfoBarExtension(session, new FakeHost()));
            Thread.sleep(300); // a wrongly sent compact RPC would land within this window

            assertEquals(List.of(StatusEventTypeEnum.INFO), statusTypes(events),
                    "compact while awaiting the cancel result must be a single INFO, no BUSY");
            assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                                                     && se.text().contains("Wait for pi to finish before compacting")));
            assertEquals(0, compactRpcCount(rpcLog), "the refused click must not send a compact RPC");
        }
        finally {
            impl.delegate().stop();
            Files.deleteIfExists(rpcLog);
        }
    }

    // Appends every RPC line pi receives to the log and never answers, so whatever was sent stays in flight.
    private static String recordingScript(Path log) {
        return "while IFS= read -r line; do printf '%s\\n' \"$line\" >> '" + log + "'; done";
    }

    private static List<String> rpcLines(Path log) {
        try {
            return Files.readAllLines(log);
        }
        catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static long compactRpcCount(Path log) {
        return rpcLines(log).stream().filter(l -> l.contains("\"type\":\"compact\"")).count();
    }

    // pi has no generic "command" command — the command name IS the request's own "type" (e.g. "compact"), so the
    // echo must match that variable name, not a literal "command" (the plugin was sending
    // {type:"command", command:"prompt", ...} when it must send {type:"prompt", ...}).
    private static final String SUCCESS_ECHO_SCRIPT = """
            while IFS= read -r line; do
                printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
            done
            """;

    private static final String FAILURE_ECHO_SCRIPT = """
            while IFS= read -r line; do
                printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":false,"error":"nothing to compact"/'
            done
            """;

    private static final String ALREADY_COMPACTED_ECHO_SCRIPT = """
              while IFS= read -r line; do
                  printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":false,"error":"Already compacted"/'
              done
              """;

    private static List<StatusEventTypeEnum> statusTypes(List<AiProcessEvent> events) {
        return events.stream()
                .filter(StatusEvent.class::isInstance)
                .map(StatusEvent.class::cast)
                .map(StatusEvent::type)
                .toList();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier cond, String desc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timeout waiting for " + desc);
            }
            Thread.sleep(20);
        }
    }

    private static void clickCompact(AiInfoBarExtension bar) {
        JButton compactBtn = (JButton) bar.createComponents().get(4);
        for (ActionListener l : compactBtn.getActionListeners()) {
            l.actionPerformed(new ActionEvent(compactBtn, ActionEvent.ACTION_PERFORMED, "compact"));
        }
    }

    private static final class FakeHost implements AiSessionHost {

        volatile int updateCalls = 0;
        volatile AiSessionSettings lastUpdatedSettings;
        final List<String> settingsAtUpdate = new CopyOnWriteArrayList<>();
        private final Semaphore updates = new Semaphore(0);

        void awaitUpdate() throws InterruptedException {
            if (!updates.tryAcquire(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for the host to be asked to persist");
            }
        }

        @Override
        public File resolveWorkDir() {
            return null;
        }

        @Override
        public AiSessionSettings getSessionSettings() {
            return null;
        }

        @Override
        public void updateSessionSettings(AiSessionSettings newSettings) {
            if (newSettings instanceof PiSessionSettings ps) {
                settingsAtUpdate.add(ps.model() + "|" + ps.thinkingLevel());
            }
            updateCalls++;
            lastUpdatedSettings = newSettings;
            updates.release();
        }
    }
}

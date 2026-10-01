package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.generated.rpc.SessionHistoryCompactResult;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotModelFallbackEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotQuotaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotReasoningEffortClearedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotReasoningEffortsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.ui.GithubCopilotAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.ui.GithubCopilotInfoBarListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Mirrors CodexAiImplementationTest and OpenCodeAiImplementationTest — the same bug class those two were
 * fixed for: a session's chosen model must be used at startup rather than silently falling back to the global
 * default.
 */
class GithubCopilotAiImplementationTest {

    private static AiSession newSession(String id, GithubCopilotSessionSettings settings) {
        return new AiSession(id, "Test", null, AiTypeEnum.GitHubCoPilot, null, settings, Instant.now(), Instant.now());
    }

    private static GithubCopilotAiImplementation implFor(AiSession session) {
        return new GithubCopilotAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };
    }

    private static final class CompactManager extends GithubCopilotProcessManager {

        private final CompletableFuture<SessionHistoryCompactResult> compactResult;
        private int compactCalls;
        private final java.util.concurrent.atomic.AtomicInteger aborts = new java.util.concurrent.atomic.AtomicInteger();
        private final List<String> prompts = new java.util.ArrayList<>();

        CompactManager(AiProcessEventListener listener, CompletableFuture<SessionHistoryCompactResult> compactResult) {
            super(listener);
            this.compactResult = compactResult;
        }

        void setState(boolean running, boolean processing) {
            this.running = running;
            this.processing = processing;
        }

        @Override
        CompletableFuture<SessionHistoryCompactResult> compactHistory(String customInstructions) {
            compactCalls++;
            return compactResult;
        }

        @Override
        void abortManualCompaction() {
            aborts.incrementAndGet();
        }

        @Override
        public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
            prompts.add(text);
        }
    }

    private static void clickCompact(GithubCopilotAiImplementation impl, AiSession session, AiSessionHost host) {
        GithubCopilotAiInfoBarExtension bar = impl.createInfoBarExtension(session, host);
        JButton compact = (JButton) bar.createComponents().get(2);
        compact.doClick();
    }

    private static StatusEvent status(List<AiProcessEvent> events, StatusEventTypeEnum type) {
        return events.stream()
                .filter(StatusEvent.class::isInstance)
                .map(StatusEvent.class::cast)
                .filter(event -> event.type() == type)
                .findFirst()
                .orElseThrow();
    }

    private static long countStatuses(List<AiProcessEvent> events, StatusEventTypeEnum type) {
        return events.stream()
                .filter(StatusEvent.class::isInstance)
                .map(StatusEvent.class::cast)
                .filter(event -> event.type() == type)
                .count();
    }

    private static long closingStatusCount(List<AiProcessEvent> events) {
        return events.stream()
                .filter(StatusEvent.class::isInstance)
                .map(StatusEvent.class::cast)
                .filter(event -> event.type() == StatusEventTypeEnum.READY
                                 || event.type() == StatusEventTypeEnum.FAILED
                                 || event.type() == StatusEventTypeEnum.EXITED)
                .count();
    }

    private static AiSessionHost stubHost(AiSessionSettings settings, AtomicReference<AiSessionSettings> updated) {
        return new AiSessionHost() {
            @Override
            public File resolveWorkDir() {
                return null;
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
    void publishQuota_broadcastsToEveryCopilotListener() throws Exception {
        CountDownLatch delivered = new CountDownLatch(2);
        GithubCopilotQuotaEvent quota = new GithubCopilotQuotaEvent(
                false, 12, 300, 96.0, "2026-09-01T00:00:00Z", false);
        AiPropertyListener first = event -> {
            assertEquals(quota, event);
            delivered.countDown();
        };
        AiPropertyListener second = event -> {
            assertEquals(quota, event);
            delivered.countDown();
        };
        AiTypePropertyBus bus = AiTypePropertyBus.getInstance();
        bus.addListener(AiTypeEnum.GitHubCoPilot, first);
        bus.addListener(AiTypeEnum.GitHubCoPilot, second);
        try {
            GithubCopilotAiImplementation.publishQuota(quota);
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        }
        finally {
            bus.removeListener(AiTypeEnum.GitHubCoPilot, first);
            bus.removeListener(AiTypeEnum.GitHubCoPilot, second);
        }
    }

    /**
     * Quota and reasoning-effort capabilities are account-wide facts, cached in
     * {@link GithubCopilotAiImplementation} and replayed to a tab opened AFTER the fact was published — the
     * same "late tab" guarantee
     * {@code CodexAiImplementationTest.publishRateLimit_updatesEveryOpenBarAndLateCreatedBar} proves for
     * Codex's rate limit. Both caches must replay, not just one.
     */
    @Test
    void createInfoBarExtensionReplaysBothCachedQuotaAndCachedReasoningEffortsForALateTab() throws Exception {
        GithubCopilotQuotaEvent quota = new GithubCopilotQuotaEvent(
                false, 40, 300, 96.0, "2026-09-01T00:00:00Z", false);
        GithubCopilotReasoningEffortsEvent efforts = new GithubCopilotReasoningEffortsEvent(
                Map.of("gpt-5", List.of("low", "high")), Map.of("gpt-5", "high"));
        GithubCopilotAiImplementation.publishQuota(quota);
        GithubCopilotAiImplementation.publishReasoningEfforts(efforts);

        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        settings.setModel("gpt-5");
        GithubCopilotAiImplementation impl = implFor(newSession("gh-late-tab", settings));
        AiSessionHost host = stubHost(settings, new AtomicReference<>());
        AtomicReference<GithubCopilotAiInfoBarExtension> lateBar = new AtomicReference<>();

        // createInfoBarExtension's setSelectedModel/onPropertyEvent calls self-defer to the EDT when called
        // off it, so the replay's effect would not be visible yet if this ran on the test thread directly.
        SwingUtilities.invokeAndWait(() -> lateBar.set(
                impl.createInfoBarExtension(newSession("gh-late-tab", settings), host)));

        JProgressBar quotaBar = (JProgressBar) lateBar.get().createComponents().get(4);
        assertTrue(quotaBar.isVisible(), "a late tab must replay the cached quota");
        assertEquals("4%", quotaBar.getString(),
                "the cached quota's remaining percentage (96%) must reach the late bar as 4% used");

        JComboBox<?> effortCombo = (JComboBox<?>) lateBar.get().createComponents().get(1);
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < effortCombo.getItemCount(); i++) {
            items.add(effortCombo.getItemAt(i));
        }
        assertTrue(items.contains("high"),
                "a late tab must ALSO replay the cached reasoning-effort options: " + items);
    }

    /**
     * {@link GithubCopilotReasoningEffortsEvent}'s compact constructor must deep-copy: {@code Map.copyOf}
     * alone is shallow, so the caller's own mutable inner lists would otherwise still be reachable — a later
     * mutation of one would silently corrupt an already-published, supposedly-immutable cached event.
     */
    @Test
    void reasoningEffortsEventDeepCopiesInnerListsSoLaterMutationOfTheCallersListHasNoEffect() {
        List<String> mutableEfforts = new ArrayList<>(List.of("low", "high"));
        Map<String, List<String>> supportedByModel = new HashMap<>();
        supportedByModel.put("gpt-5", mutableEfforts);

        GithubCopilotReasoningEffortsEvent event = new GithubCopilotReasoningEffortsEvent(supportedByModel, Map.of());

        mutableEfforts.add("medium");
        supportedByModel.put("new-model", List.of("low"));

        assertEquals(List.of("low", "high"), event.supportedFor("gpt-5"),
                "mutating the caller's inner list after construction must not change the cached event");
        assertTrue(event.supportedFor("new-model").isEmpty(),
                "mutating the caller's outer map after construction must not change the cached event");
    }

    // Tests the extracted helper directly rather than startWithDiscovery(), whose
    // other branch depends on GithubCopilotExecutableLocator.locate() finding a
    // real CLI on disk — environment-dependent, not something a unit test assumes.
    @Test
    void resolveStartupModel_usesSessionModelNotGlobalDefault() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        settings.setModel("claude-sonnet-4.5");
        GithubCopilotAiImplementation impl = implFor(newSession("gh-swd-1", settings));

        assertEquals("claude-sonnet-4.5", impl.resolveStartupModel(null),
                "startWithDiscovery(null) must fall back to the session's chosen model, not "
                + "GithubCopilotPluginSettings.getModel() — this is the per-session-model bug");
    }

    @Test
    void resolveStartupModel_explicitArgumentWinsOverSessionModel() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        settings.setModel("claude-sonnet-4.5");
        GithubCopilotAiImplementation impl = implFor(newSession("gh-swd-2", settings));

        assertEquals("gpt-5.4", impl.resolveStartupModel("gpt-5.4"),
                "an explicit model argument must still win over the session setting");
    }

    @Test
    void resolveStartupModel_noSessionModelFallsBackToGlobalDefault() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        // settings.setModel(...) never called.
        GithubCopilotAiImplementation impl = implFor(newSession("gh-swd-3", settings));

        assertEquals(GithubCopilotPluginSettings.getModel(), impl.resolveStartupModel(null),
                "with no session model and no explicit argument, the global default is still correct");
    }

    @Test
    void resolveStartupModel_blankSessionModelFallsBackToGlobalDefault() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        settings.setModel("   ");
        GithubCopilotAiImplementation impl = implFor(newSession("gh-swd-4", settings));

        assertEquals(GithubCopilotPluginSettings.getModel(), impl.resolveStartupModel(null),
                "a blank (not null) session model must not be treated as a real choice");
    }

    @Test
    void resolveStartupModel_withNoCurrentSessionFallsBackToGlobalDefault() {
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(e -> {
        }, null);

        assertEquals(GithubCopilotPluginSettings.getModel(), impl.resolveStartupModel(null),
                "no session at all must not throw — the global default applies");
    }

    @Test
    void resolveEffectiveReasoningEffort_sessionValueWinsOverGlobalDefault() {
        String globalBefore = GithubCopilotPluginSettings.getReasoningEffort();
        GithubCopilotPluginSettings.setReasoningEffort("low");
        try {
            GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
            settings.setReasoningEffort("high");
            GithubCopilotAiImplementation impl = implFor(newSession("gh-effort-1", settings));

            assertEquals("high", impl.resolveEffectiveReasoningEffort(),
                    "the session's own reasoning effort must win over the global default");
        }
        finally {
            GithubCopilotPluginSettings.setReasoningEffort(globalBefore);
        }
    }

    @Test
    void resolveEffectiveReasoningEffort_fallsBackToGlobalDefaultWhenSessionUnset() {
        String globalBefore = GithubCopilotPluginSettings.getReasoningEffort();
        GithubCopilotPluginSettings.setReasoningEffort("medium");
        try {
            GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
            // settings.setReasoningEffort(...) never called.
            GithubCopilotAiImplementation impl = implFor(newSession("gh-effort-2", settings));

            assertEquals("medium", impl.resolveEffectiveReasoningEffort());
        }
        finally {
            GithubCopilotPluginSettings.setReasoningEffort(globalBefore);
        }
    }

    @Test
    void resolveEffectiveReasoningEffort_blankSessionValueFallsBackToGlobalDefault() {
        String globalBefore = GithubCopilotPluginSettings.getReasoningEffort();
        GithubCopilotPluginSettings.setReasoningEffort("medium");
        try {
            GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
            settings.setReasoningEffort("   ");
            GithubCopilotAiImplementation impl = implFor(newSession("gh-effort-3", settings));

            assertEquals("medium", impl.resolveEffectiveReasoningEffort(),
                    "a blank (not null) session effort must not be treated as a real choice");
        }
        finally {
            GithubCopilotPluginSettings.setReasoningEffort(globalBefore);
        }
    }

    @Test
    void resolveEffectiveReasoningEffort_returnsNullWhenNeitherSet() {
        String globalBefore = GithubCopilotPluginSettings.getReasoningEffort();
        GithubCopilotPluginSettings.setReasoningEffort("");
        try {
            GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
            GithubCopilotAiImplementation impl = implFor(newSession("gh-effort-4", settings));

            assertNull(impl.resolveEffectiveReasoningEffort(),
                    "with nothing set anywhere, the effort must be omitted entirely (null), never a default level");
        }
        finally {
            GithubCopilotPluginSettings.setReasoningEffort(globalBefore);
        }
    }

    @Test
    void resolveEffectiveReasoningEffortFromSession_trueWhenSessionPinsEffort() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        settings.setReasoningEffort("high");
        GithubCopilotAiImplementation impl = implFor(newSession("gh-proven-1", settings));

        assertEquals("high", impl.resolveEffectiveReasoningEffortFromSession(),
                "provenance must report the session value when the session pinned one");
    }

    @Test
    void resolveEffectiveReasoningEffortFromSession_falseWhenSessionUnset_globalDefaultIsSource() {
        // Rule 3a depends on the manager being able to tell the scope the effective value came from: when the session
        // has no own value, the effective value is inherited from the global default and must NOT be treated as
        // session-pinned (it must be omitted, never cleared, when the model doesn't support it).
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        // settings.setReasoningEffort(...) never called.
        GithubCopilotAiImplementation impl = implFor(newSession("gh-proven-2", settings));

        assertNull(impl.resolveEffectiveReasoningEffortFromSession(),
                "with no session-pinned effort, provenance must report null (effective value is global-sourced)");
    }

    @Test
    void resolveEffectiveReasoningEffortFromSession_falseWhenSessionValueBlank() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        settings.setReasoningEffort("   ");
        GithubCopilotAiImplementation impl = implFor(newSession("gh-proven-3", settings));

        assertNull(impl.resolveEffectiveReasoningEffortFromSession(),
                "a blank session effort is not a pin — provenance must report the global default as the source");
    }

    @Test
    void resolveEffectiveReasoningEffortFromSession_falseWithNoCurrentSession() {
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(e -> {
        }, null);

        assertNull(impl.resolveEffectiveReasoningEffortFromSession(),
                "no session at all must not throw and must report null provenance");
    }

    @Test
    void setReasoningEffort_updatesSessionSettings() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();

        implFor(newSession("gh-seteffort-1", settings)).setReasoningEffort("xhigh");

        assertEquals("xhigh", settings.reasoningEffort(), "setReasoningEffort must update the session settings");
    }

    @Test
    void setReasoningEffort_doesNotChangeGithubCopilotPluginSettingsGlobalDefault() {
        String globalBefore = GithubCopilotPluginSettings.getReasoningEffort();
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();

        implFor(newSession("gh-seteffort-2", settings)).setReasoningEffort("max");

        assertEquals(globalBefore, GithubCopilotPluginSettings.getReasoningEffort(),
                "setReasoningEffort must NOT write the global plugin default");
    }

    @Test
    void setModel_updatesSessionSettings() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();

        implFor(newSession("gh-setmodel-1", settings)).setModel("gpt-5.4");

        assertEquals("gpt-5.4", settings.model(), "setModel must update the session settings");
    }

    @Test
    void setModel_doesNotChangeGithubCopilotPluginSettingsGlobalDefault() {
        String globalBefore = GithubCopilotPluginSettings.getModel();
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();

        implFor(newSession("gh-setmodel-2", settings)).setModel("claude-opus-4.6");

        assertEquals(globalBefore, GithubCopilotPluginSettings.getModel(),
                "setModel must NOT write the global plugin default");
    }

    @Test
    void applyModelFallback_updatesAndPersistsSessionSettings() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = implFor(newSession("gh-fallback-1", settings));
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        impl.onStarted(stubHost(settings, updated));

        impl.applyModelFallback("auto");

        assertEquals("auto", settings.model(), "fallback must update the session model");
        assertEquals(settings, updated.get(), "fallback must persist the updated settings");
    }

    @Test
    void applyModelFallback_persistsAndInfoBarUpdatesThroughProcessEvent() throws Exception {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        AiSession session = newSession("gh-fallback-event", settings);
        GithubCopilotAiImplementation impl = implFor(session);
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        AiSessionHost host = stubHost(settings, updated);
        GithubCopilotAiInfoBarExtension bar = impl.createInfoBarExtension(session, host);
        impl.onStarted(host);

        impl.applyModelFallback("copilot-fallback-event");
        SwingUtilities.invokeAndWait(() -> bar.onAiProcessImplEvent(new GithubCopilotModelFallbackEvent("copilot-fallback-event")));

        assertEquals("copilot-fallback-event", settings.model(), "fallback must persist the session model before the UI event");
        assertEquals(settings, updated.get(), "fallback must persist through the session host");
        assertEquals("copilot-fallback-event", bar.getSelectedModel(),
                "the info bar must receive the fallback through AiProcessImplEvent, not a direct implementation call");
    }

    @Test
    void lateInfoBarReplaysCachedAvailableModels() throws Exception {
        List<String> before = GithubCopilotAiImplementation.modelCatalog().getCachedModels();
        try {
            GithubCopilotAiImplementation.modelCatalog().publish(List.of("copilot-late-one", "copilot-late-two"));
            GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
            settings.setModel("copilot-late-one");
            AiSession session = newSession("gh-late-models", settings);
            GithubCopilotAiImplementation impl = implFor(session);
            AiSessionHost host = stubHost(settings, new AtomicReference<>());
            AtomicReference<GithubCopilotAiInfoBarExtension> ref = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> ref.set(impl.createInfoBarExtension(session, host)));

            JComboBox<?> combo = (JComboBox<?>) ref.get().createComponents().get(0);
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < combo.getItemCount(); i++) {
                items.add(combo.getItemAt(i));
            }
            assertTrue(items.contains("copilot-late-one"), "late bars must replay cached AvailableModelsEvent models");
            assertTrue(items.contains("copilot-late-two"), "late bars must replay the complete cached model list");
        }
        finally {
            GithubCopilotAiImplementation.modelCatalog().publish(before);
        }
    }

    @Test
    void applyModelFallback_withoutHostOrInfoBarDoesNotThrow() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = implFor(newSession("gh-fallback-2", settings));

        assertDoesNotThrow(() -> impl.applyModelFallback("auto"));
        assertEquals("auto", settings.model(), "fallback must still update the session model");
    }

    // Review finding: GithubCopilotProcessManager.resolveValidatedReasoningEffort only clears its own in-memory
    // field — without persisting that clear into session settings, the stale value would come back and re-fire the
    // INFO event on every subsequent start. Wired via setOnReasoningEffortCleared in the constructor; these tests
    // exercise the handler directly, matching applyModelFallback's own test style.
    @Test
    void handleReasoningEffortCleared_updatesAndPersistsSessionSettings() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        settings.setReasoningEffort("high");
        GithubCopilotAiImplementation impl = implFor(newSession("gh-cleared-1", settings));
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        impl.onStarted(stubHost(settings, updated));

        impl.handleReasoningEffortCleared();

        assertNull(settings.reasoningEffort(), "clearing must update the session settings");
        assertEquals(settings, updated.get(), "clearing must persist the updated settings");
    }

    @Test
    void handleReasoningEffortCleared_withoutHostOrInfoBarDoesNotThrow() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        settings.setReasoningEffort("high");
        GithubCopilotAiImplementation impl = implFor(newSession("gh-cleared-2", settings));

        assertDoesNotThrow(impl::handleReasoningEffortCleared);
        assertNull(settings.reasoningEffort(), "clearing must still update the session settings");
    }

    @Test
    void handleReasoningEffortCleared_updatesInfoBarComboToGlobalDefault() throws Exception {
        String globalBefore = GithubCopilotPluginSettings.getReasoningEffort();
        GithubCopilotPluginSettings.setReasoningEffort("medium");
        try {
            GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
            settings.setReasoningEffort("high");
            AiSession session = newSession("gh-cleared-3", settings);
            GithubCopilotAiImplementation impl = implFor(session);
            AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
            GithubCopilotAiInfoBarExtension ext = impl.createInfoBarExtension(session, stubHost(settings, updated));
            // "medium" must never be shown unless the current model actually advertises it — seed the live
            // per-model cache for whatever model the combo is ACTUALLY showing (queried via getSelectedModel(),
            // not assumed to equal GithubCopilotPluginSettings.getModel() — a fresh-vs-editable JComboBox's
            // getSelectedItem() and its editor's displayed text are not guaranteed to agree, the same divergence
            // setAvailableModels already works around).
            GithubCopilotReasoningEffortsEvent efforts = new GithubCopilotReasoningEffortsEvent(
                    Map.of(ext.getSelectedModel(), List.of("low", "medium")), Map.of());
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(efforts.supportedByModel(), efforts.defaultByModel());
            // The settings call above only updates the type-wide cache and fires it on the property bus; this bar
            // was already created and, outside the real IDE shell, is not subscribed to that bus (only
            // AiTopComponent subscribes an open tab to it), so deliver the fact to it directly here the same way
            // the shell would.
            SwingUtilities.invokeAndWait(() -> ext.onPropertyEvent(efforts));

            impl.handleReasoningEffortCleared();
            // The process manager reports this state transition through the implementation event channel; the
            // shell normally forwards it to the bar. Drive that same channel here, then flush the EDT.
            SwingUtilities.invokeAndWait(() -> ext.onAiProcessImplEvent(new GithubCopilotReasoningEffortClearedEvent()));
            SwingUtilities.invokeAndWait(() -> {
            });

            assertEquals("medium", ext.getSelectedReasoningEffort(),
                    "the info bar must reflect the clear immediately, falling back to the global default for "
                    + "display rather than keeping the just-cleared value visible");
        }
        finally {
            GithubCopilotPluginSettings.setReasoningEffort(globalBefore);
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
        }
    }

    @Test
    void managerClearEventReachesBarWithoutInvokingUserListener() throws Exception {
        String globalBefore = GithubCopilotPluginSettings.getReasoningEffort();
        GithubCopilotPluginSettings.setReasoningEffort("medium");
        try {
            GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
            settings.setModel("gpt-5.4");
            settings.setReasoningEffort("high");
            AiSession session = newSession("gh-integrated-clear", settings);
            GithubCopilotAiImplementation impl = implFor(session);
            // The model supports low and medium but not the session's stored "high", so validation must clear it,
            // and the global default "medium" is a valid option to fall back to. Stated explicitly: the manager
            // validates against the type-wide cache, which would otherwise be whatever another test left behind.
            GithubCopilotReasoningEffortsEvent efforts = new GithubCopilotReasoningEffortsEvent(
                    Map.of("gpt-5.4", List.of("low", "medium")), Map.of());
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(efforts.supportedByModel(), Map.of());
            // Build the bar on the EDT, as AiTopComponent does. Built on another thread, its switch to the session's
            // model is only queued, and the bar is still on the global default model when the clear is handled.
            AtomicReference<GithubCopilotAiInfoBarExtension> built = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                GithubCopilotAiInfoBarExtension created = impl.createInfoBarExtension(session,
                        stubHost(settings, new AtomicReference<>()));
                created.onPropertyEvent(efforts);
                built.set(created);
            });
            GithubCopilotAiInfoBarExtension bar = built.get();
            assertEquals("gpt-5.4", bar.getSelectedModel(), "precondition: the bar shows the session's model");
            AtomicInteger userChanges = new AtomicInteger();
            bar.addListener(new GithubCopilotInfoBarListener() {
                @Override
                public void onCompactRequested() {
                }

                @Override
                public void onModelChanged(String model) {
                }

                @Override
                public void onReasoningEffortChanged(String effort) {
                    userChanges.incrementAndGet();
                }
            });

            GithubCopilotProcessManager manager = new GithubCopilotProcessManager(event -> {
                if (event instanceof AiProcessImplEvent implEvent) {
                    // Mirrors AiTopComponent's own delivery: the core hops onto the EDT before touching the bar
                    // (via InfoBarExtensionGate), the bar itself no longer self-dispatches.
                    SwingUtilities.invokeLater(() -> bar.onAiProcessImplEvent(implEvent));
                }
            });
            manager.setReasoningEffort("high");
            // resolveValidatedReasoningEffort is the manager's real validation path.
            manager.resolveValidatedReasoningEffort("gpt-5.4");
            SwingUtilities.invokeAndWait(() -> {
            });

            assertEquals("medium", bar.getSelectedReasoningEffort(),
                    "manager clear event must reach the bar and display the global default");
            assertEquals(0, userChanges.get(),
                    "event-driven bar updates must not invoke the user/RPC reasoning listener");
        }
        finally {
            GithubCopilotPluginSettings.setReasoningEffort(globalBefore);
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
        }
    }

    @Test
    void compact_callsRpcExactlyOnceAndNeverSendsPrompt() {
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompactManager manager = new CompactManager(events::add, CompletableFuture.completedFuture(
                new SessionHistoryCompactResult(true, 40L, 2L, null, null)));
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);
        AiSession session = newSession("gh-compact-rpc", settings);

        clickCompact(impl, session, stubHost(settings, new AtomicReference<>()));

        assertEquals(1, manager.compactCalls);
        assertTrue(manager.prompts.isEmpty(), "real compaction must not send a prompt turn");
        assertEquals(1, countStatuses(events, StatusEventTypeEnum.BUSY),
                "a compact must report exactly one BUSY while it runs");
        assertEquals(1, closingStatusCount(events), "a finished compact must close exactly once");
        assertEquals("Conversation compacted — removed 40 tokens, 2 messages",
                status(events, StatusEventTypeEnum.READY).text(),
                "a successful compact must close with READY, not INFO");
    }

    @Test
    void compact_secondPressWhileTheFirstIsStillRunningDoesNotIssueASecondRpc() {
        // The busy guard (!isRunning() || isBusy()) keeps the first in-flight compact holding isBusy() true, so a
        // second press is refused before any RPC — it must never reach runWork (which would refuse anyway) or the
        // SDK's "Compaction already in progress" rejection, which used to surface as a JsonRpcException stack
        // trace and a FAILED status instead of a plain "already waiting" notice. The never-completed future is
        // what holds the first compact in flight across the second click.
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompactManager manager = new CompactManager(events::add, new CompletableFuture<>());
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);
        AiSession session = newSession("gh-compact-reentrant", settings);
        AiSessionHost host = stubHost(settings, new AtomicReference<>());

        clickCompact(impl, session, host);
        clickCompact(impl, session, host);

        assertEquals(1, manager.compactCalls, "the second press must not reach the RPC while the first is in flight");
        assertEquals("Wait for GitHub Copilot to finish before compacting",
                status(events, StatusEventTypeEnum.INFO).text());
        assertTrue(events.stream().noneMatch(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.FAILED),
                "a refused re-entry is not a failure and must not report one");
        assertEquals(1, countStatuses(events, StatusEventTypeEnum.BUSY),
                "the refused press must not open a second BUSY");
    }

    @Test
    void compact_busyWhileInFlightThenExactlyOneReadyOnSuccess() {
        // The UI contract: exactly one BUSY when the compact starts, and exactly one closing status (READY here)
        // when it ends. While the RPC is in flight there must be no closing status yet — a session whose compact
        // is still running is locked (isBusy()) against input and further compacts. The never-completed future
        // holds the compact open while the assertions on the in-flight state run.
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompletableFuture<SessionHistoryCompactResult> pending = new CompletableFuture<>();
        CompactManager manager = new CompactManager(events::add, pending);
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);

        clickCompact(impl, newSession("gh-compact-busy", settings), stubHost(settings, new AtomicReference<>()));
        assertEquals(1, countStatuses(events, StatusEventTypeEnum.BUSY),
                "a running compact must report exactly one BUSY");
        assertEquals(0, closingStatusCount(events), "a running compact must not report a closing status yet");

        pending.complete(new SessionHistoryCompactResult(true, 10L, 1L, null, null));
        assertEquals(1, closingStatusCount(events), "a completed compact must close with exactly one status");
        assertEquals("Conversation compacted — removed 10 tokens, 1 messages",
                status(events, StatusEventTypeEnum.READY).text());
    }

    @Test
    void compact_busyWhileInFlightThenExactlyOneFailedOnError() {
        // The case that matters most: a compact that errors must still close its BUSY — never leave the session
        // holding an open BUSY (an un-closable state once the RPC is gone) — and report FAILED with the detail.
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompletableFuture<SessionHistoryCompactResult> pending = new CompletableFuture<>();
        CompactManager manager = new CompactManager(events::add, pending);
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);

        clickCompact(impl, newSession("gh-compact-busy-fail", settings), stubHost(settings, new AtomicReference<>()));
        pending.completeExceptionally(new IllegalStateException("rpc exploded"));

        assertEquals(1, countStatuses(events, StatusEventTypeEnum.BUSY),
                "a compact that errored must still have opened its BUSY");
        assertEquals(1, closingStatusCount(events), "a failed compaction must close its BUSY exactly once");
        assertEquals("Compact failed: rpc exploded", status(events, StatusEventTypeEnum.FAILED).text());
    }

    @Test
    void compact_refusedByTheBusyGuardEmitsNoBusyAndDoesNotCallRpc() {
        // Refused by the guard before any RPC or runWork, so nothing is busy and nothing is in flight — no BUSY
        // is ever emitted, no closing status (there is no completion to close with), and no RPC is issued.
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompactManager manager = new CompactManager(events::add, new CompletableFuture<>());
        manager.setState(true, true);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);

        clickCompact(impl, newSession("gh-compact-busy-guard", settings), stubHost(settings, new AtomicReference<>()));

        assertEquals(0, manager.compactCalls);
        assertEquals(0, countStatuses(events, StatusEventTypeEnum.BUSY),
                "a refused compact must not open a BUSY that nothing would ever close");
        assertEquals(0, closingStatusCount(events));
    }

    @Test
    void compact_falseSuccessReportsFailedAndNotCompacted() {
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompactManager manager = new CompactManager(events::add, CompletableFuture.completedFuture(
                new SessionHistoryCompactResult(false, null, null, null, null)));
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);

        clickCompact(impl, newSession("gh-compact-false", settings), stubHost(settings, new AtomicReference<>()));

        assertEquals(1, manager.compactCalls);
        assertEquals("Compact failed: Copilot did not compact the conversation",
                status(events, StatusEventTypeEnum.FAILED).text());
        assertTrue(events.stream().noneMatch(e -> e instanceof StatusEvent se && se.text().contains("Conversation compacted")));
        assertEquals(1, closingStatusCount(events));
    }

    @Test
    void compactRpcFailureReportsFailedWithErrorDetail() {
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompactManager manager = new CompactManager(events::add, CompletableFuture.failedFuture(new IllegalStateException("rpc exploded")));
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);

        clickCompact(impl, newSession("gh-compact-error", settings), stubHost(settings, new AtomicReference<>()));

        assertEquals("Compact failed: rpc exploded", status(events, StatusEventTypeEnum.FAILED).text());
    }

    @Test
    void compact_nothingToCompactIsReadyNotFailed() {
        // The SDK's "Nothing to compact." is a harmless outcome, not an error — it must surface as READY
        // (never FAILED), and the BUSY it opened must still close exactly once: no FAILED, no stuck BUSY.
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompactManager manager = new CompactManager(events::add,
                CompletableFuture.failedFuture(new IllegalStateException("Nothing to compact.")));
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);

        clickCompact(impl, newSession("gh-compact-nothing", settings), stubHost(settings, new AtomicReference<>()));

        assertEquals(1, closingStatusCount(events),
                "a 'Nothing to compact.' result must close its BUSY exactly once");
        assertEquals("Nothing to compact.", status(events, StatusEventTypeEnum.READY).text());
        assertTrue(events.stream().noneMatch(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.FAILED),
                "a harmless 'Nothing to compact.' must not be reported as FAILED");
    }

    @Test
    void compact_nullResultReportsFailedNotCompacted() {
        // Fix #4: a null compact result (the RPC resolved but reported nothing) is not a success — the BUSY must
        // close exactly once, as FAILED, not left hanging and never as READY.
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompactManager manager = new CompactManager(events::add, CompletableFuture.completedFuture(null));
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);

        clickCompact(impl, newSession("gh-compact-null", settings), stubHost(settings, new AtomicReference<>()));

        assertEquals(1, manager.compactCalls);
        assertEquals(1, closingStatusCount(events));
        assertEquals("Compact failed: Copilot did not compact the conversation",
                status(events, StatusEventTypeEnum.FAILED).text());
    }

    @Test
    void compact_timeoutReportsTimedOutAndAbortsTheRpc() throws Exception {
        // Fix #5: a compaction that never completes must still close its BUSY — exactly once — as FAILED with a
        // plain-language message (not "Compact failed: java.util.concurrent.TimeoutException"), and must abort the
        // still-running RPC through the SDK's public abortManualCompaction. The short timeout is injected via the
        // overridable seam so the test does not wait the production five minutes.
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<StatusEvent> closing = new AtomicReference<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof StatusEvent s && (s.type() == StatusEventTypeEnum.READY
                                                   || s.type() == StatusEventTypeEnum.FAILED || s.type() == StatusEventTypeEnum.EXITED)) {
                closing.set(s);
                closed.countDown();
            }
        };
        CompactManager manager = new CompactManager(listener, new CompletableFuture<>());
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(listener, null, manager) {
            @Override
            long compactTimeoutMillis() {
                return 100L;
            }
        };

        clickCompact(impl, newSession("gh-compact-timeout", settings), stubHost(settings, new AtomicReference<>()));

        assertTrue(closed.await(5, TimeUnit.SECONDS), "the timed-out compaction must be closed");
        assertEquals(StatusEventTypeEnum.FAILED, closing.get().type());
        assertEquals("Compact timed out", closing.get().text());
        assertEquals(1, manager.aborts.get(), "a timed-out compaction must be aborted via the SDK's public API");
        assertEquals(1, closingStatusCount(events));
    }

    @Test
    void stopWhileCompactionInFlight_closesItAsFailedExactlyOnce() {
        // Fix #4: the stop path is proven against compact() itself, not a generic runWork lambda, so the real
        // non-turn work the plugin starts is the work being closed.
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompletableFuture<SessionHistoryCompactResult> pending = new CompletableFuture<>();
        CompactManager manager = new CompactManager(events::add, pending);
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);

        clickCompact(impl, newSession("gh-compact-stop", settings), stubHost(settings, new AtomicReference<>()));
        assertEquals(1, countStatuses(events, StatusEventTypeEnum.BUSY));

        manager.stop();

        assertFalse(manager.isWorkInFlight(), "stop must close the compaction it was given");
        assertEquals(1, countStatuses(events, StatusEventTypeEnum.FAILED));
        assertEquals(1, closingStatusCount(events));

        pending.complete(new SessionHistoryCompactResult(true, 1L, 1L, null, null));
        assertEquals(1, closingStatusCount(events), "a late completion must not close the compaction a second time");
    }

    @Test
    void compactRunningOrProcessingGuardReportsInfoAndDoesNotCallRpc() {
        for (boolean running : new boolean[]{false, true}) {
            for (boolean processing : new boolean[]{false, true}) {
                if (running && !processing) {
                    continue;
                }
                List<AiProcessEvent> events = new java.util.ArrayList<>();
                CompactManager manager = new CompactManager(events::add, CompletableFuture.completedFuture(null));
                manager.setState(running, processing);
                GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
                GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);

                clickCompact(impl, newSession("gh-compact-guard", settings), stubHost(settings, new AtomicReference<>()));

                assertEquals(0, manager.compactCalls, "guard must prevent the RPC: running=" + running
                                                      + ", processing=" + processing);
                assertEquals("Wait for GitHub Copilot to finish before compacting",
                        status(events, StatusEventTypeEnum.INFO).text());
            }
        }
    }

    @Test
    void compactCompletionMessage_reportsOnlyCountsReturnedByTheRpc() {
        SessionHistoryCompactResult withCounts = new SessionHistoryCompactResult(true, 123L, 4L, null, null);
        assertEquals("Conversation compacted — removed 123 tokens, 4 messages",
                GithubCopilotAiImplementation.compactCompletionMessage(withCounts));

        SessionHistoryCompactResult withoutCounts = new SessionHistoryCompactResult(true, null, null, "summary", null);
        assertEquals("Conversation compacted",
                GithubCopilotAiImplementation.compactCompletionMessage(withoutCounts));
    }

    @Test
    void handleReasoningEffortCleared_showsModelDefaultNotGlobalDefaultWhenModelSupportsNoEfforts() throws Exception {
        // Negative control for the test above: proves the "never show an effort the model doesn't support" rule is
        // genuinely enforced, not just coincidentally satisfied. With no live discovery data for the current
        // model, the combo must show "(model default)" —
        // i.e. null — even though a global default IS configured, never the global default itself. Flushed through
        // the same EDT wait as the positive case above: without it, this would pass even if the rule were NOT
        // enforced, since the queued-but-not-yet-run update also reads back as null.
        String globalBefore = GithubCopilotPluginSettings.getReasoningEffort();
        GithubCopilotPluginSettings.setReasoningEffort("medium");
        try {
            GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
            settings.setReasoningEffort("high");
            AiSession session = newSession("gh-cleared-4", settings);
            GithubCopilotAiImplementation impl = implFor(session);
            AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
            GithubCopilotAiInfoBarExtension ext = impl.createInfoBarExtension(session, stubHost(settings, updated));
            // No setModelReasoningEffortInfo call — the per-model cache is empty for whatever model this combo is
            // showing, i.e. discovery has not (yet) reported support for anything.

            impl.handleReasoningEffortCleared();
            SwingUtilities.invokeAndWait(() -> {
            });

            assertNull(ext.getSelectedReasoningEffort(),
                    "with no live discovery data for the current model, the combo must show \"(model default)\" "
                    + "— never the global default, even though one is configured");
        }
        finally {
            GithubCopilotPluginSettings.setReasoningEffort(globalBefore);
        }
    }

}

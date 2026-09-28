package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.generated.rpc.SessionHistoryCompactResult;
import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotQuotaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.ui.GithubCopilotAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        private final List<String> prompts = new java.util.ArrayList<>();

        CompactManager(CompletableFuture<SessionHistoryCompactResult> compactResult) {
            super(e -> {
            });
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

    /**
     * {@link #stubHost} plus a record of every {@code setCompacting} call, in order — the only observable
     * signal that the input was blocked and released, since the real input field lives in AiTopComponent.
     */
    private static AiSessionHost recordingHost(AiSessionSettings settings, List<Boolean> compacting) {
        AiSessionHost base = stubHost(settings, new AtomicReference<>());
        return new AiSessionHost() {
            @Override
            public File resolveWorkDir() {
                return base.resolveWorkDir();
            }

            @Override
            public void suppressNextTurn(String statusMessage, String completionMessage) {
                base.suppressNextTurn(statusMessage, completionMessage);
            }

            @Override
            public AiSessionSettings getSessionSettings() {
                return base.getSessionSettings();
            }

            @Override
            public void updateSessionSettings(AiSessionSettings newSettings) {
                base.updateSessionSettings(newSettings);
            }

            @Override
            public void setCompacting(boolean value) {
                compacting.add(value);
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
        } finally {
            bus.removeListener(AiTypeEnum.GitHubCoPilot, first);
            bus.removeListener(AiTypeEnum.GitHubCoPilot, second);
        }
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
        } finally {
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
        } finally {
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
        } finally {
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
        } finally {
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
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(
                    Map.of(ext.getSelectedModel(), List.of("low", "medium")), Map.of());

            impl.handleReasoningEffortCleared();
            // handleReasoningEffortCleared's setSelectedReasoningEffort call routes through
            // refreshReasoningEffortOptions, which defers to the EDT via invokeLater when called off it (as this
            // test thread is) — flush the queue before asserting, or the getter reads pre-update state.
            SwingUtilities.invokeAndWait(() -> {
            });

            assertEquals("medium", ext.getSelectedReasoningEffort(),
                    "the info bar must reflect the clear immediately, falling back to the global default for "
                    + "display rather than keeping the just-cleared value visible");
        } finally {
            GithubCopilotPluginSettings.setReasoningEffort(globalBefore);
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
        }
    }

    @Test
    void compact_callsRpcExactlyOnceAndNeverSendsPrompt() {
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompactManager manager = new CompactManager(CompletableFuture.completedFuture(
                new SessionHistoryCompactResult(true, 40L, 2L, null, null)));
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);
        AiSession session = newSession("gh-compact-rpc", settings);

        clickCompact(impl, session, stubHost(settings, new AtomicReference<>()));

        assertEquals(1, manager.compactCalls);
        assertTrue(manager.prompts.isEmpty(), "real compaction must not send a prompt turn");
        assertEquals("Conversation compacted — removed 40 tokens, 2 messages", status(events, StatusEventTypeEnum.INFO).text());
    }

    @Test
    void compact_secondPressWhileTheFirstIsStillRunningDoesNotIssueASecondRpc() {
        // The isRunning()/isProcessing() guard cannot catch this: session.history.compact is an RPC, not a turn, so
        // processing stays false for its whole duration, and the Copilot info bar never disables its Compact button.
        // Chris hit this live — the second press reached the SDK, which rejected it with
        // "JsonRpcException: Request session.history.compact failed with message: Compaction already in progress",
        // surfacing to the user as a stack trace and a FAILED status instead of a plain "already running" notice.
        // The never-completed future is what holds the first compact in flight across the second click.
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompactManager manager = new CompactManager(new CompletableFuture<>());
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);
        AiSession session = newSession("gh-compact-reentrant", settings);
        AiSessionHost host = stubHost(settings, new AtomicReference<>());

        clickCompact(impl, session, host);
        clickCompact(impl, session, host);

        assertEquals(1, manager.compactCalls, "the second press must not reach the RPC while the first is in flight");
        assertEquals("Compaction already in progress", status(events, StatusEventTypeEnum.INFO).text());
        assertTrue(events.stream().noneMatch(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.FAILED),
                "a refused re-entry is not a failure and must not report one");
    }

    @Test
    void compact_blocksInputWhileInFlightAndReleasesItOnSuccess() {
        // session.history.compact is an RPC, not a turn, so isProcessing() stays false throughout and nothing else
        // blocks the input — without setCompacting a prompt could be sent into a session mid-compaction.
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompletableFuture<SessionHistoryCompactResult> pending = new CompletableFuture<>();
        CompactManager manager = new CompactManager(pending);
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);
        List<Boolean> compacting = new java.util.ArrayList<>();

        clickCompact(impl, newSession("gh-compact-busy", settings), recordingHost(settings, compacting));
        assertEquals(List.of(true), compacting, "input must be blocked for as long as the RPC is in flight");

        pending.complete(new SessionHistoryCompactResult(true, 10L, 1L, null, null));
        assertEquals(List.of(true, false), compacting, "input must be released once the compaction completes");
    }

    @Test
    void compact_releasesInputWhenTheRpcFails() {
        // The case that matters most: a compaction that errors and leaves the input locked would strand the session
        // worse than the bug this blocking exists to fix.
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompletableFuture<SessionHistoryCompactResult> pending = new CompletableFuture<>();
        CompactManager manager = new CompactManager(pending);
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);
        List<Boolean> compacting = new java.util.ArrayList<>();

        clickCompact(impl, newSession("gh-compact-busy-fail", settings), recordingHost(settings, compacting));
        pending.completeExceptionally(new IllegalStateException("rpc exploded"));

        assertEquals(List.of(true, false), compacting, "a failed compaction must not leave the input locked");
        assertEquals("Compact failed: rpc exploded", status(events, StatusEventTypeEnum.FAILED).text());
    }

    @Test
    void compact_refusedByTheTurnGuardNeverBlocksInput() {
        // Refused before any RPC is issued, so there is no completion to release a block — locking here would be
        // permanent.
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompactManager manager = new CompactManager(new CompletableFuture<>());
        manager.setState(true, true);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);
        List<Boolean> compacting = new java.util.ArrayList<>();

        clickCompact(impl, newSession("gh-compact-busy-guard", settings), recordingHost(settings, compacting));

        assertTrue(compacting.isEmpty(), "a refused compact has nothing to release it, so it must never block input");
    }

    @Test
    void compact_falseSuccessReportsFailedAndNotCompacted() {
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompactManager manager = new CompactManager(CompletableFuture.completedFuture(
                new SessionHistoryCompactResult(false, null, null, null, null)));
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);

        clickCompact(impl, newSession("gh-compact-false", settings), stubHost(settings, new AtomicReference<>()));

        assertEquals(1, manager.compactCalls);
        assertEquals(StatusEventTypeEnum.FAILED, status(events, StatusEventTypeEnum.FAILED).type());
        assertTrue(events.stream().noneMatch(e -> e instanceof StatusEvent se && se.text().contains("Conversation compacted")));
    }

    @Test
    void compactRpcFailureReportsFailedWithErrorDetail() {
        List<AiProcessEvent> events = new java.util.ArrayList<>();
        CompactManager manager = new CompactManager(CompletableFuture.failedFuture(new IllegalStateException("rpc exploded")));
        manager.setState(true, false);
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(events::add, null, manager);

        clickCompact(impl, newSession("gh-compact-error", settings), stubHost(settings, new AtomicReference<>()));

        assertEquals("Compact failed: rpc exploded", status(events, StatusEventTypeEnum.FAILED).text());
    }

    @Test
    void compactRunningOrProcessingGuardReportsInfoAndDoesNotCallRpc() {
        for (boolean running : new boolean[]{false, true}) {
            for (boolean processing : new boolean[]{false, true}) {
                if (running && !processing) {
                    continue;
                }
                List<AiProcessEvent> events = new java.util.ArrayList<>();
                CompactManager manager = new CompactManager(CompletableFuture.completedFuture(null));
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
        } finally {
            GithubCopilotPluginSettings.setReasoningEffort(globalBefore);
        }
    }

}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotFatalErrorEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotReasoningEffortClearedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotCompaction;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotEventSink;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotMcpServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotQuotaSnapshot;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSessionSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class GithubCopilotProcessManagerTest {

    private static final String SESSION_ID = "plugin-session-123";

    @Test
    void sessionMcpUrlUsesRoutingIdForCopilot() throws Exception {
        McpServerRegistry.portOverride = 0;
        assertTrue(McpServerRegistry.register(new GithubCopilotMcpRegistrar("copilot-url-test")).get(5, TimeUnit.SECONDS));
        try {
            String endpoint = McpServerRegistry.endpointUrlFor(AiTypeEnum.GitHubCoPilot, "route-1234");
            assertNotNull(endpoint);
            assertTrue(endpoint.endsWith("/mcp/github_copilot/route-1234"), endpoint);
        }
        finally {
            McpServerRegistry.stopAll();
            McpServerRegistry.portOverride = null;
        }
    }

    @Test
    void buildSessionSpecPinsStableSessionId() {
        CopilotSessionSpec spec = GithubCopilotProcessManager.buildSessionSpec(
                SESSION_ID, "gpt-5.4", Map.of(), new GithubCopilotPermissionHandler(event -> {
        }, SESSION_ID), null);

        assertEquals(SESSION_ID, spec.sessionId());
        assertEquals("gpt-5.4", spec.model());
        assertTrue(spec.excludedTools().contains("edit"));
        assertTrue(spec.excludedTools().contains("create"));
        // Withheld so Copilot never reaches for them: the permission gate would
        // refuse each one and post a system message, which a single survey turned
        // into six. bash stays available — running commands has no plugin
        // equivalent, so it is gated by confirmation rather than withheld.
        assertTrue(spec.excludedTools().contains("glob"));
        assertTrue(spec.excludedTools().contains("view"));
        assertFalse(spec.excludedTools().contains("bash"));
    }

    @Test
    void buildSessionSpecPassesTheMcpServersTheGateAndTheAuthCancelThrough() {
        GithubCopilotPermissionHandler gate = new GithubCopilotPermissionHandler(event -> {
        }, SESSION_ID);
        CopilotMcpServer server = new CopilotMcpServer("http://127.0.0.1:1234/mcp/github_copilot/route-1", List.of("*"), 4321);

        CopilotSessionSpec spec = GithubCopilotProcessManager.buildSessionSpec(
                SESSION_ID, "gpt-5.4", Map.of("plugin", server), gate, null);

        assertEquals(Map.of("plugin", server), spec.mcpServers());
        CopilotMcpServer passed = spec.mcpServers().get("plugin");
        assertEquals("http://127.0.0.1:1234/mcp/github_copilot/route-1", passed.url());
        assertEquals(List.of("*"), passed.tools());
        assertEquals(4321, passed.timeoutMillis());
        assertSame(gate, spec.gate());
        assertTrue(spec.cancelMcpAuth(), "an MCP OAuth request cannot be serviced here, so it is always cancelled");
    }

    @Test
    void buildSessionSpecOmitsReasoningEffortWhenNull() {
        CopilotSessionSpec spec = GithubCopilotProcessManager.buildSessionSpec(
                SESSION_ID, "gpt-5.4", Map.of(), new GithubCopilotPermissionHandler(event -> {
        }, SESSION_ID), null);

        assertNull(spec.reasoningEffort());
    }

    @Test
    void buildSessionSpecOmitsReasoningEffortWhenBlank() {
        CopilotSessionSpec spec = GithubCopilotProcessManager.buildSessionSpec(
                SESSION_ID, "gpt-5.4", Map.of(), new GithubCopilotPermissionHandler(event -> {
        }, SESSION_ID), "  ");

        assertNull(spec.reasoningEffort(), "a blank effort is omitted, never sent as an empty string");
    }

    @Test
    void buildSessionSpecSendsExactReasoningEffortWhenSet() {
        CopilotSessionSpec spec = GithubCopilotProcessManager.buildSessionSpec(
                SESSION_ID, "gpt-5.4", Map.of(), new GithubCopilotPermissionHandler(event -> {
        }, SESSION_ID), "high");

        assertEquals("high", spec.reasoningEffort());
    }

    @Test
    void buildSessionSpecSendsXhighReasoningEffortUnchanged() {
        CopilotSessionSpec spec = GithubCopilotProcessManager.buildSessionSpec(
                SESSION_ID, "gpt-5.4", Map.of(), new GithubCopilotPermissionHandler(event -> {
        }, SESSION_ID), "xhigh");

        assertEquals("xhigh", spec.reasoningEffort());
    }

    @Test
    void resolveValidatedReasoningEffortLeavesUnsetValueAlone() {
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);

        assertNull(mgr.resolveValidatedReasoningEffort("gpt-5.4"));
        assertTrue(events.isEmpty());
    }

    @Test
    void resolveValidatedReasoningEffortReturnsSupportedValueUnchanged() {
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        mgr.setReasoningEffort("high");
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(
                Map.of("gpt-5.4", List.of("low", "high")), Map.of());

        try {
            assertEquals("high", mgr.resolveValidatedReasoningEffort("gpt-5.4"));
            assertTrue(events.isEmpty());
        }
        finally {
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
        }
    }

    @Test
    void resolveValidatedReasoningEffortClearsUnsupportedValueWithExactlyOneInfoEvent() {
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        mgr.setReasoningEffort("high");
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(
                Map.of("gpt-5.4", List.of("low", "medium")), Map.of());

        try {
            assertNull(mgr.resolveValidatedReasoningEffort("gpt-5.4"));
            assertEquals(2, events.size());
            assertEquals(StatusEventTypeEnum.INFO, ((StatusEvent) events.get(0)).type());
            assertTrue(events.get(1) instanceof GithubCopilotReasoningEffortClearedEvent,
                       "the clear must reach the property channel after the INFO event");
            // Second call must not fire another event: the value is already cleared.
            assertNull(mgr.resolveValidatedReasoningEffort("gpt-5.4"));
            assertEquals(2, events.size());
        }
        finally {
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
        }
    }

    @Test
    void resolveValidatedReasoningEffortClearsWhenModelIsUnknown() {
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        mgr.setReasoningEffort("high");

        assertNull(mgr.resolveValidatedReasoningEffort("some-unknown-model"));
        assertEquals(2, events.size());
        assertEquals(StatusEventTypeEnum.INFO, ((StatusEvent) events.get(0)).type());
        assertTrue(events.get(1) instanceof GithubCopilotReasoningEffortClearedEvent);
    }

    @Test
    void resolveValidatedReasoningEffortInvokesClearedCallbackExactlyOnceWhenClearing() {
        // This manager has no session-settings/host reference of its own — clearing the in-memory field alone would
        // never persist the clear, so the stored value would come back and re-fire the INFO event on every future
        // start. GithubCopilotAiImplementation relies on this callback to persist the clear; verify it fires exactly
        // once per genuine clear.
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        mgr.setReasoningEffort("high");
        AtomicInteger clearedCount = new AtomicInteger();
        mgr.setOnReasoningEffortCleared(clearedCount::incrementAndGet);

        mgr.resolveValidatedReasoningEffort("some-unknown-model");
        assertEquals(1, clearedCount.get());

        // Second call: already cleared, so no second INFO event AND no second callback invocation.
        mgr.resolveValidatedReasoningEffort("some-unknown-model");
        assertEquals(1, clearedCount.get());
    }

    @Test
    void resolveValidatedReasoningEffortEmitsClearEventWithoutCallback() {
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        mgr.setReasoningEffort("high");

        assertNull(mgr.resolveValidatedReasoningEffort("some-unknown-model"));

        assertTrue(events.stream().anyMatch(GithubCopilotReasoningEffortClearedEvent.class::isInstance),
                   "the manager must emit the clear event even when no persistence callback is installed");
    }

    @Test
    void resolveValidatedReasoningEffortEmitsClearEventWhenCallbackThrows() {
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        mgr.setReasoningEffort("high");
        mgr.setOnReasoningEffortCleared(() -> {
            throw new IllegalStateException("persistence failed");
        });

        assertNull(mgr.resolveValidatedReasoningEffort("some-unknown-model"));

        assertTrue(events.stream().anyMatch(GithubCopilotReasoningEffortClearedEvent.class::isInstance),
                   "a persistence callback failure must not suppress the clear event");
    }

    @Test
    void resolveValidatedReasoningEffortDoesNotInvokeClearedCallbackWhenSupported() {
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        mgr.setReasoningEffort("high");
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of("gpt-5.4", List.of("low", "high")), Map.of());
        AtomicInteger clearedCount = new AtomicInteger();
        mgr.setOnReasoningEffortCleared(clearedCount::incrementAndGet);

        try {
            mgr.resolveValidatedReasoningEffort("gpt-5.4");
            assertEquals(0, clearedCount.get());
        }
        finally {
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
        }
    }

    @Test
    void resolveValidatedReasoningEffortOmitsGlobalSourcedUnsupportedValueWithoutInfoOrClear() {
        // Rule 3a: a value inherited from the GLOBAL default belongs to the user and to every other session — one
        // session's model not supporting it is silently omitted (FINE log), never cleared, never persisted-with, and
        // never reported. The value must stay in place so a model that does support it still receives it later.
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        mgr.setReasoningEffort("high", false);
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(
                Map.of("gpt-5.4", List.of("low", "medium")), Map.of());
        AtomicInteger clearedCount = new AtomicInteger();
        mgr.setOnReasoningEffortCleared(clearedCount::incrementAndGet);

        try {
            assertNull(mgr.resolveValidatedReasoningEffort("gpt-5.4"));
            assertTrue(events.isEmpty(), "a global-sourced unsupported value must not fire any event");
            assertEquals(0, clearedCount.get(), "a global-sourced value must not be cleared");
            // The global value survives for a model that does support it — same field worn by both branches.
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(
                    Map.of("gpt-5.4", List.of("low", "medium"), "gpt-6", List.of("high")), Map.of());
            assertEquals("high", mgr.resolveValidatedReasoningEffort("gpt-6"),
                         "a global-sourced value must still be applied for a model that supports it");
            assertTrue(events.isEmpty(), "a supported global-sourced value must fire no events");
        }
        finally {
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
        }
    }

    @Test
    void openSession_resumeFailureFallsThroughToCreate_firesExactlyOneInfo() throws Exception {
        // The exactly-one-INFO guarantee must hold end to end, not just in the unit method: a stored-but-unsupported
        // session-pinned value is validated ONCE per session open (in its plan), so when the resume attempt fails and
        // the call falls through to create, the INFO fires once, and neither spec may carry the unsupported value.
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        mgr.setReasoningEffort("high");
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of("gpt-5.4", List.of("low", "medium")), Map.of());
        AtomicInteger clearedCount = new AtomicInteger();
        mgr.setOnReasoningEffortCleared(clearedCount::incrementAndGet);
        set(mgr, "copilotSessionId", SESSION_ID);
        FakeClient client = new FakeClient(List.of(SESSION_ID), true);

        try {
            createOrResume(mgr, client, "gpt-5.4");

            // This manager has no session and no MCP server running, so buildMcpServers also reports that the plugin
            // tools are unavailable. That notice is about something else; the guarantee under test is about the
            // reasoning-effort INFO, so it is excluded from the count (and asserted separately, once).
            String mcpUnavailable = StatusMessageUtil.formatMcpToolsUnavailable("GitHub Copilot");
            assertEquals(1, events.stream()
                         .filter(e -> e instanceof StatusEvent && ((StatusEvent) e).type() == StatusEventTypeEnum.INFO)
                         .filter(e -> !mcpUnavailable.equals(((StatusEvent) e).text()))
                         .count(),
                         () -> "exactly one reasoning-effort INFO event expected, but got: " + events);
            assertEquals(1, events.stream()
                         .filter(e -> e instanceof StatusEvent && mcpUnavailable.equals(((StatusEvent) e).text()))
                         .count(),
                         () -> "the MCP-unavailable notice is emitted once per session open: " + events);
            assertEquals(1, clearedCount.get());
            assertEquals(List.of("resume", "create"), client.calls, "resume is tried first, then the fall-through create");
            assertNotNull(client.resumeSpec, "resume attempt must have been made");
            assertNotNull(client.createSpec, "fall-through must have created after the resume failure");
            assertNull(client.resumeSpec.reasoningEffort(), "cleared session-pinned value must not be sent on resume");
            assertNull(client.createSpec.reasoningEffort(), "cleared session-pinned value must not be sent on create");
        }
        finally {
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
        }
    }

    @Test
    void createAndResumeSpecsBothCarryTheModelExcludedToolsGateAndAuthCancel() throws Exception {
        // Resume used to omit the MCP-auth cancel handler; one spec now serves both calls, so both must carry it.
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(event -> {
        });
        set(mgr, "copilotSessionId", SESSION_ID);
        FakeClient client = new FakeClient(List.of(SESSION_ID), true);

        createOrResume(mgr, client, "gpt-5.4");

        for (CopilotSessionSpec spec : List.of(client.resumeSpec, client.createSpec)) {
            assertNotNull(spec);
            assertEquals(SESSION_ID, spec.sessionId());
            assertEquals("gpt-5.4", spec.model());
            assertTrue(spec.excludedTools().containsAll(List.of("edit", "create", "glob", "view")));
            assertFalse(spec.excludedTools().contains("bash"));
            assertNotNull(spec.gate(), "the permission gate must be attached");
            assertTrue(spec.cancelMcpAuth(), "the MCP-auth cancel must be set on create AND resume");
        }
        assertSame(client.resumeSpec.gate(), client.createSpec.gate(),
                   "the fall-through create reuses the same permission gate as the resume attempt");
    }

    @Test
    void aStoredSessionIsResumedWithTheSupportedEffortAndNoCreate() throws Exception {
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(event -> {
        });
        mgr.setReasoningEffort("high");
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of("gpt-5.4", List.of("low", "high")), Map.of());
        set(mgr, "copilotSessionId", SESSION_ID);
        FakeClient client = new FakeClient(List.of(SESSION_ID), false);

        try {
            createOrResume(mgr, client, "gpt-5.4");

            assertEquals(List.of("resume"), client.calls, "a stored session that resumes is never also created");
            assertEquals("high", client.resumeSpec.reasoningEffort());
            assertTrue(client.resumeSpec.cancelMcpAuth());
            assertNull(client.createSpec);
        }
        finally {
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
        }
    }

    @Test
    void aSessionThatIsNotStoredIsCreatedWithoutAResumeAttempt() throws Exception {
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(event -> {
        });
        set(mgr, "copilotSessionId", SESSION_ID);
        FakeClient client = new FakeClient(List.of("some-other-session"), false);

        createOrResume(mgr, client, "gpt-5.4");

        assertEquals(List.of("create"), client.calls, "an id the store does not list is created, not resumed");
        assertNull(client.resumeSpec);
        assertEquals(SESSION_ID, client.createSpec.sessionId());
        assertTrue(client.createSpec.cancelMcpAuth());
    }

    @Test
    void sessionListContainsMatchesOnlyKnownSessionIds() {
        List<String> sessions = List.of("keep-me", "other");

        assertTrue(GithubCopilotProcessManager.sessionListContains(sessions, "keep-me"));
        assertFalse(GithubCopilotProcessManager.sessionListContains(sessions, "missing"));
        assertFalse(GithubCopilotProcessManager.sessionListContains(sessions, " "));
        assertFalse(GithubCopilotProcessManager.sessionListContains(null, "keep-me"));
        assertFalse(GithubCopilotProcessManager.sessionListContains(sessions, null));
    }

    @Test
    void isSessionNotFoundFailureDetectsNestedCopilotRpcErrors() {
        Throwable failure = new CompletionException(
                new IllegalStateException("Request session.resume failed with message: Session not found: abc"));

        assertTrue(GithubCopilotProcessManager.isSessionNotFoundFailure(failure));
        assertFalse(GithubCopilotProcessManager.isSessionNotFoundFailure(
                new CompletionException(new IllegalStateException("authentication required"))));
    }

    @Test
    void runtimeErrorThroughTheSessionBridge_surfacesAsFailedNotExited() throws Exception {
        // Drives the failure through the real wiring the bridge uses (onError -> handleRuntimeError) instead of
        // calling handleRuntimeError directly, so reverting that wiring breaks this test. The fake session hands the
        // sink the bridge subscribed with back to the test, which then reports a session error as the SDK would.
        // A mid-turn runtime failure (quota, rate limit, network) must surface as exactly one FAILED — never
        // EXITED, which is reserved for the process actually dying (this SDK-based backend owns none) — and
        // must clear processing so the next turn can start.
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        set(mgr, "processing", true);
        assertTrue(mgr.isBusy(), "a turn in flight makes the manager busy");

        FakeSession session = new FakeSession(SESSION_ID);
        mgr.createEventBridge(events::add).attach(session);
        assertNotNull(session.sink, "the bridge must subscribe to the session");
        session.sink.onSessionError("rate limited");

        assertFalse(mgr.isBusy(), "a runtime failure must clear the in-flight turn");
        assertEquals(1, events.size(), "a runtime failure must report exactly one status");
        StatusEvent status = (StatusEvent) events.get(0);
        assertEquals(StatusEventTypeEnum.FAILED, status.type(),
                     "a runtime failure must be FAILED, never EXITED");
        assertEquals("GitHub Copilot: rate limited", status.text());
    }

    @Test
    void runtimeErrorFromABridgeOfAnEarlierGeneration_isIgnored() throws Exception {
        // A late session error from a client already torn down must not close the next run's turn or post a stale
        // FAILED: the bridge's error path belongs to the generation it was built in.
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        FakeSession session = new FakeSession(SESSION_ID);
        mgr.createEventBridge(events::add).attach(session);

        mgr.stop();
        set(mgr, "processing", true);
        events.clear();
        session.sink.onSessionError("late error from the old client");

        assertTrue(mgr.isBusy(), "the current turn must not be cleared by an earlier generation's error");
        assertTrue(events.isEmpty(), () -> "an earlier generation's error must report nothing: " + events);
    }

    @Test
    void startWithAMissingExecutable_raisesExecutableNotFound() {
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);

        mgr.start("/no/such/dir/copilot", "gpt-5.4");

        GithubCopilotFatalErrorEvent fatal = events.stream()
                .filter(GithubCopilotFatalErrorEvent.class::isInstance)
                .map(GithubCopilotFatalErrorEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no fatal error event: " + events));
        assertEquals("EXECUTABLE_NOT_FOUND", fatal.errorType());
        assertEquals("GitHub Copilot CLI not found", fatal.errorMessage());
        assertEquals(1, countStatuses(events, StatusEventTypeEnum.FAILED), () -> "events were: " + events);
        assertFalse(mgr.isRunning());
    }

    @Test
    void runtimeErrorMidCompaction_closesInFlightWorkExactlyOnce() throws Exception {
        // Fix #1: a compaction is non-turn work that makes a model call, so a runtime failure for it must close
        // the work through its own token — exactly one FAILED — instead of emitting a second FAILED that would
        // leave runWork's later closer (FAILED or even READY) to paint the tab over the error.
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        CompletableFuture<String> pending = new CompletableFuture<>();
        assertTrue(mgr.runWork("Compacting conversation...", false, 60_000L,
                               () -> pending,
                               r -> new StatusEvent(StatusEventTypeEnum.READY, "done"),
                               err -> new StatusEvent(StatusEventTypeEnum.FAILED, "boom")),
                   "runWork must accept the compact while nothing else is in flight");
        assertEquals(1, countStatuses(events, StatusEventTypeEnum.BUSY));

        mgr.handleRuntimeError("rate limited");

        assertFalse(mgr.isWorkInFlight(), "the runtime failure must close the in-flight work");
        assertEquals(1, countStatuses(events, StatusEventTypeEnum.FAILED),
                     "a mid-compaction failure must report exactly one FAILED");
        assertEquals("GitHub Copilot: rate limited", status(events, StatusEventTypeEnum.FAILED).text());
        assertEquals(1, closingStatusCount(events), "the work must be closed exactly once");

        pending.complete("late");
        assertEquals(1, closingStatusCount(events),
                     "a late completion of the failed work must not close it a second time");
    }

    @Test
    void runtimeErrorOnTurn_swallowsTheTrailingIdleSoTheTabIsNotReGreened() throws Exception {
        // Fix #2: after handleRuntimeError closes a running turn as FAILED, the SDK still emits that turn's
        // session.idle; surfacing it would re-green the tab (and unlock a new turn the user has since sent).
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        set(mgr, "processing", true);
        AiProcessEventListener wire = mgr.createTurnAwareListener();

        mgr.handleRuntimeError("rate limited");
        wire.onAiProcessEvent(new TurnCompleteEvent());

        assertEquals(1, countStatuses(events, StatusEventTypeEnum.FAILED));
        assertEquals(0, countTurnCompletes(events),
                     "the trailing idle after a FAILED close must not re-green the tab");

        // The drop is one-shot: a genuinely later turn completion is still delivered.
        wire.onAiProcessEvent(new TurnCompleteEvent());
        assertEquals(1, countTurnCompletes(events), "only the next idle is dropped");
    }

    @Test
    void idleWhileCompactionInFlight_isSwallowed() throws Exception {
        // Fix #6: a manual history.compact is non-turn work; an idle the SDK emits for it is not a turn
        // completion and must not unlock the UI mid-compaction.
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        CompletableFuture<String> pending = new CompletableFuture<>();
        assertTrue(mgr.runWork("Compacting conversation...", false, 60_000L,
                               () -> pending,
                               r -> new StatusEvent(StatusEventTypeEnum.READY, "done"),
                               err -> new StatusEvent(StatusEventTypeEnum.FAILED, "boom")));
        AiProcessEventListener wire = mgr.createTurnAwareListener();

        wire.onAiProcessEvent(new TurnCompleteEvent());

        assertEquals(0, countTurnCompletes(events),
                     "an idle emitted while a compaction is in flight must not surface as a turn complete");
        pending.completeExceptionally(new IllegalStateException("cleanup"));
    }

    @Test
    void sendPromptRefusedForPendingDiff_namesTheBlockerAndReleasesTheUi() throws Exception {
        // Fix #3: handleSubmit locks the UI before calling sendPrompt, so a silent return would leave it locked
        // forever. Each refusal names its blocker with an INFO; when nothing else will close that lock, the
        // refusal must also release it with a turn-complete.
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        set(mgr, "pendingDiff", true);

        mgr.sendPrompt("hello", null, List.of());

        assertEquals(1, countStatuses(events, StatusEventTypeEnum.INFO));
        assertEquals("Wait for the pending changes to be resolved before sending another message",
                     status(events, StatusEventTypeEnum.INFO).text());
        assertEquals(1, countTurnCompletes(events), "nothing else closes this lock, so a turn-complete is required");
        assertEquals(0, countStatuses(events, StatusEventTypeEnum.BUSY), "a refusal must not start a turn");
    }

    @Test
    void sendPromptRefusedWhileNotRunning_namesTheBlockerAndReleasesTheUi() throws Exception {
        // A session that never finished starting blocks the send, and its refusal is what releases the UI lock.
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);

        mgr.sendPrompt("hello", null, List.of());

        assertEquals(1, countStatuses(events, StatusEventTypeEnum.INFO));
        assertEquals("GitHub Copilot is not ready — wait for it to finish starting",
                     status(events, StatusEventTypeEnum.INFO).text());
        assertEquals(1, countTurnCompletes(events));
        assertEquals(0, countStatuses(events, StatusEventTypeEnum.BUSY));
    }

    @Test
    void sendPromptRefusedWhileCompacting_namesTheBlockerAndReleasesTheUi() throws Exception {
        // A compaction is non-turn work that nothing else will close, so this refusal must release the UI too.
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        set(mgr, "running", true);
        CompletableFuture<String> pending = new CompletableFuture<>();
        assertTrue(mgr.runWork("Compacting conversation...", false, 60_000L,
                               () -> pending,
                               r -> new StatusEvent(StatusEventTypeEnum.READY, "done"),
                               err -> new StatusEvent(StatusEventTypeEnum.FAILED, "boom")));

        mgr.sendPrompt("hello", null, List.of());

        assertEquals(1, countStatuses(events, StatusEventTypeEnum.INFO));
        assertEquals("Wait for the current compaction to finish before sending another message",
                     status(events, StatusEventTypeEnum.INFO).text());
        assertEquals(1, countTurnCompletes(events));
        pending.completeExceptionally(new IllegalStateException("cleanup"));
    }

    @Test
    void sendPromptRefusedWhileATurnIsRunning_reportsInfoAndUnlocksTheSubmit() throws Exception {
        // Refinement to fix #3: a turn is already running and will close itself, so this refusal emits the INFO
        // every refusal must also release the submit lock; the stale in-flight completion is ignored by the UI contract.
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        set(mgr, "running", true);
        set(mgr, "processing", true);

        mgr.sendPrompt("hello", null, List.of());

        assertEquals(1, countStatuses(events, StatusEventTypeEnum.INFO));
        assertEquals("Wait for GitHub Copilot to finish before sending another message",
                     status(events, StatusEventTypeEnum.INFO).text());
        assertEquals(1, countTurnCompletes(events), "every refusal must release the submit lock");
        assertTrue(mgr.isBusy(), "the in-flight turn must not be released by a refused second send");
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

    private static long countTurnCompletes(List<AiProcessEvent> events) {
        return events.stream().filter(TurnCompleteEvent.class::isInstance).count();
    }

    @Test
    void stopClosesInFlightWorkAsFailedExactlyOnce() throws Exception {
        // stop() while non-turn work (a compaction) is in flight closes it with exactly one FAILED so the
        // session is never left holding a BUSY that only a late completion — possibly orphaned by stop's teardown —
        // would close. Exactly-once even if that late completion arrives afterwards.
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        CompletableFuture<String> pending = new CompletableFuture<>();
        assertTrue(mgr.runWork("Compacting conversation...", false, 60_000L,
                               () -> pending,
                               r -> new StatusEvent(StatusEventTypeEnum.READY, "done"),
                               err -> new StatusEvent(StatusEventTypeEnum.FAILED, "boom")),
                   "runWork must accept the compact while nothing else is in flight");
        assertEquals(1, events.stream()
                     .filter(e -> e instanceof StatusEvent s && s.type() == StatusEventTypeEnum.BUSY)
                     .count(), "a running compact reports one BUSY");
        assertTrue(mgr.isWorkInFlight());

        mgr.stop();

        assertFalse(mgr.isWorkInFlight(), "stop must close the in-flight work");
        assertEquals(1, events.stream()
                     .filter(e -> e instanceof StatusEvent s && s.type() == StatusEventTypeEnum.FAILED)
                     .count(), "stop must report exactly one FAILED");
        assertEquals(1, events.stream()
                     .filter(e -> e instanceof StatusEvent s && (s.type() == StatusEventTypeEnum.READY
                             || s.type() == StatusEventTypeEnum.FAILED
                             || s.type() == StatusEventTypeEnum.EXITED))
                     .count(), "BUSY must be closed by exactly one closing status");

        pending.complete("late");
        assertEquals(1, events.stream()
                     .filter(e -> e instanceof StatusEvent s && s.type() == StatusEventTypeEnum.FAILED)
                     .count(), "a late completion must not emit a second closing status");
    }

    /**
     * Plans and opens a session against a fake client, the way {@code start()} does: the manager's private
     * {@code planSession} and then {@code openSession}.
     */
    private static void createOrResume(GithubCopilotProcessManager mgr, CopilotSdkClient client, String model)
            throws Exception {
        Method plan = GithubCopilotProcessManager.class.getDeclaredMethod("planSession", String.class);
        plan.setAccessible(true);
        Object sessionPlan = plan.invoke(mgr, model);
        Method open = GithubCopilotProcessManager.class.getDeclaredMethod(
                "openSession", CopilotSdkClient.class, sessionPlan.getClass());
        open.setAccessible(true);
        open.invoke(mgr, client, sessionPlan);
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            }
            catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    /**
     * A {@link CopilotSdkClient} that never starts a CLI. It lists the session ids it was given, records the spec each
     * {@code create} and {@code resume} received (and the order of the calls) so a test can assert exactly what would
     * have been sent, and either fails the resume with a "session not found" error or lets it succeed.
     */
    private static final class FakeClient implements CopilotSdkClient {

        private final List<String> storedSessionIds;
        private final boolean resumeFails;
        private final List<String> calls = new ArrayList<>();
        private CopilotSessionSpec resumeSpec;
        private CopilotSessionSpec createSpec;

        FakeClient(List<String> storedSessionIds, boolean resumeFails) {
            this.storedSessionIds = storedSessionIds;
            this.resumeFails = resumeFails;
        }

        @Override
        public CompletableFuture<List<String>> listSessionIds() {
            return CompletableFuture.completedFuture(storedSessionIds);
        }

        @Override
        public CompletableFuture<CopilotSdkSession> create(CopilotSessionSpec spec) {
            calls.add("create");
            createSpec = spec;
            return CompletableFuture.completedFuture(new FakeSession(spec.sessionId()));
        }

        @Override
        public CompletableFuture<CopilotSdkSession> resume(String sessionId, CopilotSessionSpec spec) {
            calls.add("resume");
            resumeSpec = spec;
            if (resumeFails) {
                // A genuine ExecutionException whose deepest message marks it a "session not found" — the real failure
                // the fall-through guard was written for.
                return CompletableFuture.failedFuture(new ExecutionException(
                        new IllegalStateException("Request session.resume failed with message: Session not found: "
                                + sessionId)));
            }
            return CompletableFuture.completedFuture(new FakeSession(sessionId));
        }

        @Override
        public CompletableFuture<List<CopilotModel>> listModels() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<Map<String, CopilotQuotaSnapshot>> quotaSnapshots() {
            return CompletableFuture.completedFuture(Map.of());
        }

        @Override
        public void close() {
        }
    }

    /**
     * A {@link CopilotSdkSession} that does nothing but remember the sink it was subscribed with, so a test can raise
     * events on it as the SDK adapter would.
     */
    private static final class FakeSession implements CopilotSdkSession {

        private final String sessionId;
        private CopilotEventSink sink;

        FakeSession(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public CompletableFuture<String> send(String prompt) {
            return CompletableFuture.completedFuture("message-1");
        }

        @Override
        public CompletableFuture<String> sendImmediate(String prompt) {
            return CompletableFuture.completedFuture("message-1");
        }

        @Override
        public CompletableFuture<Void> abort() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<CopilotCompaction> compact(String customInstructions) {
            return CompletableFuture.completedFuture(new CopilotCompaction(Boolean.TRUE, 0L, 0L));
        }

        @Override
        public void abortManualCompaction() {
        }

        @Override
        public AutoCloseable subscribe(CopilotEventSink eventSink) {
            this.sink = eventSink;
            return () -> this.sink = null;
        }

        @Override
        public void close() {
        }
    }
}

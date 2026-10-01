package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SessionMetadata;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotReasoningEffortClearedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class GithubCopilotProcessManagerTest {

    @Test
    void buildCreateConfigPinsStableSessionId() {
        SessionConfig config = GithubCopilotProcessManager.buildCreateConfig(
                "plugin-session-123", "gpt-5.4", Map.of(), new GithubCopilotPermissionHandler(event -> {
        }, "plugin-session-123"), null);

        assertEquals("plugin-session-123", config.getSessionId());
        assertEquals("gpt-5.4", config.getModel());
        assertTrue(config.getExcludedTools().contains("edit"));
        assertTrue(config.getExcludedTools().contains("create"));
        // Withheld so Copilot never reaches for them: the permission gate would
        // refuse each one and post a system message, which a single survey turned
        // into six. bash stays available — running commands has no plugin
        // equivalent, so it is gated by confirmation rather than withheld.
        assertTrue(config.getExcludedTools().contains("glob"));
        assertTrue(config.getExcludedTools().contains("view"));
        assertFalse(config.getExcludedTools().contains("bash"));
    }

    @Test
    void buildCreateConfigOmitsReasoningEffortWhenNull() {
        SessionConfig config = GithubCopilotProcessManager.buildCreateConfig(
                "plugin-session-123", "gpt-5.4", Map.of(), new GithubCopilotPermissionHandler(event -> {
        }, "plugin-session-123"), null);

        assertNull(config.getReasoningEffort());
    }

    @Test
    void buildCreateConfigSendsExactReasoningEffortWhenSet() {
        SessionConfig config = GithubCopilotProcessManager.buildCreateConfig(
                "plugin-session-123", "gpt-5.4", Map.of(), new GithubCopilotPermissionHandler(event -> {
        }, "plugin-session-123"), "high");

        assertEquals("high", config.getReasoningEffort());
    }

    @Test
    void buildResumeConfigOmitsReasoningEffortWhenNull() {
        ResumeSessionConfig config = GithubCopilotProcessManager.buildResumeConfig(
                "gpt-5.4", Map.of(), new GithubCopilotPermissionHandler(event -> {
        }, "plugin-session-123"), null);

        assertNull(config.getReasoningEffort());
    }

    @Test
    void buildResumeConfigSendsExactReasoningEffortWhenSet() {
        ResumeSessionConfig config = GithubCopilotProcessManager.buildResumeConfig(
                "gpt-5.4", Map.of(), new GithubCopilotPermissionHandler(event -> {
        }, "plugin-session-123"), "xhigh");

        assertEquals("xhigh", config.getReasoningEffort());
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
        java.util.concurrent.atomic.AtomicInteger clearedCount = new java.util.concurrent.atomic.AtomicInteger();
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
        java.util.concurrent.atomic.AtomicInteger clearedCount = new java.util.concurrent.atomic.AtomicInteger();
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
    void createOrResumeSession_resumeFailureFallsThroughToCreate_firesExactlyOneInfo() throws Exception {
        // The exactly-one-INFO guarantee must hold end to end, not just in the unit method: a stored-but-unsupported
        // session-pinned value is validated ONCE per createOrResumeSession call (hoisted out of the builders), so when
        // the resume attempt fails and the call falls through to createSession — each builder running once — the INFO
        // fires once, and neither the resume config nor the create config may carry the unsupported value.
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new ScriptedGithubCopilotProcessManager("plugin-session-123", events::add);
        mgr.setReasoningEffort("high");
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of("gpt-5.4", List.of("low", "medium")), Map.of());
        AtomicInteger clearedCount = new AtomicInteger();
        mgr.setOnReasoningEffortCleared(clearedCount::incrementAndGet);
        set(mgr, "copilotSessionId", "plugin-session-123");

        try {
            Method createOrResume = GithubCopilotProcessManager.class.getDeclaredMethod(
                    "createOrResumeSession", CopilotClient.class, String.class);
            createOrResume.setAccessible(true);
            // The scripted manager's hooks never touch the (null) client — CopilotClient is final, so the test stubs
            // the manager, not the SDK class.
            createOrResume.invoke(mgr, null, "gpt-5.4");

            assertEquals(1, events.stream()
                    .filter(e -> e instanceof StatusEvent && ((StatusEvent) e).type() == StatusEventTypeEnum.INFO)
                    .count(),
                    () -> "exactly one INFO event expected, but got: " + events);
            assertEquals(1, clearedCount.get());
            ScriptedGithubCopilotProcessManager scripted = (ScriptedGithubCopilotProcessManager) mgr;
            assertNotNull(scripted.resumeConfig, "resume attempt must have been made");
            assertNotNull(scripted.createConfig, "fall-through must have created after the resume failure");
            assertNull(scripted.resumeConfig.getReasoningEffort(),
                    "cleared session-pinned value must not be sent on resume");
            assertNull(scripted.createConfig.getReasoningEffort(),
                    "cleared session-pinned value must not be sent on create");
        }
        finally {
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
        }
    }

    @Test
    void sessionListContainsMatchesOnlyKnownSessionIds() {
        SessionMetadata kept = new SessionMetadata();
        kept.setSessionId("keep-me");
        SessionMetadata other = new SessionMetadata();
        other.setSessionId("other");

        assertTrue(GithubCopilotProcessManager.sessionListContains(List.of(kept, other), "keep-me"));
        assertFalse(GithubCopilotProcessManager.sessionListContains(List.of(kept, other), "missing"));
        assertFalse(GithubCopilotProcessManager.sessionListContains(List.of(kept, other), " "));
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
        // calling handleRuntimeError directly, so reverting that wiring breaks this test. The session is detached
        // from any transport (JsonRpcClient null), so dispatchEvent delivers straight to the bridge's listeners.
        // A mid-turn runtime failure (quota, rate limit, network) must surface as exactly one FAILED — never
        // EXITED, which is reserved for the process actually dying (this SDK-based backend owns none) — and
        // must clear processing so the next turn can start.
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotProcessManager mgr = new GithubCopilotProcessManager(events::add);
        set(mgr, "processing", true);
        assertTrue(mgr.isBusy(), "a turn in flight makes the manager busy");

        CopilotSession session = newDetachedSession();
        mgr.createEventBridge(events::add).attach(session);
        dispatch(session, sessionError("rate limited"));

        assertFalse(mgr.isBusy(), "a runtime failure must clear the in-flight turn");
        assertEquals(1, events.size(), "a runtime failure must report exactly one status");
        StatusEvent status = (StatusEvent) events.get(0);
        assertEquals(StatusEventTypeEnum.FAILED, status.type(),
                "a runtime failure must be FAILED, never EXITED");
        assertEquals("GitHub Copilot: rate limited", status.text());
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

    private static CopilotSession newDetachedSession() throws Exception {
        // JsonRpcClient is package-private, so it can't be named in this test's source; find the (String,
        // JsonRpcClient, String) constructor by shape. A null transport means dispatchEvent delivers straight to
        // the bridge's listeners with no server involved.
        Constructor<CopilotSession> ctor = (Constructor<CopilotSession>) Arrays
                .stream(CopilotSession.class.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == 3 && c.getParameterTypes()[0] == String.class)
                .findFirst().orElseThrow();
        ctor.setAccessible(true);
        return ctor.newInstance("test-session", null, null);
    }

    private static void dispatch(CopilotSession session, SessionEvent event) throws Exception {
        Method m = CopilotSession.class.getDeclaredMethod("dispatchEvent", SessionEvent.class);
        m.setAccessible(true);
        m.invoke(session, event);
    }

    private static SessionErrorEvent sessionError(String message) {
        SessionErrorEvent event = new SessionErrorEvent();
        event.setData(new SessionErrorEvent.SessionErrorEventData(
                null, null, null, message, null, null, null, null, null, null));
        return event;
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
     * A {@link GithubCopilotProcessManager} that never touches a real CLI process: its
     * {@code listSessionsHook} reports the one session id the test drives, and its
     * {@code resumeSessionHook}/{@code createSessionHook} capture the config they received (so the test can
     * assert nothing unsupported was ever sent) and script the exact outcome the scenario needs. Subclassing
     * the manager instead of mocking {@link CopilotClient} because that SDK class is final and the plugin's
     * test classpath has no mocking library — the manager's three delegation hooks exist precisely for this.
     */
    private static final class ScriptedGithubCopilotProcessManager extends GithubCopilotProcessManager {

        private final String knownSessionId;
        private ResumeSessionConfig resumeConfig;
        private SessionConfig createConfig;

        ScriptedGithubCopilotProcessManager(String knownSessionId, AiProcessEventListener listener) {
            super(listener);
            this.knownSessionId = knownSessionId;
        }

        @Override
        CompletableFuture<List<SessionMetadata>> listSessionsHook(CopilotClient client) {
            SessionMetadata metadata = new SessionMetadata();
            metadata.setSessionId(knownSessionId);
            return CompletableFuture.completedFuture(List.of(metadata));
        }

        @Override
        CompletableFuture<CopilotSession> resumeSessionHook(CopilotClient client, String sessionId, ResumeSessionConfig config) {
            resumeConfig = config;
            // A genuine ExecutionException whose deepest message marks it a "session not found" — the real failure the
            // fall-through guard was written for.
            return CompletableFuture.failedFuture(new ExecutionException(
                    new IllegalStateException("Request session.resume failed with message: Session not found: "
                                              + knownSessionId)));
        }

        @Override
        CompletableFuture<CopilotSession> createSessionHook(CopilotClient client, SessionConfig config) {
            createConfig = config;
            return CompletableFuture.completedFuture(null);
        }
    }
}

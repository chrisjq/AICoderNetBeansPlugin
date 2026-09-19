package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SessionMetadata;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
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
            assertEquals(1, events.size());
            assertEquals(StatusEventTypeEnum.INFO, ((StatusEvent) events.get(0)).type());
            // Second call must not fire a second INFO event: the value is already cleared.
            assertNull(mgr.resolveValidatedReasoningEffort("gpt-5.4"));
            assertEquals(1, events.size());
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
        assertEquals(1, events.size());
        assertEquals(StatusEventTypeEnum.INFO, ((StatusEvent) events.get(0)).type());
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
     * A {@link GithubCopilotProcessManager} that never touches a real CLI process: its {@code listSessionsHook} reports
     * the one session id the test drives, and its {@code resumeSessionHook}/{@code createSessionHook} capture the
     * config they received (so the test can assert nothing unsupported was ever sent) and script the exact outcome the
     * scenario needs. Subclassing the manager instead of mocking {@link CopilotClient} because that SDK class is final
     * and the plugin's test classpath has no mocking library — the manager's three delegation hooks exist precisely for
     * this.
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

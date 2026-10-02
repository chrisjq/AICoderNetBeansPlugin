package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComboBox;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
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
 * Covers {@code GrokAiImplementation}'s session-lifecycle glue around the ACP-based
 * {@code GrokAiProcessManager}: model/reasoning-effort scoping (session-scoped changes never touch the global
 * default — {@link GrokPluginSettings}), {@code isStoredSessionValid}/{@code resumeSession} always trusting
 * the stored ACP session id (ACP's {@code session/load} falls back to {@code session/new} itself, so a stale
 * id never blocks the user — mirroring {@code OpenCodeAiImplementation}, not the old CLI's create-vs-resume
 * problem this test used to guard), the effective-reasoning-effort precedence (session wins over the global
 * default), and the callback path that clears a session-sourced effort the agent rejected — both in-memory
 * and in the persisted {@link GrokSessionSettings}.
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
    void isStoredSessionValid_alwaysTrue_sessionLoadFallsBackToSessionNew() {
        // session/load is attempted first with fallback to session/new (GrokAiProcessManager.spawnAndHandshake),
        // so a stored session id is always safe to try resuming — mirrors OpenCodeAiImplementation.
        GrokAiImplementation impl = new GrokAiImplementation(noopListener(), noopPrompter());
        assertTrue(impl.isStoredSessionValid(UUID.randomUUID().toString()));
        assertTrue(impl.isStoredSessionValid("anything-at-all"));
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

        assertEquals("high", impl.delegate().reasoningEffort,
                "the session's own reasoning effort must win over any global default");
        assertTrue(impl.delegate().reasoningEffortFromSession);
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

            assertEquals("medium", impl.delegate().reasoningEffort);
            assertFalse(impl.delegate().reasoningEffortFromSession);
        }
        finally {
            GrokPluginSettings.setReasoningEffort(globalBefore);
        }
    }

    // ---- the manager clearing an unsupported value must also clear the PERSISTED source, not just its own
    // in-memory field, or the same INFO event recurs forever across restarts ----
    @Test
    void unsupportedReasoningEffortClearedAtSendTimeAlsoClearsThePersistedSessionSetting() {
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setModel("grok-4.5");
        settings.setReasoningEffort("xhigh");
        GrokAiImplementation impl = implFor(newSession("grok-clear-1", settings));
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        impl.onStarted(stubHost(settings, updated));

        impl.delegate().configureReasoningEffort("xhigh", true);
        impl.delegate().sessionConfigOptions = fakeConfigOptions("grok-4.5", "low", "medium", "high");
        impl.delegate().applyInitialConfigOptionsIfNeeded();

        assertNull(settings.reasoningEffort(),
                "a stored-unsupported value must end up null in the persisted session settings, not just the "
                + "in-memory field");
        assertEquals(settings, updated.get(), "the cleared settings must actually be persisted through the host");
    }

    /**
     * Builds a {@code configOptions} snapshot shaped like {@code session/new}'s response: a {@code model}
     * option whose current value already matches {@code model} (so the model branch never needs to send
     * anything), and a {@code reasoning_effort} option offering exactly {@code availableEfforts}.
     */
    private static com.google.gson.JsonArray fakeConfigOptions(String model, String... availableEfforts) {
        com.google.gson.JsonArray options = new com.google.gson.JsonArray();
        com.google.gson.JsonObject modelOpt = new com.google.gson.JsonObject();
        modelOpt.addProperty("id", "model");
        modelOpt.addProperty("currentValue", model);
        options.add(modelOpt);
        com.google.gson.JsonObject effortOpt = new com.google.gson.JsonObject();
        effortOpt.addProperty("id", "reasoning_effort");
        com.google.gson.JsonArray values = new com.google.gson.JsonArray();
        for (String effort : availableEfforts) {
            com.google.gson.JsonObject v = new com.google.gson.JsonObject();
            v.addProperty("value", effort);
            values.add(v);
        }
        effortOpt.add("options", values);
        options.add(effortOpt);
        return options;
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

    // ---- only a SESSION-pinned value is ever cleared automatically. The global default belongs to the user
    // and to every other session/backend, so one session's model rejecting it must never touch the global
    // default. ----
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

    // ---- session AND global set to DIFFERENT non-blank values at once — the case that would catch a
    // wrong-scope clearing bug ----
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
            impl.delegate().sessionConfigOptions = fakeConfigOptions("grok-4.5", "low", "medium", "high");
            impl.delegate().applyInitialConfigOptionsIfNeeded();

            assertNull(settings.reasoningEffort(), "the session's own unsupported value must be cleared");
            assertEquals("medium", GrokPluginSettings.getReasoningEffort(),
                    "the global default must be left completely untouched, even though it is also set");
        }
        finally {
            GrokPluginSettings.setReasoningEffort(globalBefore);
        }
    }

    // ---- a reasoning-effort (or model) change on a RUNNING session must reach the agent, not just be stored:
    // the manager otherwise sends the effort once, when the ACP session is established ----
    /**
     * A manager that records {@code session/set_config_option} calls instead of sending them, and counts the
     * asynchronous live-effort pushes the implementation requests.
     */
    private static final class RecordingManager extends GrokAiProcessManager {

        final List<String> configCalls = new CopyOnWriteArrayList<>();
        final List<AiProcessEvent> events;
        final AtomicInteger livePushes = new AtomicInteger();
        volatile boolean live = true;

        RecordingManager(List<AiProcessEvent> events) {
            super(events::add);
            this.events = events;
        }

        @Override
        public boolean isSessionLive() {
            return live;
        }

        @Override
        public CompletableFuture<com.google.gson.JsonArray> setConfigOption(String configId, String value) {
            configCalls.add(configId + "=" + value);
            return CompletableFuture.completedFuture(new com.google.gson.JsonArray());
        }

        @Override
        public void applyReasoningEffortToLiveSessionAsync() {
            livePushes.incrementAndGet();
        }

        @Override
        public void changeModelOnLiveSessionAsync(String newModel) {
            modelChanges.add(newModel);
        }

        final List<String> modelChanges = new CopyOnWriteArrayList<>();
    }

    /**
     * Keeps the real config-operation queue but answers the agent itself: the {@code model} response is held
     * until the test releases it, and a {@code reasoning_effort} request updates the agent's current effort
     * the way a real agent's response would.
     */
    private static final class QueueManager extends GrokAiProcessManager {

        final List<String> configCalls = new CopyOnWriteArrayList<>();
        final List<AiProcessEvent> events;
        final CompletableFuture<com.google.gson.JsonArray> heldModelResponse = new CompletableFuture<>();

        QueueManager(List<AiProcessEvent> events) {
            super(events::add);
            this.events = events;
        }

        @Override
        public boolean isSessionLive() {
            return true;
        }

        /**
         * Stands in for the running ACP session's identity; the test changes it to replace the session.
         */
        volatile Object token = "session-1";

        @Override
        Object currentSessionToken() {
            return token;
        }

        @Override
        public CompletableFuture<com.google.gson.JsonArray> setConfigOption(String configId, String value) {
            configCalls.add(configId + "=" + value);
            if ("model".equals(configId)) {
                return heldModelResponse.thenApply(options -> {
                    sessionConfigOptions = options;
                    return options;
                });
            }
            com.google.gson.JsonArray options = sessionConfigOptions;
            CompletableFuture<Void> gate = effortGate;
            if (gate != null) {
                return gate.thenApply(ignored -> setCurrentValue(options, configId, value));
            }
            return CompletableFuture.completedFuture(setCurrentValue(options, configId, value));
        }

        /**
         * When set, a {@code reasoning_effort} request is answered only once this completes, like a slow
         * agent.
         */
        volatile CompletableFuture<Void> effortGate;

        private static com.google.gson.JsonArray setCurrentValue(com.google.gson.JsonArray options, String configId, String value) {
            for (com.google.gson.JsonElement el : options) {
                com.google.gson.JsonObject opt = el.getAsJsonObject();
                if (configId.equals(opt.get("id").getAsString())) {
                    opt.addProperty("currentValue", value);
                }
            }
            return options;
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static String agentEffort(com.google.gson.JsonArray options) {
        for (com.google.gson.JsonElement el : options) {
            com.google.gson.JsonObject opt = el.getAsJsonObject();
            if ("reasoning_effort".equals(opt.get("id").getAsString())) {
                return opt.get("currentValue").getAsString();
            }
        }
        return null;
    }

    private static GrokAiImplementation implWith(GrokAiProcessManager manager, AiSession session) {
        return new GrokAiImplementation(e -> {
        }, null, manager) {
            {
                currentSession = session;
            }
        };
    }

    private static JComboBox<?> effortComboOf(GrokAiImplementation impl, AiSession session, GrokSessionSettings settings) {
        return (JComboBox<?>) impl.createInfoBarExtension(session, stubHost(settings, new AtomicReference<>()))
                .createComponents().get(1);
    }

    @Test
    void effortPickOnALiveSession_isPushedToTheAgent() {
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setModel("grok-4.6");
        AiSession session = newSession("grok-live-effort-1", settings);
        RecordingManager manager = new RecordingManager(new CopyOnWriteArrayList<>());
        JComboBox<?> combo = effortComboOf(implWith(manager, session), session, settings);
        assertTrue(combo.getItemCount() > 1, "the combo must offer at least one real level");
        String level = combo.getItemAt(1).toString();

        combo.setSelectedItem(level);

        assertEquals(level, manager.reasoningEffort, "the pick is still stored");
        assertEquals(1, manager.livePushes.get(), "a pick on a running session must be sent to the agent, not only stored");
    }

    @Test
    void effortPickWithNoLiveSession_isOnlyStored() {
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setModel("grok-4.6");
        AiSession session = newSession("grok-live-effort-2", settings);
        RecordingManager manager = new RecordingManager(new CopyOnWriteArrayList<>());
        manager.live = false;
        JComboBox<?> combo = effortComboOf(implWith(manager, session), session, settings);
        String level = combo.getItemAt(1).toString();

        combo.setSelectedItem(level);

        assertEquals(level, manager.reasoningEffort);
        assertEquals(0, manager.livePushes.get(), "nothing to send to before the session is established");
    }

    @Test
    void modelChangeOnALiveSession_reChecksTheEffortOnceTheSwitchIsConfirmed() {
        GrokSessionSettings settings = new GrokSessionSettings();
        AiSession session = newSession("grok-live-model-1", settings);
        RecordingManager manager = new RecordingManager(new CopyOnWriteArrayList<>());

        implWith(manager, session).setModel("grok-4.6");

        assertEquals(List.of("grok-4.6"), manager.modelChanges,
                "a model change on a running session goes through the manager's config-operation queue, which "
                + "re-checks the effort once the switch is confirmed");
        assertTrue(manager.configCalls.isEmpty(), "and is never sent around that queue: " + manager.configCalls);
    }

    @Test
    void modelChangeWithNoLiveSession_isNotSentToTheAgent() {
        GrokSessionSettings settings = new GrokSessionSettings();
        AiSession session = newSession("grok-live-model-2", settings);
        RecordingManager manager = new RecordingManager(new CopyOnWriteArrayList<>());
        manager.live = false;

        implWith(manager, session).setModel("grok-4.6");

        assertTrue(manager.modelChanges.isEmpty());
    }

    // ---- a model change and an effort pick made back to back are ONE ordered transaction: the effort must be
    // judged against the options the NEW model returned, never the previous model's stale ones ----
    @Test
    void effortPickedWhileAModelChangeIsInFlight_isJudgedAgainstTheNewModelsOptionsAndKept() throws Exception {
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setModel("grok-4.7");
        settings.setReasoningEffort("xhigh");
        AiSession session = newSession("grok-race-1", settings);
        QueueManager manager = new QueueManager(new CopyOnWriteArrayList<>());
        GrokAiImplementation impl = implWith(manager, session);
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        impl.onStarted(stubHost(settings, updated));
        // The running model has no xhigh; the model about to be picked does.
        manager.sessionConfigOptions = optionsFor("grok-4.7", "low", "low", "high");
        manager.configureReasoningEffort("xhigh", true);
        CountDownLatch cleared = new CountDownLatch(1);
        manager.setOnReasoningEffortCleared(() -> {
            impl.clearInvalidPersistedReasoningEffort();
            cleared.countDown();
        });

        impl.setModel("grok-4.6");
        manager.applyReasoningEffortToLiveSessionAsync();

        // While the model request is unanswered the stale options must not be used to discard the effort.
        assertFalse(cleared.await(300, TimeUnit.MILLISECONDS),
                "the effort was judged against the previous model's options while the model change was in flight");
        manager.heldModelResponse.complete(optionsFor("grok-4.6", "low", "low", "high", "xhigh"));
        manager.awaitConfigOperationsForTesting();

        assertEquals(List.of("model=grok-4.6", "reasoning_effort=xhigh"), manager.configCalls,
                "model first, then the effort the new model supports, each sent once");
        assertEquals("xhigh", manager.reasoningEffort);
        assertEquals("xhigh", settings.reasoningEffort(), "the persisted session override must survive");
        assertEquals(1, cleared.getCount(), "nothing may have been cleared");
        assertFalse(manager.events.stream().anyMatch(e -> e instanceof StatusEvent se && se.text().contains("not available")),
                "the user must not be told xhigh is unavailable: " + manager.events);
    }

    // ---- the start-up apply runs on the same queue, so a pick made the moment the session is published is
    // applied after it, never alongside it (otherwise the start-up response could land last and win) ----
    @Test
    void aPickMadeDuringTheStartUpApply_isAppliedAfterItAndWins() throws Exception {
        QueueManager manager = new QueueManager(new CopyOnWriteArrayList<>());
        manager.sessionConfigOptions = optionsFor("grok-4.6", "medium", "low", "medium", "high");
        manager.configureReasoningEffort("high", true);
        manager.effortGate = new CompletableFuture<>();
        Thread startUp = new Thread(manager::applyInitialConfigOptionsOnQueue);
        startUp.start();
        assertTrue(waitFor(() -> manager.configCalls.contains("reasoning_effort=high"), 5000),
                "the start-up apply must have sent its effort: " + manager.configCalls);

        manager.configureReasoningEffort("low", true);
        manager.applyReasoningEffortToLiveSessionAsync();

        assertFalse(waitFor(() -> manager.configCalls.contains("reasoning_effort=low"), 300),
                "the pick was sent while the start-up apply was still waiting for the agent: " + manager.configCalls);
        manager.effortGate.complete(null);
        startUp.join(10_000);
        manager.awaitConfigOperationsForTesting();

        assertEquals(List.of("reasoning_effort=high", "reasoning_effort=low"), manager.configCalls);
        assertEquals("low", agentEffort(manager.sessionConfigOptions), "the later pick must be what the agent ends on");
    }

    // ---- start-up: the effort is judged against the options the model request returned, like a live change ----
    @Test
    void startUpApply_judgesTheEffortAgainstTheOptionsTheModelRequestReturned() {
        QueueManager manager = new QueueManager(new CopyOnWriteArrayList<>());
        manager.setModel("grok-4.6");
        // The agent started on a model without xhigh; the stored model has it.
        manager.sessionConfigOptions = optionsFor("grok-4.7", "low", "low", "high");
        manager.configureReasoningEffort("xhigh", true);
        AtomicBoolean cleared = new AtomicBoolean();
        manager.setOnReasoningEffortCleared(() -> cleared.set(true));
        manager.heldModelResponse.complete(optionsFor("grok-4.6", "low", "low", "high", "xhigh"));

        manager.applyInitialConfigOptionsIfNeeded();

        assertEquals(List.of("model=grok-4.6", "reasoning_effort=xhigh"), manager.configCalls,
                "xhigh is supported by the model that was just selected, so it is sent");
        assertEquals("xhigh", manager.reasoningEffort);
        assertFalse(cleared.get(), "the effort must not be discarded on the strength of the pre-model options");
    }

    // ---- a queued config task belongs to the session it was queued for ----
    @Test
    void aQueuedConfigTask_neverReachesASessionThatReplacedTheOneItWasQueuedFor() throws Exception {
        QueueManager manager = new QueueManager(new CopyOnWriteArrayList<>());
        manager.setModel("grok-4.6");
        manager.sessionConfigOptions = optionsFor("grok-4.7", "low", "low", "high", "xhigh");
        manager.configureReasoningEffort("xhigh", true);
        AtomicBoolean cleared = new AtomicBoolean();
        manager.setOnReasoningEffortCleared(() -> cleared.set(true));

        manager.changeModelOnLiveSessionAsync("grok-4.6");
        manager.applyReasoningEffortToLiveSessionAsync();
        assertTrue(waitFor(() -> manager.configCalls.contains("model=grok-4.6"), 5000),
                "the model request is in flight, the effort task queued behind it");
        manager.token = "session-2";
        manager.heldModelResponse.complete(optionsFor("grok-4.6", "low", "low", "high", "xhigh"));
        manager.awaitConfigOperationsForTesting();

        assertEquals(List.of("model=grok-4.6"), manager.configCalls,
                "nothing queued for the old session may be sent on the new one");
        assertEquals("xhigh", manager.reasoningEffort);
        assertFalse(cleared.get());
    }

    @Test
    void aConfigTaskQueuedWithNoSession_neverRuns() throws Exception {
        QueueManager manager = new QueueManager(new CopyOnWriteArrayList<>());
        manager.sessionConfigOptions = optionsFor("grok-4.6", "low", "low", "high");
        manager.configureReasoningEffort("high", true);
        manager.token = null;

        manager.applyReasoningEffortToLiveSessionAsync();
        manager.awaitConfigOperationsForTesting();

        assertTrue(manager.configCalls.isEmpty(), manager.configCalls.toString());
    }

    // ---- a rejected level is only cleared while it is still the stored value: a newer pick made meanwhile
    // is never wiped, in memory or in the persisted settings ----
    @Test
    void rejectedEffortIsOnlyClearedWhileItIsStillTheStoredValue() {
        RecordingManager manager = new RecordingManager(new CopyOnWriteArrayList<>());
        AtomicInteger cleared = new AtomicInteger();
        manager.setOnReasoningEffortCleared(cleared::incrementAndGet);
        manager.configureReasoningEffort("high", true);

        assertFalse(manager.clearRejectedReasoningEffort("xhigh"), "xhigh is no longer what is stored");
        assertEquals("high", manager.reasoningEffort);
        assertEquals(0, cleared.get());

        manager.configureReasoningEffort("xhigh", true);
        assertTrue(manager.clearRejectedReasoningEffort("xhigh"));
        assertNull(manager.reasoningEffort);
        assertEquals(1, cleared.get());
    }

    @Test
    void clearingAPersistedEffort_leavesANewerPickAlone() {
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setReasoningEffort("high");
        GrokAiImplementation impl = implFor(newSession("grok-clear-5", settings));
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        impl.onStarted(stubHost(settings, updated));
        impl.delegate().configureReasoningEffort("high", true);

        impl.clearInvalidPersistedReasoningEffort();

        assertEquals("high", settings.reasoningEffort(), "a newer pick must survive the clearing of an older rejected one");
        assertNull(updated.get(), "and nothing is persisted over it");
    }

    @Test
    void failedModelChange_neverClearsAnEffortJudgedAgainstTheModelTheUserDidNotPick() throws Exception {
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setModel("grok-4.7");
        settings.setReasoningEffort("xhigh");
        AiSession session = newSession("grok-race-2", settings);
        QueueManager manager = new QueueManager(new CopyOnWriteArrayList<>());
        GrokAiImplementation impl = implWith(manager, session);
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        impl.onStarted(stubHost(settings, updated));
        manager.sessionConfigOptions = optionsFor("grok-4.7", "low", "low", "high");
        manager.configureReasoningEffort("xhigh", true);

        impl.setModel("grok-4.6");
        manager.heldModelResponse.completeExceptionally(new IllegalStateException("model rejected"));
        manager.awaitConfigOperationsForTesting();

        assertEquals("xhigh", manager.reasoningEffort,
                "the agent is still on the old model, so its options say nothing about the model the user picked");
        assertEquals("xhigh", settings.reasoningEffort());
        assertEquals(List.of("model=grok-4.6"), manager.configCalls, "no effort is sent on the strength of the old options");
        assertTrue(manager.events.stream().anyMatch(e -> e instanceof StatusEvent se && se.text().contains("rejected by Grok for model")),
                "the user is told the model change failed: " + manager.events);
    }

    /**
     * A {@code configOptions} snapshot whose {@code reasoning_effort} option is currently {@code current} and
     * offers exactly {@code available}.
     */
    private static com.google.gson.JsonArray effortOptions(String current, String... available) {
        return optionsFor("grok-4.6", current, available);
    }

    /**
     * Like {@link #effortOptions} for an agent currently on {@code model}.
     */
    private static com.google.gson.JsonArray optionsFor(String model, String current, String... available) {
        com.google.gson.JsonArray options = fakeConfigOptions(model, available);
        for (com.google.gson.JsonElement el : options) {
            com.google.gson.JsonObject opt = el.getAsJsonObject();
            if ("reasoning_effort".equals(opt.get("id").getAsString())) {
                opt.addProperty("currentValue", current);
            }
        }
        return options;
    }

    @Test
    void liveEffortApply_sendsASupportedLevelThatDiffersFromTheAgents() {
        RecordingManager manager = new RecordingManager(new CopyOnWriteArrayList<>());
        manager.sessionConfigOptions = effortOptions("low", "low", "high");
        manager.configureReasoningEffort("high", true);

        manager.applyReasoningEffortToLiveSession();

        assertEquals(List.of("reasoning_effort=high"), manager.configCalls);
    }

    @Test
    void liveEffortApply_sendsNothingWhenTheAgentAlreadyHasThatLevel() {
        RecordingManager manager = new RecordingManager(new CopyOnWriteArrayList<>());
        manager.sessionConfigOptions = effortOptions("high", "low", "high");
        manager.configureReasoningEffort("high", true);

        manager.applyReasoningEffortToLiveSession();

        assertTrue(manager.configCalls.isEmpty(), manager.configCalls.toString());
    }

    @Test
    void liveEffortApply_neverSendsALevelTheCurrentModelDoesNotAccept_andClearsTheSessionsOwnValue() {
        RecordingManager manager = new RecordingManager(new CopyOnWriteArrayList<>());
        AtomicBoolean cleared = new AtomicBoolean();
        manager.setOnReasoningEffortCleared(() -> cleared.set(true));
        manager.sessionConfigOptions = effortOptions("low", "low", "high");
        manager.configureReasoningEffort("xhigh", true);

        manager.applyReasoningEffortToLiveSession();

        assertTrue(manager.configCalls.isEmpty(), "an unsupported level must not be sent: " + manager.configCalls);
        assertNull(manager.reasoningEffort, "a session-sourced unsupported value is cleared");
        assertTrue(cleared.get(), "so the persisted setting is cleared too");
        assertTrue(manager.events.stream().anyMatch(e -> e instanceof StatusEvent se && se.text().contains("not available")),
                "the user is told once: " + manager.events);
    }

    @Test
    void liveEffortApply_leavesAnUnsupportedGlobalDefaultAlone() {
        RecordingManager manager = new RecordingManager(new CopyOnWriteArrayList<>());
        AtomicBoolean cleared = new AtomicBoolean();
        manager.setOnReasoningEffortCleared(() -> cleared.set(true));
        manager.sessionConfigOptions = effortOptions("low", "low", "high");
        manager.configureReasoningEffort("xhigh", false);

        manager.applyReasoningEffortToLiveSession();

        assertTrue(manager.configCalls.isEmpty());
        assertEquals("xhigh", manager.reasoningEffort, "the global default is never cleared automatically");
        assertFalse(cleared.get());
    }

    @Test
    void liveEffortApply_withNoConfigOptionsIsANoOp_notARejection() {
        RecordingManager manager = new RecordingManager(new CopyOnWriteArrayList<>());
        AtomicBoolean cleared = new AtomicBoolean();
        manager.setOnReasoningEffortCleared(() -> cleared.set(true));
        manager.sessionConfigOptions = null;
        manager.configureReasoningEffort("high", true);

        manager.applyReasoningEffortToLiveSession();

        assertEquals("high", manager.reasoningEffort);
        assertFalse(cleared.get());
        assertTrue(manager.configCalls.isEmpty());
    }

    @Test
    void modelCatalogPublishUpdatesTheOpenBarsModelComboThroughAvailableModelsEvent() throws Exception {
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.ui.GrokAiInfoBarExtension bar
                                                                                      = new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.ui.GrokAiInfoBarExtension();
        CountDownLatch delivered = new CountDownLatch(1);
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener listener = event -> {
            // Mirrors AiTopComponent's own bus-listener forwarding: the bus dispatches off the EDT.
            javax.swing.SwingUtilities.invokeLater(() -> bar.onPropertyEvent(event));
            delivered.countDown();
        };
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus bus
                                                                    = kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus.getInstance();
        // AiModelCatalog.publish retains whatever it's given as the new cached snapshot indefinitely (there is
        // no per-test scope), so a later test's createInfoBarExtension replay — or a later bar in the same
        // JVM — would otherwise see this synthetic list. Restore the prior snapshot in finally.
        List<String> before = GrokAiImplementation.modelCatalog().getCachedModels();
        bus.addListener(AiTypeEnum.GROK, listener);
        try {
            List<String> discovered = List.of("grok-catalog-test-1", "grok-catalog-test-2");
            GrokAiImplementation.modelCatalog().publish(discovered);

            assertTrue(delivered.await(5, TimeUnit.SECONDS),
                    "the type-wide property bus must deliver the discovered list to every open Grok bar");
            javax.swing.SwingUtilities.invokeAndWait(() -> {
            });

            JComboBox<?> modelCombo = (JComboBox<?>) bar.createComponents().get(0);
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < modelCombo.getItemCount(); i++) {
                items.add(modelCombo.getItemAt(i));
            }
            assertEquals(discovered, items,
                    "publishing through the catalog — the only channel now — must repopulate the model combo");
        }
        finally {
            bus.removeListener(AiTypeEnum.GROK, listener);
            GrokAiImplementation.modelCatalog().publish(before);
        }
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ClientResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.CompatibilityFlags;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ErrorMapper;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.MappedError;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedSnapshot;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedSnapshotSources;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SecretStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SessionSecrets;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.ChatClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.TurnContext;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manager's session state: saved context, key hints, per-request flags, the one-time non-streaming note and the
 * live Detected snapshot.
 */
class OpenAiCompatibleAiProcessManagerStateTest {

    private static final OpenAiCompatibleLimits LIMITS = OpenAiCompatibleLimits.defaults()
            .withCheckInterval(Duration.ofMillis(20))
            .withFirstByteTimeout(Duration.ofMillis(300))
            .withIdleTimeout(Duration.ofMillis(300))
            .withTotalCap(Duration.ofSeconds(2))
            .withRetryBackoff(Duration.ofMillis(50))
            .withProbeTimeout(Duration.ofMillis(100))
            .withTranscriptCapBytes(1L << 20);

    private static final SecretStore NO_SECRETS = new SecretStore() {
        @Override
        public char[] read(String key) {
            return null;
        }

        @Override
        public void save(String key, char[] value, String description) {
        }

        @Override
        public void delete(String key) {
        }
    };

    @TempDir
    Path contextRoot;

    private final List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
    private OpenAiCompatibleAiProcessManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    private static ChatClient answering(String text) {
        return (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                -> CompletableFuture.completedFuture(new ClientResult(
                        new AssembledReply(text, List.of(), "stop", null, null, true, true),
                        ClientResult.End.COMPLETE, null, false));
    }

    private static ChatClient failing(MappedError.Kind kind, String message) {
        return (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                -> CompletableFuture.completedFuture(new ClientResult(null, ClientResult.End.HTTP_ERROR,
                                                                      new MappedError(kind, message, null, null), false));
    }

    private static OpenAiCompatibleSessionSettings localSettings() {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setBaseUrl("http://localhost:1");
        settings.setModel("model-x");
        settings.setApiKeySet(false);
        return settings;
    }

    private static AiSession session(String id, OpenAiCompatibleSessionSettings settings) {
        return new AiSession(id, "OC", null, AiTypeEnum.OPENAI_COMPATIBLE, null, settings, Instant.now(),
                             Instant.now());
    }

    private OpenAiCompatibleAiProcessManager start(String id, ChatClient client,
                                                   OpenAiCompatibleSessionSettings settings, SecretStore store,
                                                   AtomicReference<SessionSecrets> secretsOut) {
        OpenAiCompatibleAiProcessManager created = new OpenAiCompatibleAiProcessManager(
                events::add, limits -> client,
                (sessionId, keySet) -> {
                    SessionSecrets secrets = new SessionSecrets(sessionId, store, Runnable::run, keySet);
                    if (secretsOut != null) {
                        secretsOut.set(secrets);
                    }
                    return secrets;
                }, LIMITS);
        created.setContextDirResolverForTest(sessionId -> contextRoot.resolve(sessionId));
        created.startWithoutMcpForTest(session(id, settings));
        manager = created;
        return created;
    }

    private OpenAiCompatibleAiProcessManager start(String id, ChatClient client,
                                                   OpenAiCompatibleSessionSettings settings) {
        return start(id, client, settings, NO_SECRETS, null);
    }

    private long turnsCompleted() {
        return events.stream().filter(TurnCompleteEvent.class::isInstance).count();
    }

    private void runTurn(OpenAiCompatibleAiProcessManager pm, String text) throws InterruptedException {
        long before = turnsCompleted();
        pm.sendPrompt(text, null, List.of());
        awaitTrue(() -> turnsCompleted() > before && !pm.isProcessing(), "the turn to finish");
        pm.awaitContextIo();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    private Path contextFile(String id) {
        return contextRoot.resolve(id).resolve(OpenAiCompatibleAiProcessManager.CONTEXT_FILE_NAME);
    }

    // ---- saved context ----
    @Test
    @Timeout(20)
    void unsetSaveContextOnCloseWritesAndRestoresContext() throws Exception {
        OpenAiCompatibleSessionSettings settings = localSettings();
        OpenAiCompatibleAiProcessManager first = start("ctx-default", answering("Default answer."), settings);
        runTurn(first, "default question");
        first.awaitContextIo();
        assertTrue(Files.isRegularFile(contextFile("ctx-default")), "the unset default saves context");
        first.stop();

        OpenAiCompatibleAiProcessManager second = start("ctx-default", answering("unused"), settings);

        List<ChatMessage> restored = second.historyForTest();
        assertEquals(2, restored.size());
        assertEquals("default question", restored.get(0).content());
        assertEquals("Default answer.", restored.get(1).content());
    }

    @Test
    @Timeout(20)
    void theHistorySurvivesStopAndRestartWhenSaveContextIsOn() throws Exception {
        OpenAiCompatibleSessionSettings settings = localSettings();
        settings.setSaveContextOnClose(true);
        OpenAiCompatibleAiProcessManager first = start("ctx-1", answering("First answer."), settings);
        runTurn(first, "first question");
        assertTrue(Files.isRegularFile(contextFile("ctx-1")), "the turn's end saves the context");
        first.stop();

        OpenAiCompatibleAiProcessManager second = start("ctx-1", answering("unused"), settings);

        List<ChatMessage> restored = second.historyForTest();
        assertEquals(2, restored.size());
        assertEquals(ChatRole.USER, restored.get(0).role());
        assertEquals("first question", restored.get(0).content());
        assertEquals(ChatRole.ASSISTANT, restored.get(1).role());
        assertEquals("First answer.", restored.get(1).content());
    }

    @Test
    @Timeout(20)
    void aSavedSummaryAndTheCalibrationComeBackAfterResume() throws Exception {
        JsonObject state = new JsonObject();
        state.addProperty("compactionSummary", "Goal: finish the report.");
        state.addProperty("calibrationRatio", 1.25);
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        JsonObject summaryMessage = new JsonObject();
        summaryMessage.addProperty("role", "USER");
        summaryMessage.addProperty("content", TurnContext.SUMMARY_PREFIX + "Goal: finish the report.");
        messages.add(summaryMessage);
        state.add("messages", messages);
        state.addProperty("prunedCount", 3);
        Files.createDirectories(contextFile("ctx-2").getParent());
        Files.writeString(contextFile("ctx-2"), state.toString());
        OpenAiCompatibleSessionSettings settings = localSettings();
        settings.setSaveContextOnClose(true);

        OpenAiCompatibleAiProcessManager pm = start("ctx-2", answering("x"), settings);
        pm.resumeSession("ctx-2");

        assertEquals("Goal: finish the report.", pm.turnContextForTest().summary());
        assertEquals(1, pm.historyForTest().size());
        assertEquals(3, pm.turnContextForTest().prunedCount());
        assertEquals(3, pm.snapshot().prunedCount());
    }

    @Test
    @Timeout(20)
    void withSaveContextOffTheFileIsNotWrittenAndAStaleOneIsDeleted() throws Exception {
        Files.createDirectories(contextFile("ctx-3").getParent());
        Files.writeString(contextFile("ctx-3"), "{\"messages\":[]}");
        OpenAiCompatibleSessionSettings settings = localSettings();
        settings.setSaveContextOnClose(false);

        OpenAiCompatibleAiProcessManager pm = start("ctx-3", answering("a"), settings);
        pm.awaitContextIo();

        assertFalse(Files.exists(contextFile("ctx-3")), "starting with the setting off removes the file");
        runTurn(pm, "question");
        assertFalse(Files.exists(contextFile("ctx-3")), "a turn does not save with the setting off");
    }

    @Test
    @Timeout(20)
    void switchingTheSettingOffWhileRunningDeletesTheFile() throws Exception {
        OpenAiCompatibleSessionSettings settings = localSettings();
        settings.setSaveContextOnClose(true);
        OpenAiCompatibleAiProcessManager pm = start("ctx-4", answering("a"), settings);
        runTurn(pm, "question");
        assertTrue(Files.isRegularFile(contextFile("ctx-4")));

        settings.setSaveContextOnClose(false);
        pm.onSessionSettingsChanged(settings);
        pm.awaitContextIo();

        assertFalse(Files.exists(contextFile("ctx-4")));
    }

    @Test
    @Timeout(20)
    void aCorruptFileIsIgnoredAndTheSessionStillStartsAndWorks() throws Exception {
        OpenAiCompatibleSessionSettings settings = localSettings();
        settings.setSaveContextOnClose(true);
        Files.createDirectories(contextFile("ctx-5").getParent());
        Files.writeString(contextFile("ctx-5"), "this is not json {{{");

        OpenAiCompatibleAiProcessManager pm = start("ctx-5", answering("a"), settings);

        assertEquals(0, pm.historyForTest().size());
        runTurn(pm, "still works");
        assertEquals(2, pm.historyForTest().size());
        assertTrue(Files.readString(contextFile("ctx-5")).contains("still works"),
                   "the next turn replaces the corrupt file with a good one");
    }

    @Test
    @Timeout(20)
    void anOversizedFileIsIgnored() throws Exception {
        OpenAiCompatibleSessionSettings settings = localSettings();
        settings.setSaveContextOnClose(true);
        Files.createDirectories(contextFile("ctx-6").getParent());
        byte[] big = new byte[(int) LIMITS.transcriptCapBytes() + 1024];
        java.util.Arrays.fill(big, (byte) 'x');
        Files.write(contextFile("ctx-6"), big);

        OpenAiCompatibleAiProcessManager pm = start("ctx-6", answering("a"), settings);

        assertEquals(0, pm.historyForTest().size());
    }

    // ---- key hints ----
    @Test
    @Timeout(20)
    void aKeySetOnTheSessionButMissingOnThisComputerShowsTheNotFoundHint() {
        OpenAiCompatibleSessionSettings settings = localSettings();
        settings.setApiKeySet(true);

        OpenAiCompatibleAiProcessManager pm = start("key-1", answering("a"), settings);

        assertEquals(OpenAiCompatibleAiProcessManager.KEY_NOT_FOUND_HINT, pm.keyHintForTest());
    }

    @Test
    @Timeout(20)
    void aKeyringThatCannotBeReadShowsTheUnavailableHint() {
        SecretStore broken = new SecretStore() {
            @Override
            public char[] read(String key) {
                throw new IllegalStateException("keyring locked");
            }

            @Override
            public void save(String key, char[] value, String description) {
            }

            @Override
            public void delete(String key) {
            }
        };
        OpenAiCompatibleSessionSettings settings = localSettings();
        settings.setApiKeySet(true);

        OpenAiCompatibleAiProcessManager pm = start("key-2", answering("a"), settings, broken, null);

        assertEquals(OpenAiCompatibleAiProcessManager.KEY_UNAVAILABLE_HINT, pm.keyHintForTest());
    }

    @Test
    @Timeout(20)
    void aKeyThatWasNotKeptByTheKeyringShowsTheUnavailableHintAtTheNextTurn() throws Exception {
        SecretStore forgetful = new SecretStore() {
            @Override
            public char[] read(String key) {
                return null;
            }

            @Override
            public void save(String key, char[] value, String description) {
            }

            @Override
            public void delete(String key) {
            }
        };
        AtomicReference<SessionSecrets> secrets = new AtomicReference<>();
        OpenAiCompatibleSessionSettings settings = localSettings();
        settings.setApiKeySet(true);
        OpenAiCompatibleAiProcessManager pm = start("key-3", answering("a"), settings, forgetful, secrets);
        assertNotNull(secrets.get());

        assertEquals(Boolean.FALSE, secrets.get().saveKeyAsync("sk-1".toCharArray()).get(5, TimeUnit.SECONDS));
        runTurn(pm, "hello");

        assertEquals(OpenAiCompatibleAiProcessManager.KEY_UNAVAILABLE_HINT, pm.keyHintForTest());
    }

    @Test
    @Timeout(20)
    void aRejectedKeyShowsTheHintAndASuccessfulRequestClearsIt() throws Exception {
        AtomicBoolean reject = new AtomicBoolean(true);
        ChatClient client = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                -> reject.get()
                   ? failing(MappedError.Kind.KEY_REJECTED, "The server rejected the API key.")
                        .send(request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                   : answering("fine").send(request, flags, apiKey, headers, limits, handle, onTextDelta, debug,
                                            keySituation);
        OpenAiCompatibleAiProcessManager pm = start("key-4", client, localSettings());
        assertNull(pm.keyHintForTest());

        runTurn(pm, "hello");
        assertEquals(OpenAiCompatibleAiProcessManager.KEY_REJECTED_HINT, pm.keyHintForTest());

        reject.set(false);
        runTurn(pm, "hello again");
        assertNull(pm.keyHintForTest());
    }

    @Test
    @Timeout(20)
    void noKeySentWithoutConfiguredKeyShowsNotSetHint() throws Exception {
        ChatClient noKeyClient = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                -> CompletableFuture.completedFuture(new ClientResult(null, ClientResult.End.HTTP_ERROR,
                                                                      ErrorMapper.map(401, "unauthorized", keySituation, "http://localhost:1", "model-x"), false));
        OpenAiCompatibleAiProcessManager pm = start("key-no-key", noKeyClient, localSettings());

        runTurn(pm, "hello");

        assertEquals(OpenAiCompatibleAiProcessManager.KEY_NOT_SET_HINT, pm.keyHintForTest());
    }

    @Test
    @Timeout(20)
    void effectiveKeyWithUnknownStateUsesUnavailableHint() {
        SecretStore broken = new SecretStore() {
            @Override
            public char[] read(String key) {
                throw new IllegalStateException("keyring locked");
            }

            @Override
            public void save(String key, char[] value, String description) {
            }

            @Override
            public void delete(String key) {
            }
        };
        OpenAiCompatibleSessionSettings settings = localSettings();
        settings.setApiKeySet(true);

        OpenAiCompatibleAiProcessManager pm = start("key-unknown-unavail", answering("a"), settings, broken, null);

        assertEquals(OpenAiCompatibleAiProcessManager.KEY_UNAVAILABLE_HINT, pm.keyHintForTest());
    }

    @Test
    @Timeout(20)
    void reloadingSameSettingsClearsAStaleKeyHint() throws Exception {
        AtomicBoolean reject = new AtomicBoolean(true);
        ChatClient client = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                -> reject.get()
                   ? failing(MappedError.Kind.KEY_REJECTED, "Rejected")
                        .send(request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                   : answering("ok").send(request, flags, apiKey, headers, limits, handle, onTextDelta, debug,
                                          keySituation);
        OpenAiCompatibleSessionSettings settings = localSettings();
        OpenAiCompatibleAiProcessManager pm = start("key-reload", client, settings);
        runTurn(pm, "hello");
        assertEquals(OpenAiCompatibleAiProcessManager.KEY_REJECTED_HINT, pm.keyHintForTest());

        pm.onSessionSettingsChanged(settings);
        assertNull(pm.keyHintForTest());
    }

    @Test
    @Timeout(20)
    void aServerThatNeedsAKeyShowsTheNeededHint() throws Exception {
        OpenAiCompatibleAiProcessManager pm = start("key-5",
                                                    failing(MappedError.Kind.KEY_REQUIRED, "This server requires an API key."), localSettings());

        runTurn(pm, "hello");

        assertEquals(OpenAiCompatibleAiProcessManager.KEY_NEEDED_HINT, pm.keyHintForTest());
    }

    @Test
    @Timeout(20)
    void aSecretHeaderWithoutItsValueShowsTheHeaderHint() throws Exception {
        OpenAiCompatibleSessionSettings settings = localSettings();
        settings.setExtraHeaderNames(List.of("X-Api-Token"));

        OpenAiCompatibleAiProcessManager pm = start("key-6", answering("a"), settings);

        assertEquals("Header X-Api-Token needs its value on this computer", pm.keyHintForTest());
    }

    // ---- flags ----
    @Test
    @Timeout(20)
    void flagsStartConservativeForAnUnknownServerAndWhatARequestLearnsCarriesOver() throws Exception {
        List<CompatibilityFlags> seen = new CopyOnWriteArrayList<>();
        List<Boolean> streamUsage = new CopyOnWriteArrayList<>();
        List<Boolean> reasoning = new CopyOnWriteArrayList<>();
        ChatClient client = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation) -> {
            seen.add(flags);
            streamUsage.add(flags.supportsStreamUsage());
            reasoning.add(flags.supportsReasoningEffort());
            flags.turnOff(CompatibilityFlags.STREAM_OPTIONS);
            return answering("a").send(request, flags, apiKey, headers, limits, handle, onTextDelta, debug,
                                       keySituation);
        };
        OpenAiCompatibleAiProcessManager pm = start("flags-1", client, localSettings());

        runTurn(pm, "one");
        runTurn(pm, "two");

        assertEquals(List.of(true, false), streamUsage, "the second turn starts without the rejected field");
        assertEquals(List.of(false, false), reasoning, "an unknown server is treated conservatively");
        assertFalse(seen.get(0) == seen.get(1), "each turn works on its own copy");
        assertFalse(pm.decisionsForTest().supportsStreamUsage());
        assertTrue(pm.decisionsForTest().supportsReasoningEffort(),
                   "the baseline's withheld field is not recorded as a rejection");
    }

    // ---- streaming ----
    @Test
    @Timeout(20)
    void aNonStreamingAnswerIsRecordedOnceAndShownInTheSnapshot() throws Exception {
        ChatClient client = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                -> CompletableFuture.completedFuture(new ClientResult(
                        new AssembledReply("whole", List.of(), "stop", null, null, true, true),
                        ClientResult.End.COMPLETE, null, false).withNonStreaming(true));
        OpenAiCompatibleAiProcessManager pm = start("stream-1", client, localSettings());
        AtomicInteger changes = new AtomicInteger();
        pm.addListener(changes::incrementAndGet);
        assertNull(pm.snapshot().streaming());

        runTurn(pm, "one");
        int afterFirst = changes.get();
        runTurn(pm, "two");

        assertEquals(Boolean.FALSE, pm.decisionsForTest().streaming());
        assertEquals(Boolean.FALSE, pm.snapshot().streaming());
        assertTrue(afterFirst >= 1);
        assertTrue(pm.decisionsForTest().recordNonStreaming() == false, "it was already recorded");
    }

    // ---- live Detected snapshot ----
    @Test
    @Timeout(20)
    void theSnapshotIsRegisteredWhileRunningAndListenersHearAboutTurnEnds() throws Exception {
        OpenAiCompatibleAiProcessManager pm = start("snap-1", answering("a"), localSettings());
        assertSame(pm, DetectedSnapshotSources.forSession("snap-1"));
        AtomicInteger changes = new AtomicInteger();
        Runnable listener = changes::incrementAndGet;
        pm.addListener(listener);

        runTurn(pm, "one");

        assertTrue(changes.get() >= 1, "a turn end tells the listeners");
        DetectedSnapshot snapshot = pm.snapshot();
        assertEquals("Auto", snapshot.toolCallingMode());
        assertEquals("Drop", snapshot.compactionStrategy());
        assertEquals(0, snapshot.prunedCount());
        assertNotNull(snapshot.window());

        pm.removeListener(listener);
        int before = changes.get();
        runTurn(pm, "two");
        assertEquals(before, changes.get(), "a removed listener hears nothing");

        pm.stop();
        assertNull(DetectedSnapshotSources.forSession("snap-1"));
    }

    @Test
    @Timeout(20)
    void aFailingListenerDoesNotAffectTheSessionOrOtherListeners() throws Exception {
        OpenAiCompatibleAiProcessManager pm = start("snap-2", answering("a"), localSettings());
        AtomicInteger good = new AtomicInteger();
        pm.addListener(() -> {
            throw new IllegalStateException("broken panel");
        });
        pm.addListener(good::incrementAndGet);

        runTurn(pm, "one");

        assertTrue(good.get() >= 1);
        assertEquals(2, pm.historyForTest().size());
    }

    @Test
    @Timeout(20)
    void theSnapshotReportsTheConfiguredToolCallingModeAndStrategy() {
        OpenAiCompatibleSessionSettings settings = localSettings();
        settings.setToolCallingMode(ToolCallingModeEnum.NATIVE);
        settings.setCompactionStrategy(kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.CompactionStrategyEnum.NONE);

        OpenAiCompatibleAiProcessManager pm = start("snap-3", answering("a"), settings);
        DetectedSnapshot snapshot = pm.snapshot();

        assertEquals("Native", snapshot.toolCallingMode());
        assertEquals("None", snapshot.compactionStrategy());
    }
}

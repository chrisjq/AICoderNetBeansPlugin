package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ClientResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedFacts;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerKind;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.events.OpenAiCompatibleContextUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SecretStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SessionSecrets;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.ChatClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The detection poller's wiring in the manager: probes carry the session's API key once the Keyring has been read, the
 * poller callback uses the settings in force now rather than a copy from join time, and the keep-alive is sent only to
 * a detected Ollama.
 */
class OpenAiCompatiblePollerWiringTest {

    private static final OpenAiCompatibleLimits LIMITS = OpenAiCompatibleLimits.defaults()
            .withCheckInterval(Duration.ofMillis(20))
            .withFirstByteTimeout(Duration.ofMillis(300))
            .withIdleTimeout(Duration.ofMillis(300))
            .withTotalCap(Duration.ofSeconds(2))
            .withRetryBackoff(Duration.ofMillis(50))
            .withProbeTimeout(Duration.ofMillis(100));

    private static final String KEY = "sk-test-key";

    /**
     * A Keyring with one stored session key.
     */
    private static SecretStore storeWithKey() {
        return new SecretStore() {
            @Override
            public char[] read(String key) {
                // The session's own key is stored as "aicoder.openai_compatible.<sessionId>"; headers and the
                // plugin default are not.
                boolean sessionKey = key.startsWith("aicoder.openai_compatible.oc-poller-") && !key.contains(".header.");
                return sessionKey ? KEY.toCharArray() : null;
            }

            @Override
            public void save(String key, char[] value, String description) {
            }

            @Override
            public void delete(String key) {
            }
        };
    }

    /**
     * Holds Keyring reads until released, like a Keyring that answers after the session started.
     */
    private static final class HeldExecutor implements Executor {

        final List<Runnable> held = new CopyOnWriteArrayList<>();

        @Override
        public void execute(Runnable command) {
            held.add(command);
        }

        void release() {
            List<Runnable> now = new ArrayList<>(held);
            held.clear();
            now.forEach(Runnable::run);
        }
    }

    @TempDir
    Path baseDir;

    private final List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
    private final List<String> joinedHashes = new CopyOnWriteArrayList<>();
    private final List<String> joinedKeys = new CopyOnWriteArrayList<>();
    private OpenAiCompatibleAiProcessManager manager;
    private volatile Runnable duringJoin;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    private static ChatClient answering() {
        return (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                -> CompletableFuture.completedFuture(new ClientResult(
                        new AssembledReply("Hi.", List.of(), "stop", null, null, true, true),
                        ClientResult.End.COMPLETE, null, false));
    }

    private static OpenAiCompatibleSessionSettings settings(Integer override, boolean apiKeySet) {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setBaseUrl("http://localhost:1");
        settings.setModel("model-x");
        settings.setApiKeySet(apiKeySet);
        settings.setContextWindowOverride(override);
        return settings;
    }

    private OpenAiCompatibleAiProcessManager started(OpenAiCompatibleSessionSettings settings, SecretStore store,
                                                     Executor keyringExecutor) {
        OpenAiCompatibleAiProcessManager created = new OpenAiCompatibleAiProcessManager(
                events::add, limits -> answering(),
                (id, keySet) -> new SessionSecrets(id, store, keyringExecutor, keySet), LIMITS);
        created.setContextDirResolverForTest(id -> {
            Path dir = baseDir.resolve(id);
            Files.createDirectories(dir);
            return dir;
        });
        created.setPollerJoinObserverForTest((hash, key) -> {
            joinedHashes.add(hash);
            joinedKeys.add(key == null ? "<none>" : new String(key));
            Runnable hook = duringJoin;
            if (hook != null) {
                duringJoin = null;
                hook.run();
            }
        });
        AiSession session = new AiSession("oc-poller-" + System.nanoTime(), "OC", null, AiTypeEnum.OPENAI_COMPATIBLE,
                                          null, settings, Instant.now(), Instant.now());
        created.startWithoutMcpForTest(session);
        manager = created;
        return created;
    }

    // ---- 1. the probes carry the key ----
    @Test
    @Timeout(10)
    void aKeyAlreadyReadFromTheKeyringIsSentWithTheProbes() {
        started(settings(null, true), storeWithKey(), Runnable::run);

        assertEquals(KEY, joinedKeys.get(joinedKeys.size() - 1), "the poller is joined with the session's key");
        assertFalse(joinedHashes.get(joinedHashes.size() - 1).isEmpty(), "and keyed by its hash");
    }

    @Test
    @Timeout(10)
    void aKeyThatArrivesAfterTheStartJoinsThePollerAgainWithIt() {
        HeldExecutor keyring = new HeldExecutor();
        started(settings(null, true), storeWithKey(), keyring);
        assertEquals(List.of("<none>"), joinedKeys, "joined without a key while the Keyring is still being read");

        keyring.release();

        assertEquals(KEY, joinedKeys.get(joinedKeys.size() - 1), "joined again once the key arrived");
        assertEquals(2, joinedKeys.size());
    }

    @Test
    @Timeout(10)
    void aSessionWithoutAKeyIsNotJoinedAgain() {
        HeldExecutor keyring = new HeldExecutor();
        SecretStore empty = new SecretStore() {
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
        started(settings(null, false), empty, keyring);

        keyring.release();

        assertEquals(List.of("<none>"), joinedKeys);
    }

    // ---- 2. the callback reads the settings in force now ----
    @Test
    @Timeout(10)
    void aPollerReportUsesTheSettingsInForceNotTheJoinTimeCopy() {
        OpenAiCompatibleSessionSettings atJoin = settings(150_000, false);
        OpenAiCompatibleAiProcessManager pm = started(atJoin, storeWithKey(), Runnable::run);
        // Same URL, model, key flag and type: the unchanged-signature path, which does not join again.
        OpenAiCompatibleSessionSettings changed = settings(64_000, false);
        pm.onSessionSettingsChanged(changed);
        int joinsBefore = joinedKeys.size();
        events.clear();

        pm.onPollerReport(DetectedFacts.of("any-generation"), true, atJoin);

        assertEquals(joinsBefore, joinedKeys.size(), "the unchanged-signature path did not join again");
        OpenAiCompatibleContextUsageEvent usage = events.stream()
                .filter(OpenAiCompatibleContextUsageEvent.class::isInstance)
                .map(OpenAiCompatibleContextUsageEvent.class::cast)
                .reduce((first, second) -> second).orElseThrow();
        assertEquals(64_000, usage.window(), "the new override reaches the poller path");
    }

    // ---- 2b. keyed detection is accepted (generation follows the join) ----
    private static OpenAiCompatibleSessionSettings lmStudioSettings() {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setBaseUrl("http://10.0.0.45:1234");
        settings.setServerType(ServerTypeEnum.LM_STUDIO);
        settings.setModel("m");
        settings.setApiKeySet(true);
        return settings;
    }

    @Test
    @Timeout(10)
    void keyedFactsAreAcceptedAfterTheKeyArrivesLate() {
        HeldExecutor keyring = new HeldExecutor();
        OpenAiCompatibleAiProcessManager pm = started(lmStudioSettings(), storeWithKey(), keyring);
        assertEquals(List.of(""), joinedHashes, "joined without a key while the Keyring is being read");

        keyring.release();

        assertEquals(2, joinedHashes.size(), "joined again with the key");
        assertFalse(joinedHashes.get(1).isEmpty());
        events.clear();
        pm.onPollerReport(DetectedFacts.of(DetectedFacts.generation("http://10.0.0.45:1234", "m", joinedHashes.get(1)))
                .withKind(ServerKind.LMSTUDIO)
                .withWindowReadings(List.of(new WindowReading(150_000, WindowReading.Source.LMSTUDIO_LOADED, true))),
                          true, lmStudioSettings());

        OpenAiCompatibleContextUsageEvent usage = events.stream()
                .filter(OpenAiCompatibleContextUsageEvent.class::isInstance)
                .map(OpenAiCompatibleContextUsageEvent.class::cast)
                .reduce((first, second) -> second).orElseThrow();
        assertEquals(150_000, usage.window(), "the keyed reading is used, not the assumed 8,192");
    }

    @Test
    @Timeout(10)
    void aKeyThatLoadsDuringTheFirstJoinStillGetsAKeyedJoin() {
        HeldExecutor keyring = new HeldExecutor();
        duringJoin = keyring::release;

        started(lmStudioSettings(), storeWithKey(), keyring);

        assertEquals(2, joinedHashes.size(), "a second, keyed join happens");
        assertEquals("", joinedHashes.get(0));
        assertFalse(joinedHashes.get(1).isEmpty());
    }

    @Test
    @Timeout(10)
    void detectionRefusedWith401ShowsTheKeyHintNotATypeMismatch() {
        OpenAiCompatibleAiProcessManager pm = started(lmStudioSettings(), storeWithKey(), Runnable::run);
        String generation = DetectedFacts.generation("http://10.0.0.45:1234", "m", joinedHashes.get(joinedHashes.size() - 1));

        pm.onPollerReport(DetectedFacts.of(generation).withKind(ServerKind.GENERIC).withProbesUnauthorized(true), true,
                          lmStudioSettings());
        assertEquals(OpenAiCompatibleAiProcessManager.KEY_REJECTED_HINT, pm.keyHintForTest());

        pm.onPollerReport(DetectedFacts.of(generation).withKind(ServerKind.LMSTUDIO), true, lmStudioSettings());
        assertNull(pm.keyHintForTest(), "a later snapshot that is not refused clears the hint");
    }

    @Test
    @Timeout(10)
    void aChatKeyRejectionSurvivesALaterDetectionReport() {
        OpenAiCompatibleAiProcessManager pm = started(lmStudioSettings(), storeWithKey(), Runnable::run);
        pm.setKeyResultHintForTest(OpenAiCompatibleAiProcessManager.KEY_REJECTED_HINT);

        pm.onPollerReport(DetectedFacts.of("non-refused").withKind(ServerKind.LMSTUDIO), true, lmStudioSettings());

        assertEquals(OpenAiCompatibleAiProcessManager.KEY_REJECTED_HINT, pm.keyHintForTest());
    }

    // ---- 3. keep-alive only for a detected Ollama ----
    @Test
    @Timeout(5)
    void keepAliveIsForADetectedOllamaOnly() {
        assertFalse(OpenAiCompatibleAiProcessManager.isDetectedOllama(null), "no facts yet: not Ollama");
        assertTrue(OpenAiCompatibleAiProcessManager.isDetectedOllama(
                DetectedFacts.of("g").withKind(ServerKind.OLLAMA)));
        assertFalse(OpenAiCompatibleAiProcessManager.isDetectedOllama(
                DetectedFacts.of("g").withKind(ServerKind.LMSTUDIO)));
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SessionSecretsTest {

    private ExecutorService executor;

    @BeforeEach
    void isolatePreferences() throws Exception {
        SessionSecrets.useForTest(Preferences.userRoot().node("/aicoder-test-" + System.nanoTime()));
    }

    @AfterEach
    void tearDown() {
        OpenAiCompatiblePluginSettings.useForTest(null);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static void defaultUrl(String url) {
        Preferences prefs = Preferences.userRoot().node("/aicoder-defaults-" + System.nanoTime());
        OpenAiCompatiblePluginSettings.useForTest(prefs);
        OpenAiCompatiblePluginSettings.setBaseUrl(url);
    }

    @Test
    @Timeout(10)
    void keyHashIsStableAndNeverContainsKey() {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        var secrets = new SessionSecrets("hash", store, executor, false);
        assertTrue(secrets.saveKeyAsync("secret-value".toCharArray()).join());
        String hash = secrets.keyHash();
        assertEquals(16, hash.length());
        assertFalse(hash.contains("secret"));
        assertEquals(hash, secrets.keyHash());
        secrets.close();
    }

    @Test
    @Timeout(10)
    void batchReadLoadsKeyAndHeaders() {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        store.put("aicoder.openai_compatible.s1", "key");
        store.put("aicoder.openai_compatible.s1.header.authorization", "bearer");
        var secrets = new SessionSecrets("s1", store, executor, true);
        secrets.loadAsync(List.of("Authorization")).join();
        assertArrayEquals("key".toCharArray(), secrets.apiKey(Duration.ofSeconds(1)).orElseThrow());
        assertArrayEquals("bearer".toCharArray(), secrets.header("authorization").orElseThrow());
        assertEquals(SessionSecrets.KeyState.PRESENT, secrets.keyState());
        secrets.close();
    }

    @Test
    @Timeout(10)
    void lockedReadTimesOutAndMarksUnavailable() {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        store.setLocked(true);
        var secrets = new SessionSecrets("s2", store, executor, true);
        secrets.loadAsync(List.of());
        long start = System.nanoTime();
        assertTrue(secrets.apiKey(Duration.ofMillis(50)).isEmpty());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
        assertEquals(SessionSecrets.KeyState.UNAVAILABLE, secrets.keyState());
        store.release();
        secrets.close();
    }

    @Test
    @Timeout(10)
    void missingKeyIsReportedWhenFlagSet() {
        executor = Executors.newSingleThreadExecutor();
        var secrets = new SessionSecrets("s3", new FakeSecretStore(), executor, true);
        secrets.loadAsync(List.of()).join();
        assertEquals(SessionSecrets.KeyState.MISSING_ON_THIS_COMPUTER, secrets.keyState());
        secrets.close();
    }

    @Test
    @Timeout(10)
    void missingKeyIsNotSetWhenFlagUnset() {
        executor = Executors.newSingleThreadExecutor();
        var secrets = new SessionSecrets("s3b", new FakeSecretStore(), executor, false);
        secrets.loadAsync(List.of()).join();
        assertEquals(SessionSecrets.KeyState.NOT_SET, secrets.keyState());
        secrets.clearKeyAsync().join();
        assertEquals(SessionSecrets.KeyState.NOT_SET, secrets.keyState());
        secrets.close();
    }

    @Test
    @Timeout(10)
    void lossySaveKeepsInMemoryValueAndReportsFailure() {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        store.setLossy(true);
        var secrets = new SessionSecrets("s4", store, executor, false);
        assertFalse(secrets.saveKeyAsync("key".toCharArray()).join());
        assertArrayEquals("key".toCharArray(), secrets.apiKey(Duration.ZERO).orElseThrow());
        assertEquals(SessionSecrets.KeyState.UNAVAILABLE, secrets.keyState());
        secrets.close();
    }

    @Test
    @Timeout(10)
    void normalSaveAndDeleteAll() {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        var secrets = new SessionSecrets("s5", store, executor, false);
        assertTrue(secrets.saveKeyAsync("key".toCharArray()).join());
        assertTrue(secrets.saveHeaderAsync("X-Token", "header".toCharArray()).join());
        SessionSecrets.deleteAllAsync("s5", store, Runnable::run).join();
        assertFalse(store.contains("aicoder.openai_compatible.s5"));
        assertFalse(store.contains("aicoder.openai_compatible.s5.header.x-token"));
        secrets.close();
    }

    @Test
    @Timeout(10)
    void trackedEntriesDeleteAndCleanupHandleMultipleSessions() {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        var a = new SessionSecrets("multi-a", store, executor, false);
        var b = new SessionSecrets("multi-b", store, executor, false);
        var c = new SessionSecrets("multi-c", store, executor, false);
        assertTrue(a.saveKeyAsync("a".toCharArray()).join());
        assertTrue(a.saveHeaderAsync("X-A", "ha".toCharArray()).join());
        assertTrue(b.saveKeyAsync("b".toCharArray()).join());
        assertTrue(b.saveHeaderAsync("X-B", "hb".toCharArray()).join());
        assertTrue(c.saveKeyAsync("c".toCharArray()).join());
        SessionSecrets.deleteAllAsync("multi-b", store, Runnable::run).join();
        assertTrue(store.contains("aicoder.openai_compatible.multi-a"));
        assertFalse(store.contains("aicoder.openai_compatible.multi-b"));
        assertTrue(store.contains("aicoder.openai_compatible.multi-c"));
        SessionSecrets.cleanupOrphansAsync(List.of("multi-a"), null, store, Runnable::run).join();
        assertTrue(store.contains("aicoder.openai_compatible.multi-a"));
        assertFalse(store.contains("aicoder.openai_compatible.multi-c"));
        a.close();
        b.close();
        c.close();
    }

    @Test
    @Timeout(10)
    void orphanCleanupKeepsLiveEntries() {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        store.put("aicoder.openai_compatible.live", "a");
        store.put("aicoder.openai_compatible.dead", "b");
        SessionSecrets.cleanupOrphansAsync(List.of("live"),
                                           List.of("aicoder.openai_compatible.live", "aicoder.openai_compatible.dead"),
                                           store, Runnable::run).join();
        assertTrue(store.contains("aicoder.openai_compatible.live"));
        assertFalse(store.contains("aicoder.openai_compatible.dead"));
    }

    @Test
    @Timeout(10)
    void closeZeroFillsValues() {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        var secrets = new SessionSecrets("s6", store, executor, false);
        secrets.saveKeyAsync("key".toCharArray()).join();
        char[] held = secrets.apiKey(Duration.ZERO).orElseThrow();
        secrets.close();
        assertArrayEquals(new char[]{0, 0, 0}, held);
    }

    @Test
    @Timeout(10)
    void sameRootUsesTheDefaultKey() {
        executor = Executors.newSingleThreadExecutor();
        defaultUrl("https://api.example.com/v1");
        FakeSecretStore store = new FakeSecretStore();
        store.put("aicoder.openai_compatible.default", "dkey");
        var secrets = new SessionSecrets("d1", store, executor, false);
        secrets.loadAsync(List.of()).join();
        Optional<char[]> key = secrets.apiKeyFor(Duration.ofSeconds(1), "https://API.example.com/v1/chat/completions");
        assertArrayEquals("dkey".toCharArray(), key.orElseThrow());
        assertTrue(secrets.usingDefaultKey());
        secrets.close();
    }

    @Test
    @Timeout(10)
    void differentRootGetsNoKey() {
        executor = Executors.newSingleThreadExecutor();
        defaultUrl("https://api.example.com/v1");
        FakeSecretStore store = new FakeSecretStore();
        store.put("aicoder.openai_compatible.default", "dkey");
        var secrets = new SessionSecrets("d2", store, executor, false);
        secrets.loadAsync(List.of()).join();
        assertTrue(secrets.apiKeyFor(Duration.ofSeconds(1), "https://other.example.com/v1").isEmpty());
        assertFalse(secrets.usingDefaultKey());
        secrets.close();
    }

    @Test
    @Timeout(10)
    void ownKeyWinsOverTheDefaultKey() {
        executor = Executors.newSingleThreadExecutor();
        defaultUrl("https://api.example.com/v1");
        FakeSecretStore store = new FakeSecretStore();
        store.put("aicoder.openai_compatible.default", "dkey");
        var secrets = new SessionSecrets("d3", store, executor, false);
        secrets.loadAsync(List.of()).join();
        assertTrue(secrets.saveKeyAsync("skey".toCharArray()).join());
        assertArrayEquals("skey".toCharArray(),
                          secrets.apiKeyFor(Duration.ofSeconds(1), "https://api.example.com/v1").orElseThrow());
        assertFalse(secrets.usingDefaultKey());
        secrets.close();
    }

    @Test
    @Timeout(10)
    void sessionFlaggedWithAKeyNeverFallsBackToTheDefault() {
        executor = Executors.newSingleThreadExecutor();
        defaultUrl("https://api.example.com/v1");
        FakeSecretStore store = new FakeSecretStore();
        store.put("aicoder.openai_compatible.default", "dkey");
        var secrets = new SessionSecrets("d4", store, executor, true);
        secrets.loadAsync(List.of()).join();
        assertTrue(secrets.apiKeyFor(Duration.ofSeconds(1), "https://api.example.com/v1").isEmpty());
        assertFalse(secrets.usingDefaultKey());
        secrets.close();
    }
}

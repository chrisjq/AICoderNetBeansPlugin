package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SessionSecretsTest {

    private ExecutorService executor;

    @BeforeEach
    void isolatePreferences() throws Exception {
        SessionSecrets.useForTest(MemoryPreferences.create());
        SessionSecrets.clearPendingSavesForTest();
    }

    @AfterEach
    void tearDown() {
        OpenAiCompatiblePluginSettings.useForTest(null);
        if (executor != null) {
            executor.shutdownNow();
        }
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
    void loadingANewInstanceWaitsForAPendingKeySave() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        Deque<Runnable> delayedSaves = new ArrayDeque<>();
        SessionSecrets writer = new SessionSecrets("race", store, delayedSaves::add, false);
        SessionSecrets reader = new SessionSecrets("race", store, executor, true);

        writer.saveKeyAsync("sk-race".toCharArray());
        var load = reader.loadAsync(List.of());
        assertFalse(load.isDone(), "the new instance must not read before the queued save finishes");

        delayedSaves.removeFirst().run();
        load.get(5, TimeUnit.SECONDS);

        assertArrayEquals("sk-race".toCharArray(), reader.apiKey(Duration.ofSeconds(1)).orElseThrow());
        writer.close();
        reader.close();
    }

    @Test
    @Timeout(10)
    void failedPendingSaveDoesNotFailAConcurrentLoad() throws Exception {
        AtomicReference<Thread> loadThread = new AtomicReference<>();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            loadThread.set(t);
            return t;
        });
        Deque<Runnable> delayedSaves = new ArrayDeque<>();
        SecretStore store = new SecretStore() {
            @Override
            public char[] read(String key) {
                return null;
            }

            @Override
            public void save(String key, char[] value, String description) {
                throw new IllegalStateException("save failed");
            }

            @Override
            public void delete(String key) {
            }
        };
        SessionSecrets writer = new SessionSecrets("failed-race", store, delayedSaves::add, false);
        SessionSecrets reader = new SessionSecrets("failed-race", store, executor, true);

        var save = writer.saveKeyAsync("key".toCharArray());
        var load = reader.loadAsync(List.of());
        assertFalse(load.isDone());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread t = loadThread.get();
            if (t != null) {
                Thread.State state = t.getState();
                if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                    break;
                }
            }
            Thread.sleep(5);
        }
        assertFalse(delayedSaves.isEmpty());
        delayedSaves.removeFirst().run();

        save.handle((ignored, error) -> null).join();
        load.join();
        assertEquals(SessionSecrets.KeyState.UNAVAILABLE, reader.keyState());
        assertEquals(SessionSecrets.UnavailableReason.KEYRING_ERROR, reader.unavailableReason());
        writer.close();
        reader.close();
    }

    @Test
    @Timeout(10)
    void pendingSaveTimeoutMarksUnavailable() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        Preferences prefs = MemoryPreferences.create();
        OpenAiCompatiblePluginSettings.useForTest(prefs);
        OpenAiCompatiblePluginSettings.setKeyringLoadTimeout(Duration.ofMillis(10));
        Deque<Runnable> delayedSaves = new ArrayDeque<>();
        SecretStore store = new SecretStore() {
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
        SessionSecrets writer = new SessionSecrets("timeout-race", store, delayedSaves::add, false);
        SessionSecrets reader = new SessionSecrets("timeout-race", store, executor, true);

        var save = writer.saveKeyAsync("key".toCharArray());
        var load = reader.loadAsync(List.of("Authorization"));
        assertFalse(load.isDone());

        try {
            load.get(2, TimeUnit.SECONDS);
        }
        catch (Exception ignored) {
        }

        assertEquals(SessionSecrets.KeyState.UNAVAILABLE, reader.keyState());
        assertEquals(SessionSecrets.UnavailableReason.KEYRING_ERROR, reader.unavailableReason());
        assertTrue(reader.header("authorization").isEmpty());
        if (!delayedSaves.isEmpty()) {
            delayedSaves.removeFirst().run();
        }
        save.join();
        writer.close();
        reader.close();
    }

    @Test
    @Timeout(10)
    void pendingSaveTimeoutStillLoadsHeaders() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        Preferences prefs = MemoryPreferences.create();
        OpenAiCompatiblePluginSettings.useForTest(prefs);
        OpenAiCompatiblePluginSettings.setKeyringLoadTimeout(Duration.ofMillis(10));
        Deque<Runnable> delayedSaves = new ArrayDeque<>();
        SecretStore store = new SecretStore() {
            @Override
            public char[] read(String key) {
                if (key.endsWith(".header.authorization")) {
                    return "bearer".toCharArray();
                }
                return null;
            }

            @Override
            public void save(String key, char[] value, String description) {
            }

            @Override
            public void delete(String key) {
            }
        };
        SessionSecrets writer = new SessionSecrets("timeout-headers", store, delayedSaves::add, false);
        SessionSecrets reader = new SessionSecrets("timeout-headers", store, executor, true);

        var save = writer.saveKeyAsync("key".toCharArray());
        var load = reader.loadAsync(List.of("Authorization"));
        assertFalse(load.isDone());

        try {
            load.get(2, TimeUnit.SECONDS);
        }
        catch (Exception ignored) {
        }

        assertEquals(SessionSecrets.KeyState.UNAVAILABLE, reader.keyState());
        assertEquals(SessionSecrets.UnavailableReason.KEYRING_ERROR, reader.unavailableReason());
        assertArrayEquals("bearer".toCharArray(), reader.header("authorization").orElseThrow());
        if (!delayedSaves.isEmpty()) {
            delayedSaves.removeFirst().run();
        }
        save.join();
        writer.close();
        reader.close();
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
    void closeDoesNotMutateCallerKeyCopy() {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        var secrets = new SessionSecrets("s6", store, executor, false);
        secrets.saveKeyAsync("key".toCharArray()).join();
        char[] held = secrets.apiKey(Duration.ZERO).orElseThrow();
        secrets.close();
        assertArrayEquals("key".toCharArray(), held);
    }

    @Test
    @Timeout(10)
    void ownKeyIsReturned() {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        var secrets = new SessionSecrets("d3", store, executor, false);
        secrets.loadAsync(List.of()).join();
        assertTrue(secrets.saveKeyAsync("skey".toCharArray()).join());
        assertArrayEquals("skey".toCharArray(), secrets.apiKey(Duration.ofSeconds(1)).orElseThrow());
        secrets.close();
    }
}

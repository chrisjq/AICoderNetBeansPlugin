package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.BackendExecutors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.BaseUrls;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import org.openide.util.NbPreferences;

public final class SessionSecrets implements AutoCloseable {

    private static final String PREFIX = "aicoder.openai_compatible.";
    private static final String DEFAULT_KEY_NAME = "aicoder.openai_compatible.default";
    private static final String ENTRIES_KEY = "ai.openai_compatible.keyringEntries";
    private static final String DEFAULT_ID = "default";
    private static volatile Preferences PREFS = NbPreferences.forModule(PluginSettings.class);
    private static final SecretStore DEFAULT_STORE = new NbKeyringSecretStore();

    public enum KeyState {
        UNKNOWN_YET,
        PRESENT,
        MISSING_ON_THIS_COMPUTER,
        NOT_SET,
        UNAVAILABLE
    }

    /**
     * Why the Keyring was unavailable (spec §8.6).
     */
    public enum UnavailableReason {
        NONE,
        KEYRING_ERROR,
        NOT_PERSISTED
    }

    private final String sessionId;
    private final SecretStore store;
    private final Executor executor;
    private final boolean apiKeySet;
    private final Object lock = new Object();
    private final Map<String, char[]> headers = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicReference<KeyState> state = new AtomicReference<>(KeyState.UNKNOWN_YET);
    private volatile char[] apiKey;
    private volatile char[] defaultKey;
    private volatile boolean usingDefault;
    private volatile UnavailableReason unavailableReason = UnavailableReason.NONE;
    private volatile CompletableFuture<Void> loadFuture;
    private volatile boolean closed;

    public SessionSecrets(String sessionId, SecretStore store, Executor executor, boolean apiKeySet) {
        this.sessionId = sessionId;
        this.store = store;
        this.executor = executor;
        this.apiKeySet = apiKeySet;
    }

    public SessionSecrets(String sessionId, SecretStore store, Executor executor) {
        this(sessionId, store, executor, false);
    }

    public SessionSecrets(String sessionId, boolean apiKeySet) {
        this(sessionId, DEFAULT_STORE, BackendExecutors.probePool(), apiKeySet);
    }

    public CompletableFuture<Void> loadAsync(Collection<String> secretHeaderNames) {
        synchronized (lock) {
            if (loadFuture != null) {
                return loadFuture;
            }
            CompletableFuture<Void> future = new CompletableFuture<>();
            loadFuture = future;
            Set<String> names = new HashSet<>();
            if (secretHeaderNames != null) {
                for (String name : secretHeaderNames) {
                    if (name != null && !name.isBlank()) {
                        names.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
            try {
                executor.execute(() -> {
                    try {
                        if (closed) {
                            markUnavailable(UnavailableReason.KEYRING_ERROR);
                            future.complete(null);
                            return;
                        }
                        char[] loaded = store.read(keyName());
                        if (loaded != null) {
                            apiKey = loaded.clone();
                        }
                        if (!apiKeySet) {
                            // The plugin default is read in the same batch, and only for a session without a key of its own.
                            char[] fallback = store.read(DEFAULT_KEY_NAME);
                            if (fallback != null) {
                                defaultKey = fallback;
                            }
                        }
                        for (String name : names) {
                            char[] value = store.read(headerKeyName(name));
                            if (value != null) {
                                headers.put(name, value.clone());
                            }
                        }
                        state.set(loaded == null ? (apiKeySet ? KeyState.MISSING_ON_THIS_COMPUTER : KeyState.NOT_SET) : KeyState.PRESENT);
                        future.complete(null);
                    }
                    catch (RuntimeException ex) {
                        markUnavailable(UnavailableReason.KEYRING_ERROR);
                        future.completeExceptionally(ex);
                    }
                });
            }
            catch (RuntimeException ex) {
                markUnavailable(UnavailableReason.KEYRING_ERROR);
                future.completeExceptionally(ex);
            }
            return future;
        }
    }

    public Optional<char[]> apiKey(Duration maxWait) {
        CompletableFuture<Void> future = loadFuture;
        if (future == null) {
            return Optional.ofNullable(apiKey);
        }
        try {
            long nanos;
            try {
                nanos = Math.max(0L, maxWait.toNanos());
            }
            catch (ArithmeticException ex) {
                nanos = Long.MAX_VALUE;
            }
            future.get(nanos, TimeUnit.NANOSECONDS);
        }
        catch (TimeoutException ex) {
            markUnavailable(UnavailableReason.KEYRING_ERROR);
            return Optional.empty();
        }
        catch (Exception ex) {
            markUnavailable(UnavailableReason.KEYRING_ERROR);
            return Optional.empty();
        }
        char[] value = apiKey;
        return value == null ? Optional.empty() : Optional.of(value);
    }

    /**
     * The key to send for this session: its own key if it has one, otherwise the plugin default when this session has
     * no key flag set and its root equals the root of the plugin-default base URL (spec §8.6). Sets
     * {@link #usingDefaultKey()} for the panel.
     */
    public Optional<char[]> apiKeyFor(Duration maxWait, String sessionUrl) {
        Optional<char[]> own = apiKey(maxWait);
        if (own.isPresent()) {
            usingDefault = false;
            return own;
        }
        char[] fallback = defaultKey;
        boolean useDefault = !apiKeySet && fallback != null && sameRootAsDefault(sessionUrl);
        usingDefault = useDefault;
        return useDefault ? Optional.of(fallback) : Optional.empty();
    }

    /**
     * True when the last {@link #apiKeyFor} call returned the plugin default key.
     */
    public boolean usingDefaultKey() {
        return usingDefault;
    }

    private static boolean sameRootAsDefault(String sessionUrl) {
        String defaultRoot = BaseUrls.root(OpenAiCompatiblePluginSettings.getBaseUrl());
        return !defaultRoot.isEmpty() && defaultRoot.equals(BaseUrls.root(sessionUrl));
    }

    public String keyHash() {
        char[] key = apiKey != null ? apiKey : defaultKey;
        if (key == null || key.length == 0) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(new String(key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8 && i < digest.length; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException ex) {
            return "";
        }
    }

    public KeyState keyState() {
        return state.get();
    }

    /**
     * Why the Keyring could not be used, meaningful while {@link #keyState()} is {@link KeyState#UNAVAILABLE}:
     * {@link UnavailableReason#KEYRING_ERROR} when a read failed or timed out (a locked or missing keyring),
     * {@link UnavailableReason#NOT_PERSISTED} when a save did not read back (the keyring does not keep secrets).
     * {@link UnavailableReason#NONE} in every other state.
     */
    public UnavailableReason unavailableReason() {
        return state.get() == KeyState.UNAVAILABLE ? unavailableReason : UnavailableReason.NONE;
    }

    private void markUnavailable(UnavailableReason reason) {
        unavailableReason = reason;
        state.set(KeyState.UNAVAILABLE);
    }

    public Optional<char[]> header(String name) {
        if (name == null) {
            return Optional.empty();
        }
        char[] value = headers.get(name.toLowerCase(Locale.ROOT));
        return value == null ? Optional.empty() : Optional.of(value);
    }

    public CompletableFuture<Boolean> saveKeyAsync(char[] key) {
        if (key == null) {
            return CompletableFuture.completedFuture(false);
        }
        return submit(() -> {
            char[] copy = key.clone();
            store.save(keyName(), copy, "OpenAI Compatible API key");
            char[] readBack = store.read(keyName());
            boolean ok = java.util.Arrays.equals(copy, readBack);
            replaceApiKey(copy);
            if (ok) {
                state.set(KeyState.PRESENT);
                track(keyName());
            }
            else {
                markUnavailable(UnavailableReason.NOT_PERSISTED);
            }
            return ok;
        });
    }

    public CompletableFuture<Boolean> saveHeaderAsync(String name, char[] value) {
        if (name == null || value == null) {
            return CompletableFuture.completedFuture(false);
        }
        String normalised = name.toLowerCase(Locale.ROOT);
        return submit(() -> {
            char[] copy = value.clone();
            String key = headerKeyName(normalised);
            store.save(key, copy, "OpenAI Compatible secret header");
            char[] readBack = store.read(key);
            boolean ok = java.util.Arrays.equals(copy, readBack);
            char[] old = headers.put(normalised, copy);
            zero(old);
            if (ok) {
                track(key);
            }
            else {
                markUnavailable(UnavailableReason.NOT_PERSISTED);
            }
            return ok;
        });
    }

    public CompletableFuture<Void> clearKeyAsync() {
        return submit(() -> {
            store.delete(keyName());
            char[] old = apiKey;
            apiKey = null;
            zero(old);
            removeTracked(keyName());
            state.set(KeyState.NOT_SET);
            return null;
        });
    }

    public static CompletableFuture<Void> deleteAllAsync(String sessionId) {
        return deleteAllAsync(sessionId, DEFAULT_STORE, BackendExecutors.probePool());
    }

    public static CompletableFuture<Void> deleteAllAsync(String sessionId, SecretStore store, Executor executor) {
        return CompletableFuture.runAsync(() -> {
            String prefix = PREFIX + sessionId;
            Set<String> entries = trackedEntries();
            for (String key : new ArrayList<>(entries)) {
                if (key.equals(prefix) || key.startsWith(prefix + ".header.")) {
                    store.delete(key);
                    untrack(key);
                }
            }
        }, executor);
    }

    public static CompletableFuture<Void> cleanupOrphansAsync(Collection<String> liveSessionIds,
                                                              Collection<String> storedKeyNames) {
        return cleanupOrphansAsync(liveSessionIds, storedKeyNames, DEFAULT_STORE, BackendExecutors.probePool());
    }

    /**
     * Decides whether a session still exists. Throws {@link IOException} when that cannot be read.
     */
    public interface SessionExistence {

        boolean exists(String sessionId) throws IOException;
    }

    /**
     * Deletes the stored entries of sessions that no longer exist (spec §8.6). The existence check is built on the
     * background thread by {@code existence}; a null result means the sessions cannot be read, and then nothing is
     * deleted. A session whose folder cannot be checked is kept, and the default key is never deleted.
     */
    public static CompletableFuture<Void> cleanupOrphansAsync(Supplier<SessionExistence> existence) {
        return cleanupOrphansAsync(existence, DEFAULT_STORE, BackendExecutors.probePool());
    }

    public static CompletableFuture<Void> cleanupOrphansAsync(Supplier<SessionExistence> existence,
                                                              SecretStore store, Executor executor) {
        return CompletableFuture.runAsync(() -> {
            SessionExistence check = existence.get();
            if (check == null) {
                return;
            }
            for (String key : new ArrayList<>(trackedEntries())) {
                String id = sessionIdFromKey(key);
                if (id == null || DEFAULT_ID.equals(id)) {
                    continue;
                }
                try {
                    if (check.exists(id)) {
                        continue;
                    }
                }
                catch (IOException ex) {
                    continue;
                }
                store.delete(key);
                untrack(key);
            }
        }, executor);
    }

    public static CompletableFuture<Void> cleanupOrphansAsync(Collection<String> liveSessionIds,
                                                              Collection<String> storedKeyNames,
                                                              SecretStore store, Executor executor) {
        Set<String> live = liveSessionIds == null ? Set.of() : Set.copyOf(liveSessionIds);
        Collection<String> names = storedKeyNames == null ? trackedEntries() : storedKeyNames;
        return CompletableFuture.runAsync(() -> {
            for (String key : new ArrayList<>(names)) {
                String id = sessionIdFromKey(key);
                if (id != null && !live.contains(id)) {
                    store.delete(key);
                    untrack(key);
                }
            }
        }, executor);
    }

    private <T> CompletableFuture<T> submit(java.util.function.Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(action.get());
                }
                catch (RuntimeException ex) {
                    markUnavailable(UnavailableReason.KEYRING_ERROR);
                    future.completeExceptionally(ex);
                }
            });
        }
        catch (RuntimeException ex) {
            future.completeExceptionally(ex);
        }
        return future;
    }

    private String keyName() {
        return PREFIX + sessionId;
    }

    private String headerKeyName(String name) {
        return PREFIX + sessionId + ".header." + name;
    }

    private void replaceApiKey(char[] value) {
        char[] old = apiKey;
        apiKey = value;
        zero(old);
    }

    private static String sessionIdFromKey(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            return null;
        }
        String rest = key.substring(PREFIX.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    static void useForTest(Preferences preferences) {
        PREFS = preferences;
    }

    private static Set<String> trackedEntries() {
        String value = PREFS.get(ENTRIES_KEY, "");
        if (value.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String key : value.split(",")) {
            if (!key.isBlank()) {
                result.add(key);
            }
        }
        return result;
    }

    private static synchronized void track(String key) {
        Set<String> entries = new HashSet<>(trackedEntries());
        entries.add(key);
        PREFS.put(ENTRIES_KEY, String.join(",", entries));
    }

    private static void removeTracked(String key) {
        untrack(key);
    }

    private static synchronized void untrack(String key) {
        Set<String> entries = new HashSet<>(trackedEntries());
        if (entries.remove(key)) {
            PREFS.put(ENTRIES_KEY, String.join(",", entries));
        }
    }

    private static void zero(char[] value) {
        if (value != null) {
            java.util.Arrays.fill(value, (char) 0);
        }
    }

    @Override
    public void close() {
        closed = true;
        char[] value = apiKey;
        apiKey = null;
        zero(value);
        char[] fallback = defaultKey;
        defaultKey = null;
        zero(fallback);
        for (char[] header : headers.values()) {
            zero(header);
        }
        headers.clear();
    }
}

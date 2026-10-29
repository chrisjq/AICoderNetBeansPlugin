package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.BackendExecutors;

/**
 * Asynchronous keyring access for reusable OpenAI-compatible server entries.
 */
public final class SavedServerKeys {

    private static final String KEY_PREFIX = "aicoder.openai_compatible_server.";
    private static final String DESCRIPTION = "OpenAI-compatible server API key";

    private final SecretStore store;
    private final Executor executor;

    public SavedServerKeys() {
        this(new NbKeyringSecretStore(), BackendExecutors.probePool());
    }

    public SavedServerKeys(SecretStore store, Executor executor) {
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public CompletableFuture<Boolean> saveAsync(String serverId, char[] key) {
        final char[] original = key == null ? new char[0] : key.clone();
        return CompletableFuture.supplyAsync(() -> {
            char[] saved = original.clone();
            try {
                store.save(keyName(serverId), saved, DESCRIPTION);
                char[] readBack = store.read(keyName(serverId));
                try {
                    return readBack != null && Arrays.equals(original, readBack);
                }
                finally {
                    if (readBack != null) {
                        Arrays.fill(readBack, '\0');
                    }
                }
            }
            finally {
                Arrays.fill(saved, '\0');
                Arrays.fill(original, '\0');
            }
        }, executor);
    }

    public CompletableFuture<Optional<char[]>> readAsync(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            char[] value = store.read(keyName(serverId));
            if (value == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(value.clone());
            }
            finally {
                Arrays.fill(value, '\0');
            }
        }, executor);
    }

    public CompletableFuture<Void> deleteAsync(String serverId) {
        return CompletableFuture.runAsync(() -> store.delete(keyName(serverId)), executor);
    }

    public static String keyName(String serverId) {
        return KEY_PREFIX + Objects.requireNonNull(serverId, "serverId");
    }
}

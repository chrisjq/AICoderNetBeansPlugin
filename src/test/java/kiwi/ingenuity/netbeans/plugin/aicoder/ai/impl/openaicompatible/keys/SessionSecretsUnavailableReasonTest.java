package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SessionSecrets.KeyState;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SessionSecrets.UnavailableReason;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SessionSecretsUnavailableReasonTest {

    private ExecutorService executor;

    @BeforeEach
    void isolatePreferences() {
        SessionSecrets.useForTest(MemoryPreferences.create());
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void aHealthyKeyringHasNoReason() {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        SessionSecrets secrets = new SessionSecrets("healthy", store, executor, false);

        assertEquals(UnavailableReason.NONE, secrets.unavailableReason());
        assertTrue(secrets.saveKeyAsync("sk".toCharArray()).join());
        assertEquals(KeyState.PRESENT, secrets.keyState());
        assertEquals(UnavailableReason.NONE, secrets.unavailableReason());
        secrets.close();
    }

    @Test
    @Timeout(10)
    void aSaveThatDoesNotReadBackIsNotPersisted() {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        store.setLossy(true);
        SessionSecrets secrets = new SessionSecrets("lossy", store, executor, false);

        assertFalse(secrets.saveKeyAsync("sk".toCharArray()).join());

        assertEquals(KeyState.UNAVAILABLE, secrets.keyState());
        assertEquals(UnavailableReason.NOT_PERSISTED, secrets.unavailableReason());
        secrets.close();
    }

    @Test
    @Timeout(10)
    void aHeaderThatDoesNotReadBackIsNotPersistedEither() {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        store.setLossy(true);
        SessionSecrets secrets = new SessionSecrets("lossy-header", store, executor, false);

        assertFalse(secrets.saveHeaderAsync("X-Token", "v".toCharArray()).join());

        assertEquals(UnavailableReason.NOT_PERSISTED, secrets.unavailableReason());
        secrets.close();
    }

    @Test
    @Timeout(10)
    void aKeyringThatCannotBeReadIsAKeyringError() {
        SecretStore broken = new SecretStore() {
            @Override
            public char[] read(String key) {
                throw new IllegalStateException("no keyring service");
            }

            @Override
            public void save(String key, char[] value, String description) {
            }

            @Override
            public void delete(String key) {
            }
        };
        SessionSecrets secrets = new SessionSecrets("broken", broken, Runnable::run, true);

        secrets.loadAsync(List.of());

        assertEquals(KeyState.UNAVAILABLE, secrets.keyState());
        assertEquals(UnavailableReason.KEYRING_ERROR, secrets.unavailableReason());
        secrets.close();
    }

    @Test
    @Timeout(10)
    void aLockedKeyringThatDoesNotAnswerInTimeIsAKeyringError() {
        executor = Executors.newSingleThreadExecutor();
        FakeSecretStore store = new FakeSecretStore();
        store.setLocked(true);
        SessionSecrets secrets = new SessionSecrets("locked", store, executor, true);
        secrets.loadAsync(List.of());

        assertFalse(secrets.apiKey(Duration.ofMillis(50)).isPresent());

        assertEquals(KeyState.UNAVAILABLE, secrets.keyState());
        assertEquals(UnavailableReason.KEYRING_ERROR, secrets.unavailableReason());
        store.release();
        secrets.close();
    }

    @Test
    @Timeout(10)
    void theReasonIsNoneOutsideTheUnavailableState() {
        SessionSecrets secrets = new SessionSecrets("missing", new FakeSecretStore(), Runnable::run, true);
        secrets.loadAsync(List.of());

        assertEquals(KeyState.MISSING_ON_THIS_COMPUTER, secrets.keyState());
        assertEquals(UnavailableReason.NONE, secrets.unavailableReason());
        secrets.close();
    }
}

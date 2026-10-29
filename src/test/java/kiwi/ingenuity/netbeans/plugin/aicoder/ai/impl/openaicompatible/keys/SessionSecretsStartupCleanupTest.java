package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys;

import java.io.IOException;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The startup orphan cleanup: a key is deleted only when its session is gone from both the persisted list and the
 * folder, and nothing is deleted when either cannot be read.
 */
@Timeout(10)
class SessionSecretsStartupCleanupTest {

    @BeforeEach
    void isolatePreferences() {
        SessionSecrets.useForTest(MemoryPreferences.create());
    }

    private static String keyOf(String sessionId) {
        return "aicoder.openai_compatible." + sessionId;
    }

    private static void saveKey(FakeSecretStore store, String sessionId) {
        new SessionSecrets(sessionId, store, Runnable::run, false)
                .saveKeyAsync(("key-" + sessionId).toCharArray()).join();
    }

    private static SessionSecrets.SessionExistence existing(String... sessionIds) {
        Set<String> known = Set.of(sessionIds);
        return known::contains;
    }

    @Test
    void aSavedButNotRunningSessionsKeySurvivesStartupCleanup() {
        FakeSecretStore store = new FakeSecretStore();
        saveKey(store, "saved-1");

        SessionSecrets.cleanupOrphansAsync(() -> existing("saved-1"), store, Runnable::run).join();

        assertNotNull(store.read(keyOf("saved-1")));
    }

    @Test
    void aKeyWhoseSessionIsGoneIsDeleted() {
        FakeSecretStore store = new FakeSecretStore();
        saveKey(store, "gone-1");
        saveKey(store, "saved-1");

        SessionSecrets.cleanupOrphansAsync(() -> existing("saved-1"), store, Runnable::run).join();

        assertNull(store.read(keyOf("gone-1")));
        assertNotNull(store.read(keyOf("saved-1")));
    }

    @Test
    void anUnreadableSessionListDeletesNothing() {
        FakeSecretStore store = new FakeSecretStore();
        saveKey(store, "gone-1");

        SessionSecrets.cleanupOrphansAsync(() -> null, store, Runnable::run).join();

        assertNotNull(store.read(keyOf("gone-1")));
    }

    @Test
    void aSessionWhoseFolderCannotBeCheckedIsKept() {
        FakeSecretStore store = new FakeSecretStore();
        saveKey(store, "unknown-1");
        SessionSecrets.SessionExistence unreadable = sessionId -> {
            throw new IOException("folder unreadable");
        };

        SessionSecrets.cleanupOrphansAsync(() -> unreadable, store, Runnable::run).join();

        assertNotNull(store.read(keyOf("unknown-1")));
    }

    @Test
    void theRetiredDefaultKeyIsCleanedUp() {
        FakeSecretStore store = new FakeSecretStore();
        saveKey(store, "default");

        SessionSecrets.cleanupOrphansAsync(() -> existing(), store, Runnable::run).join();

        assertNull(store.read(keyOf("default")));
    }
}

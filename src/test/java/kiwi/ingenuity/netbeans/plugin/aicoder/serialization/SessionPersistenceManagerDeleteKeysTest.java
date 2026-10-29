package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SecretStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SessionSecrets;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class SessionPersistenceManagerDeleteKeysTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateSecrets() {
        SessionSecrets.useForTest(Preferences.userRoot().node("/aicoder-delete-keys-" + System.nanoTime()));
    }

    @AfterEach
    void resetSecretDeleter() {
        SessionPersistenceManager.setSessionSecretsDeleterForTest(null);
        SessionSecrets.useForTest(null);
    }

    @Test
    @Timeout(15)
    void deletingAnOpenAiCompatibleSessionDeletesItsKeyAndSecretHeader() throws Exception {
        FakeSecretStore store = new FakeSecretStore();
        String sessionId = "openai-delete";
        SessionSecrets secrets = new SessionSecrets(sessionId, store, Runnable::run);
        secrets.saveKeyAsync("sk-session".toCharArray()).join();
        secrets.saveHeaderAsync("x-api-key", "header-secret".toCharArray()).join();

        SessionPersistenceManager.setSessionSecretsDeleterForTest(
                id -> SessionSecrets.deleteAllAsync(id, store, Runnable::run));
        SessionPersistenceManager manager = new SessionPersistenceManager(tempDir);
        manager.save(new AiSession(sessionId, "OpenAI", null, AiTypeEnum.OPENAI_COMPATIBLE, null,
                                   new OpenAiCompatibleSessionSettings(), Instant.now(), Instant.now()));

        manager.delete(sessionId);

        assertNull(store.read("aicoder.openai_compatible." + sessionId));
        assertNull(store.read("aicoder.openai_compatible." + sessionId + ".header.x-api-key"));
    }

    private static final class FakeSecretStore implements SecretStore {

        private final Map<String, char[]> values = new HashMap<>();

        @Override
        public char[] read(String key) {
            char[] value = values.get(key);
            return value == null ? null : value.clone();
        }

        @Override
        public void save(String key, char[] value, String description) {
            values.put(key, value.clone());
            Arrays.fill(value, '\0');
        }

        @Override
        public void delete(String key) {
            char[] value = values.remove(key);
            if (value != null) {
                Arrays.fill(value, '\0');
            }
        }
    }
}

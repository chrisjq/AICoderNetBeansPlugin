package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys;

import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class SavedServerKeysTest {

    @Test
    void savesAndReadsAKeyWithoutLeakingCallerArray() {
        FakeSecretStore store = new FakeSecretStore();
        SavedServerKeys keys = new SavedServerKeys(store, Runnable::run);
        char[] input = "sk-test".toCharArray();

        assertTrue(keys.saveAsync("server-a", input).join());
        assertArrayEquals("sk-test".toCharArray(), input);
        Optional<char[]> read = keys.readAsync("server-a").join();
        assertTrue(read.isPresent());
        assertArrayEquals("sk-test".toCharArray(), read.orElseThrow());
    }

    @Test
    void reportsFalseWhenTheStoreDoesNotPreserveTheValue() {
        FakeSecretStore store = new FakeSecretStore();
        store.setLossy(true);
        SavedServerKeys keys = new SavedServerKeys(store, Runnable::run);

        assertFalse(keys.saveAsync("server-b", "sk-test".toCharArray()).join());
    }
}

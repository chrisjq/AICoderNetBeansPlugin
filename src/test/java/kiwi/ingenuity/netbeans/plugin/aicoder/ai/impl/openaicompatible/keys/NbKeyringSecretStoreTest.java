package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The NetBeans-backed store against the real Keyring API (NetBeans falls back to its in-memory provider in unit tests).
 */
class NbKeyringSecretStoreTest {

    @Test
    @Timeout(10)
    void saveLeavesTheCallersArrayIntactAndTheValueReadsBack() {
        NbKeyringSecretStore store = new NbKeyringSecretStore();
        String key = "aicoder.test.nb-store-" + UUID.randomUUID();
        char[] value = "sk-test".toCharArray();
        try {
            store.save(key, value, "test");

            assertArrayEquals("sk-test".toCharArray(), value, "Keyring.save must not zero the caller's array");
            assertArrayEquals("sk-test".toCharArray(), store.read(key));
        }
        finally {
            store.delete(key);
        }
        assertNull(store.read(key), "delete removes the entry");
    }
}

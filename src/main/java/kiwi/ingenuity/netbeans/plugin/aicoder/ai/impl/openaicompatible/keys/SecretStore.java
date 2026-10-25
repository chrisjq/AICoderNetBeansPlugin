package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys;

/**
 * Keyring access behind an interface so tests never touch the real keyring.
 */
public interface SecretStore {

    char[] read(String key);

    void save(String key, char[] value, String description);

    void delete(String key);
}

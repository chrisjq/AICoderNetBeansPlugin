package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys;

/**
 * Keyring access behind an interface so tests never touch the real keyring.
 */
public interface SecretStore {

    char[] read(String key);

    /**
     * Stores {@code value} under {@code key}. Implementations must not modify the caller's array; the NetBeans-backed
     * store saves a copy because {@code Keyring.save} zeroes the array it is given.
     */
    void save(String key, char[] value, String description);

    void delete(String key);
}

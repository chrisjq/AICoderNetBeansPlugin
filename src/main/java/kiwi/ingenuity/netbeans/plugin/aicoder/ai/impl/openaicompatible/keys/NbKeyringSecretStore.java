package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys;

import org.netbeans.api.keyring.Keyring;

/**
 * SecretStore implementation backed by the NetBeans keyring.
 */
public final class NbKeyringSecretStore implements SecretStore {

    @Override
    public char[] read(String key) {
        return Keyring.read(key);
    }

    @Override
    public void save(String key, char[] value, String description) {
        Keyring.save(key, value, description);
    }

    @Override
    public void delete(String key) {
        Keyring.delete(key);
    }
}

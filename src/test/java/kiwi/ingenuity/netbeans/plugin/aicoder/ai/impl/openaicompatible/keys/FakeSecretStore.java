package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

final class FakeSecretStore implements SecretStore {

    private final Map<String, char[]> values = new ConcurrentHashMap<>();
    private volatile boolean locked;
    private volatile boolean lossy;
    private volatile CountDownLatch release = new CountDownLatch(0);

    void setLocked(boolean locked) {
        this.locked = locked;
        if (locked) {
            release = new CountDownLatch(1);
        }
        else {
            release.countDown();
        }
    }

    void release() {
        release.countDown();
    }

    void setLossy(boolean lossy) {
        this.lossy = lossy;
    }

    void put(String key, String value) {
        values.put(key, value.toCharArray());
    }

    boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public char[] read(String key) {
        awaitIfLocked();
        char[] value = values.get(key);
        return value == null ? null : value.clone();
    }

    @Override
    public void save(String key, char[] value, String description) {
        awaitIfLocked();
        if (!lossy) {
            values.put(key, value.clone());
        }
        // Like Keyring.save, the fake zeroes the array it is given, so callers that reuse it after a save are caught.
        Arrays.fill(value, (char) 0);
    }

    @Override
    public void delete(String key) {
        awaitIfLocked();
        values.remove(key);
    }

    private void awaitIfLocked() {
        if (locked) {
            try {
                release.await();
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

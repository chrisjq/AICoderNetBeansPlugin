package kiwi.ingenuity.netbeans.plugin.aicoder.testutil;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Test-only in-memory preferences implementation; it never touches disk.
 */
public final class MemoryPreferences extends AbstractPreferences {

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, MemoryPreferences> children = new HashMap<>();

    private MemoryPreferences(AbstractPreferences parent, String name) {
        super(parent, name);
    }

    public static Preferences create() {
        MemoryPreferences root = new MemoryPreferences(null, "");
        return root.childSpi("test");
    }

    @Override
    protected synchronized void putSpi(String key, String value) {
        values.put(key, value);
    }

    @Override
    protected synchronized String getSpi(String key) {
        return values.get(key);
    }

    @Override
    protected synchronized void removeSpi(String key) {
        values.remove(key);
    }

    @Override
    protected synchronized void removeNodeSpi() {
        values.clear();
        children.clear();
    }

    @Override
    protected synchronized String[] keysSpi() {
        return values.keySet().toArray(String[]::new);
    }

    @Override
    protected synchronized String[] childrenNamesSpi() {
        return children.keySet().toArray(String[]::new);
    }

    @Override
    protected synchronized AbstractPreferences childSpi(String name) {
        return children.computeIfAbsent(name, key -> new MemoryPreferences(this, key));
    }

    @Override
    protected void syncSpi() throws BackingStoreException {
    }

    @Override
    protected void flushSpi() throws BackingStoreException {
    }
}

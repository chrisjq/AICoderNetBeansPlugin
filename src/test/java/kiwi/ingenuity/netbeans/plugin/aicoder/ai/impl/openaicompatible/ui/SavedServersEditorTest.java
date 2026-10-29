package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SavedServerKeys;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SecretStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.SavedServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class SavedServersEditorTest {

    @AfterEach
    void cleanup() {
        OpenAiCompatiblePluginSettings.useForTest(null);
    }

    @Test
    void addEditRemoveAndDefaultAreStagedUntilSave() {
        useTestPreferences();
        FakeSecretStore store = new FakeSecretStore();
        SavedServersEditor editor = new SavedServersEditor(new SavedServerKeys(store, Runnable::run));
        editor.load();
        SavedServer a = editor.addForTest("A", "http://a:1", ServerTypeEnum.AUTO, "sk-a".toCharArray());
        SavedServer b = editor.addForTest("B", "http://b:1", ServerTypeEnum.LM_STUDIO, null);
        editor.setDefaultForTest(b.id());
        assertEquals(List.of(), OpenAiCompatiblePluginSettings.getSavedServers(), "nothing written before save");

        assertTrue(editor.save().join());
        assertEquals(List.of(a, b), OpenAiCompatiblePluginSettings.getSavedServers());
        assertEquals(b.id(), OpenAiCompatiblePluginSettings.getDefaultServerId());
        assertArrayEquals("sk-a".toCharArray(), store.read(SavedServerKeys.keyName(a.id())));

        editor.removeForTest(b.id());
        assertTrue(editor.save().join());
        assertEquals(a.id(), OpenAiCompatiblePluginSettings.getDefaultServerId(), "first remaining becomes default");
    }

    @Test
    void anUntouchedEditorDoesNotOverwriteTheSavedList() {
        useTestPreferences();
        FakeSecretStore store = new FakeSecretStore();
        SavedServerKeys serverKeys = new SavedServerKeys(store, Runnable::run);
        SavedServersEditor editorB = new SavedServersEditor(serverKeys);
        editorB.load();
        SavedServersEditor editorA = new SavedServersEditor(serverKeys);
        editorA.load();

        SavedServer x = editorA.addForTest("X", "http://x:1", ServerTypeEnum.AUTO, null);
        assertTrue(editorA.save().join());
        assertTrue(editorB.save().join());

        assertEquals(List.of(x), OpenAiCompatiblePluginSettings.getSavedServers());
    }

    @Test
    void anEditedEditorStillSavesAsBefore() throws Exception {
        useTestPreferences();
        FakeSecretStore store = new FakeSecretStore();
        SavedServersEditor editor = new SavedServersEditor(new SavedServerKeys(store, Runnable::run));
        editor.load();

        SavedServer x = editor.addForTest("X", "http://x:1", ServerTypeEnum.AUTO, null);
        assertTrue(editor.isModified());
        assertTrue(editor.save().join());
        flushEdt();
        assertEquals(List.of(x), OpenAiCompatiblePluginSettings.getSavedServers());
        assertTrue(!editor.isModified());
    }

    @Test
    void aFailedKeySaveLeavesTheSavedListUnchanged() {
        useTestPreferences();
        SavedServer a = SavedServer.create("A", "http://a:1", ServerTypeEnum.AUTO);
        OpenAiCompatiblePluginSettings.setSavedServers(List.of(a));
        OpenAiCompatiblePluginSettings.setDefaultServerId(a.id());
        SecretStore failing = new SecretStore() {
            @Override
            public char[] read(String name) {
                return null;
            }

            @Override
            public void save(String name, char[] value, String description) {
                throw new IllegalStateException("keyring unavailable");
            }

            @Override
            public void delete(String name) {
            }
        };
        SavedServersEditor editor = new SavedServersEditor(new SavedServerKeys(failing, Runnable::run));
        editor.load();
        editor.addForTest("B", "http://b:1", ServerTypeEnum.AUTO, "sk-b".toCharArray());

        assertEquals(Boolean.FALSE, editor.save().join());
        assertEquals(List.of(a), OpenAiCompatiblePluginSettings.getSavedServers());
        assertEquals(a.id(), OpenAiCompatiblePluginSettings.getDefaultServerId());
    }

    @Test
    void removingAnEntryDeletesItsKey() {
        useTestPreferences();
        FakeSecretStore store = new FakeSecretStore();
        SavedServersEditor editor = new SavedServersEditor(new SavedServerKeys(store, Runnable::run));
        editor.load();
        SavedServer a = editor.addForTest("A", "http://a:1", ServerTypeEnum.AUTO, "sk-a".toCharArray());
        editor.save().join();
        editor.removeForTest(a.id());
        editor.save().join();

        assertNull(store.read(SavedServerKeys.keyName(a.id())));
    }

    @Test
    void aFailedKeyDeleteIsRetriedByTheNextSave() {
        useTestPreferences();
        FakeSecretStore store = new FakeSecretStore();
        SavedServersEditor editor = new SavedServersEditor(new SavedServerKeys(store, Runnable::run));
        editor.load();
        SavedServer a = editor.addForTest("A", "http://a:1", ServerTypeEnum.AUTO, "sk-a".toCharArray());
        assertTrue(editor.save().join());
        editor.removeForTest(a.id());
        store.failNextDeletes = 1;

        assertEquals(Boolean.FALSE, editor.save().join(), "the key of the removed server could not be deleted");
        assertArrayEquals("sk-a".toCharArray(), store.read(SavedServerKeys.keyName(a.id())));

        assertTrue(editor.save().join(), "the next save retries the delete and it succeeds");
        assertNull(store.read(SavedServerKeys.keyName(a.id())));
    }

    @Test
    void optionsSaveSurvivesReloadBeforeKeyringCompletes() throws Exception {
        useTestPreferences();
        FakeSecretStore store = new FakeSecretStore();
        QueueExecutor executor = new QueueExecutor();
        SavedServersEditor editor = new SavedServersEditor(new SavedServerKeys(store, executor));
        SwingUtilities.invokeAndWait(editor::load);
        SavedServer added = editor.addForTest("A", "http://a:1", ServerTypeEnum.AUTO, "sk-a".toCharArray());

        CompletableFuture<Boolean> saving = editor.save();
        SwingUtilities.invokeAndWait(editor::load);
        executor.drain();
        SwingUtilities.invokeAndWait(() -> {
        });

        assertTrue(saving.join());
        assertEquals(List.of(added), OpenAiCompatiblePluginSettings.getSavedServers());
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    private static void useTestPreferences() {
        OpenAiCompatiblePluginSettings.useForTest(
                MemoryPreferences.create());
    }

    private static final class QueueExecutor implements Executor {

        private final Queue<Runnable> queue = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable command) {
            queue.add(command);
        }

        void drain() {
            for (;;) {
                Runnable next;
                synchronized (this) {
                    next = queue.poll();
                }
                if (next == null) {
                    return;
                }
                next.run();
            }
        }
    }

    private static final class FakeSecretStore implements SecretStore {

        private final Map<String, char[]> values = new HashMap<>();
        private int failNextDeletes;

        @Override
        public char[] read(String name) {
            char[] value = values.get(name);
            return value == null ? null : value.clone();
        }

        @Override
        public void save(String name, char[] value, String description) {
            values.put(name, value.clone());
            Arrays.fill(value, '\0');
        }

        @Override
        public void delete(String name) {
            if (failNextDeletes > 0) {
                failNextDeletes--;
                throw new IllegalStateException("keyring busy");
            }
            char[] value = values.remove(name);
            if (value != null) {
                Arrays.fill(value, '\0');
            }
        }
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettingsKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.DefaultKeyRules;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleSettingsTabFormTest {

    private Preferences prefs;

    private static final class FakeKeyStore implements OpenAiCompatibleSettingsTab.DefaultKeyStore {

        final List<String> saved = new CopyOnWriteArrayList<>();
        final AtomicInteger cleared = new AtomicInteger();
        volatile boolean saveSucceeds = true;

        @Override
        public CompletableFuture<Boolean> save(char[] key) {
            saved.add(new String(key));
            return CompletableFuture.completedFuture(saveSucceeds);
        }

        @Override
        public CompletableFuture<Void> clear() {
            cleared.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    @BeforeEach
    void isolatePreferences() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        prefs = Preferences.userRoot().node("openai-compatible-tab-" + UUID.randomUUID());
        OpenAiCompatiblePluginSettings.useForTest(prefs);
    }

    @AfterEach
    void restorePreferences() throws Exception {
        OpenAiCompatiblePluginSettings.useForTest(null);
        if (prefs != null) {
            prefs.removeNode();
        }
    }

    private static <T extends Component> List<T> find(Component root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T extends Component> void collect(Component component, Class<T> type, List<T> found) {
        if (type.isInstance(component)) {
            found.add(type.cast(component));
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, type, found);
            }
        }
    }

    private static JTextField textField(OpenAiCompatibleSettingsTab tab, int index) {
        List<JTextField> fields = new ArrayList<>();
        for (JTextField field : find(tab.getComponent(), JTextField.class)) {
            if (!(field instanceof JPasswordField)) {
                fields.add(field);
            }
        }
        return fields.get(index);
    }

    private static JTextField urlField(OpenAiCompatibleSettingsTab tab) {
        return textField(tab, 0);
    }

    private static JTextField modelField(OpenAiCompatibleSettingsTab tab) {
        return textField(tab, 1);
    }

    private static JPasswordField keyField(OpenAiCompatibleSettingsTab tab) {
        return find(tab.getComponent(), JPasswordField.class).get(0);
    }

    private static boolean hasLabel(OpenAiCompatibleSettingsTab tab, String text) {
        return find(tab.getComponent(), JLabel.class).stream().anyMatch(label -> text.equals(label.getText()));
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    @Test
    @Timeout(10)
    void loadFillsTheFormFromTheStoredDefaults() throws Exception {
        OpenAiCompatiblePluginSettings.setServerType(ServerTypeEnum.VLLM);
        OpenAiCompatiblePluginSettings.setBaseUrl("http://vllm:8000");
        OpenAiCompatiblePluginSettings.setModel("llama");
        OpenAiCompatiblePluginSettings.setDefaultKeySet(true);
        OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(new FakeKeyStore());

        tab.load();
        tab.importFinished().get(5, TimeUnit.SECONDS);
        flushEdt();

        assertEquals("http://vllm:8000", urlField(tab).getText());
        assertEquals("llama", modelField(tab).getText());
        assertEquals(ServerTypeEnum.VLLM, find(tab.getComponent(), JComboBox.class).get(0).getSelectedItem());
        assertTrue(hasLabel(tab, "Key: set"));
    }

    @Test
    @Timeout(10)
    void storeWritesTypeNormalisedUrlAndModel() throws Exception {
        OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(new FakeKeyStore());
        tab.load();
        tab.importFinished().get(5, TimeUnit.SECONDS);
        flushEdt();

        urlField(tab).setText("HTTPS://Host.example/v1/");
        modelField(tab).setText("  gpt-x  ");
        find(tab.getComponent(), JComboBox.class).get(0).setSelectedItem(ServerTypeEnum.OTHER);
        tab.store();

        assertEquals("HTTPS://Host.example", OpenAiCompatiblePluginSettings.getBaseUrl());
        assertEquals("gpt-x", OpenAiCompatiblePluginSettings.getModel());
        assertEquals(ServerTypeEnum.OTHER, OpenAiCompatiblePluginSettings.getServerType());
    }

    @Test
    @Timeout(10)
    void aTypedKeyIsSavedAndTheFlagTurnsOnOnlyAfterTheReadBack() throws Exception {
        FakeKeyStore keys = new FakeKeyStore();
        OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(keys);
        tab.load();
        tab.importFinished().get(5, TimeUnit.SECONDS);
        flushEdt();

        keyField(tab).setText("sk-1");
        tab.store();
        tab.storeFinished().get(5, TimeUnit.SECONDS);

        assertEquals(List.of("sk-1"), keys.saved);
        assertTrue(OpenAiCompatiblePluginSettings.isDefaultKeySet());
        assertEquals(0, keyField(tab).getPassword().length);
    }

    @Test
    @Timeout(10)
    void aKeyThatCouldNotBeStoredLeavesTheFlagOffAndShowsANotice() throws Exception {
        FakeKeyStore keys = new FakeKeyStore();
        keys.saveSucceeds = false;
        List<String> notices = new CopyOnWriteArrayList<>();
        OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(keys);
        tab.setNoticeSink(notices::add);
        tab.load();
        tab.importFinished().get(5, TimeUnit.SECONDS);
        flushEdt();

        keyField(tab).setText("sk-1");
        tab.store();
        tab.storeFinished().get(5, TimeUnit.SECONDS);

        assertFalse(OpenAiCompatiblePluginSettings.isDefaultKeySet());
        assertEquals(1, notices.size());
    }

    @Test
    @Timeout(10)
    void savingAUrlOnAnotherRootClearsTheDefaultKeyWithTheNotice() throws Exception {
        OpenAiCompatiblePluginSettings.setBaseUrl("http://a:1");
        OpenAiCompatiblePluginSettings.setDefaultKeySet(true);
        FakeKeyStore keys = new FakeKeyStore();
        List<String> notices = new CopyOnWriteArrayList<>();
        OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(keys);
        tab.setNoticeSink(notices::add);
        tab.load();
        tab.importFinished().get(5, TimeUnit.SECONDS);
        flushEdt();

        urlField(tab).setText("http://b:2");
        tab.store();
        tab.storeFinished().get(5, TimeUnit.SECONDS);

        assertFalse(OpenAiCompatiblePluginSettings.isDefaultKeySet());
        assertEquals(1, keys.cleared.get());
        assertEquals(List.of(DefaultKeyRules.CLEARED_NOTICE), notices);
    }

    @Test
    @Timeout(10)
    void savingTheSameRootKeepsTheDefaultKey() throws Exception {
        OpenAiCompatiblePluginSettings.setBaseUrl("http://a:1");
        OpenAiCompatiblePluginSettings.setDefaultKeySet(true);
        FakeKeyStore keys = new FakeKeyStore();
        List<String> notices = new CopyOnWriteArrayList<>();
        OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(keys);
        tab.setNoticeSink(notices::add);
        tab.load();
        tab.importFinished().get(5, TimeUnit.SECONDS);
        flushEdt();

        urlField(tab).setText("http://a:1/v1");
        tab.store();
        tab.storeFinished().get(5, TimeUnit.SECONDS);

        assertTrue(OpenAiCompatiblePluginSettings.isDefaultKeySet());
        assertEquals(0, keys.cleared.get());
        assertTrue(notices.isEmpty());
    }

    @Test
    @Timeout(10)
    void aTypedKeyWinsWhenTheUrlRootChangesInTheSameSave() throws Exception {
        OpenAiCompatiblePluginSettings.setBaseUrl("http://a:1");
        OpenAiCompatiblePluginSettings.setDefaultKeySet(true);
        FakeKeyStore keys = new FakeKeyStore();
        OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(keys);
        tab.load();
        tab.importFinished().get(5, TimeUnit.SECONDS);
        flushEdt();

        urlField(tab).setText("http://b:2");
        keyField(tab).setText("sk-new");
        tab.store();
        tab.storeFinished().get(5, TimeUnit.SECONDS);

        assertEquals(List.of("sk-new"), keys.saved);
        assertEquals(0, keys.cleared.get());
        assertTrue(OpenAiCompatiblePluginSettings.isDefaultKeySet());
    }

    @Test
    @Timeout(10)
    void theClearButtonRemovesTheKeyOnSaveWithoutANotice() throws Exception {
        OpenAiCompatiblePluginSettings.setBaseUrl("http://a:1");
        OpenAiCompatiblePluginSettings.setDefaultKeySet(true);
        FakeKeyStore keys = new FakeKeyStore();
        List<String> notices = new CopyOnWriteArrayList<>();
        OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(keys);
        tab.setNoticeSink(notices::add);
        tab.load();
        tab.importFinished().get(5, TimeUnit.SECONDS);
        flushEdt();

        JButton clear = find(tab.getComponent(), JButton.class).stream()
                .filter(button -> "Clear".equals(button.getText())).findFirst().orElseThrow();
        clear.doClick();
        assertTrue(hasLabel(tab, "Key: not set"));
        tab.store();
        tab.storeFinished().get(5, TimeUnit.SECONDS);

        assertFalse(OpenAiCompatiblePluginSettings.isDefaultKeySet());
        assertEquals(1, keys.cleared.get());
        assertTrue(notices.isEmpty());
    }

    @Test
    @Timeout(10)
    void theOllamaImportRunsOnFirstOpenAndShowsTheNote() throws Exception {
        prefs.put(OllamaPluginSettingsKeyEnum.BASE_URL.key(), "http://localhost:11434/v1");
        prefs.put(OllamaPluginSettingsKeyEnum.MODEL.key(), "qwen");
        OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(new FakeKeyStore());

        tab.load();
        assertTrue(tab.importFinished().get(5, TimeUnit.SECONDS));
        flushEdt();

        assertEquals("http://localhost:11434", urlField(tab).getText());
        assertEquals("qwen", modelField(tab).getText());
        assertEquals(ServerTypeEnum.AUTO, find(tab.getComponent(), JComboBox.class).get(0).getSelectedItem());
        assertTrue(hasLabel(tab, "Imported from Ollama (local)"));

        OpenAiCompatibleSettingsTab again = new OpenAiCompatibleSettingsTab(new FakeKeyStore());
        again.load();
        assertFalse(again.importFinished().get(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(10)
    void pickingAPresetFillsAnEmptyUrl() throws Exception {
        OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(new FakeKeyStore());
        tab.load();
        tab.importFinished().get(5, TimeUnit.SECONDS);
        flushEdt();

        find(tab.getComponent(), JComboBox.class).get(0).setSelectedItem(ServerTypeEnum.LM_STUDIO);

        assertEquals("http://localhost:1234", urlField(tab).getText());
        assertTrue(hasLabel(tab, "Base URL changed to http://localhost:1234"));
    }

    @Test
    @Timeout(10)
    void onlyHttpUrlsOrABlankOneAreValid() throws Exception {
        OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(new FakeKeyStore());
        urlField(tab).setText("");
        assertTrue(tab.isValid());
        urlField(tab).setText("HTTP://x");
        assertTrue(tab.isValid());
        urlField(tab).setText("https://x/v1");
        assertTrue(tab.isValid());
        urlField(tab).setText("ftp://x");
        assertFalse(tab.isValid());
        urlField(tab).setText("localhost:1234");
        assertFalse(tab.isValid());
    }

    @Test
    @Timeout(10)
    void advancedValuesRoundTripThroughLoadAndStore() throws Exception {
        OpenAiCompatiblePluginSettings.setAssumedWindow(12000);
        OpenAiCompatiblePluginSettings.setConnectTimeoutSeconds(7);
        OpenAiCompatiblePluginSettings.setKeepAlive("10m");
        OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(new FakeKeyStore());
        tab.load();
        tab.importFinished().get(5, TimeUnit.SECONDS);
        flushEdt();

        assertFalse(tab.pluginAdvanced().component().isVisible());
        assertEquals("Advanced \u25b8", tab.advancedToggle().getText());
        assertEquals(12000, tab.pluginAdvanced().assumedWindow());
        assertEquals(7, tab.pluginAdvanced().connectTimeoutSeconds());
        assertEquals("10m", tab.pluginAdvanced().keepAlive());
        assertTrue(find(tab.getComponent(), JButton.class).stream()
                .anyMatch(button -> "Test connection".equals(button.getText())));

        tab.pluginAdvanced().assumedWindow(15000);
        tab.pluginAdvanced().connectTimeoutSeconds(4);
        tab.pluginAdvanced().keepAlive("2m");
        tab.store();

        assertEquals(15000, OpenAiCompatiblePluginSettings.getAssumedWindow());
        assertEquals(4, OpenAiCompatiblePluginSettings.getConnectTimeoutSeconds());
        assertEquals("2m", OpenAiCompatiblePluginSettings.getKeepAlive());
    }
}

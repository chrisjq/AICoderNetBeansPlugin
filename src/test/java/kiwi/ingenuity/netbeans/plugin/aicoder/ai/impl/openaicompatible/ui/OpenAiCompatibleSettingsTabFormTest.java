package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SavedServerKeys;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SecretStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class OpenAiCompatibleSettingsTabFormTest {

    private static final class FakeStore implements SecretStore {

        private final Map<String, char[]> values = new ConcurrentHashMap<>();

        boolean contains(String key) {
            return values.containsKey(key);
        }

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
            values.remove(key);
        }
    }

    private Preferences prefs;

    @BeforeEach
    void isolatePreferences() {
        Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        prefs = MemoryPreferences.create();
        OpenAiCompatiblePluginSettings.useForTest(prefs);
    }

    @AfterEach
    void restorePreferences() throws Exception {
        OpenAiCompatiblePluginSettings.useForTest(null);
        if (prefs != null) {
            prefs.removeNode();
        }
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    @Test
    void storeWritesTheSavedServerList() throws Exception {
        FakeStore store = new FakeStore();
        OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(new SavedServerKeys(store, Runnable::run));
        tab.load();
        tab.importFinished().get(5, TimeUnit.SECONDS);
        flushEdt();

        var server = tab.serversEditor().addForTest("Local", "http://localhost:1234", ServerTypeEnum.LM_STUDIO,
                                                "sk-local".toCharArray());
        tab.store();
        tab.storeFinished().get(5, TimeUnit.SECONDS);
        assertEquals(1, OpenAiCompatiblePluginSettings.getSavedServers().size());
        assertTrue(store.contains(SavedServerKeys.keyName(server.id())));
    }

    @Test
    void testConnectionUsesTheSelectedEntry() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/models", exchange -> {
                         byte[] body = "{\"data\":[{\"id\":\"model-a\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                         exchange.sendResponseHeaders(200, body.length);
                         try (var output = exchange.getResponseBody()) {
                             output.write(body);
                         }
                     });
        server.start();
        try {
            FakeStore store = new FakeStore();
            OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(new SavedServerKeys(store, Runnable::run));
            tab.load();
            tab.importFinished().get(5, TimeUnit.SECONDS);
            flushEdt();
            tab.serversEditor().addForTest("Test", "http://localhost:" + server.getAddress().getPort(),
                                           ServerTypeEnum.AUTO, new char[0]);
            assertTrue(tab.testConnectionButton().isEnabled());
            tab.testConnectionButton().doClick();
            for (int i = 0; i < 50 && !"Connected · 1 models".equals(tab.testConnectionStatus()); i++) {
                Thread.sleep(20);
                flushEdt();
            }
            assertEquals("Connected · 1 models", tab.testConnectionStatus());
        }
        finally {
            server.stop(0);
        }
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.ui;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.OllamaModelDiscovery;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The reasoning-effort combo must show what will actually be used, like Grok's and Copilot's info bars: the session's
 * own value when pinned, otherwise the global default — without ever writing that fallback back into the session's
 * settings, or "inherits the global" and "pinned to this session" become indistinguishable.
 */
class OllamaAiInfoBarExtensionTest {

    @Test
    void sessionSettingsChangedShowsTheSessionValueWhenPinned() throws Exception {
        OllamaAiInfoBarExtension ext = new OllamaAiInfoBarExtension();
        OllamaSessionSettings settings = new OllamaSessionSettings();
        settings.setReasoningEffort("high");

        // onSessionSettingsChanged defers to SwingUtilities.invokeLater when called off the EDT (the caller here
        // isn't it), so the update must be run ON the EDT and awaited — otherwise the assertion below races the
        // still-queued task and can observe the combo's pre-update state instead of proving anything.
        SwingUtilities.invokeAndWait(() -> ext.onSessionSettingsChanged(settings));

        assertEquals("high", ext.getSelectedReasoningEffort());
    }

    @Test
    void sessionSettingsChangedFallsBackToTheGlobalDefaultWhenSessionIsUnset() throws Exception {
        String before = OllamaPluginSettings.getReasoningEffort();
        try {
            OllamaPluginSettings.setReasoningEffort("medium");
            OllamaAiInfoBarExtension ext = new OllamaAiInfoBarExtension();
            OllamaSessionSettings settings = new OllamaSessionSettings();

            SwingUtilities.invokeAndWait(() -> ext.onSessionSettingsChanged(settings));

            assertEquals("medium", ext.getSelectedReasoningEffort(),
                         "must display the global default, not \"(model default)\", when one is set");
            assertNull(settings.reasoningEffort(),
                       "the fallback must be display-only and never written back into the session's settings");
        }
        finally {
            OllamaPluginSettings.setReasoningEffort(before);
        }
    }

    /**
     * Serves a fixed {@code /api/tags} body on an ephemeral loopback port — never the real Ollama, per the standing "do
     * not hammer the box" instruction. A fresh ephemeral port per call keeps the discovery cache entry isolated from
     * every other test.
     */
    private static HttpServer startFakeOllamaServer(String tagsResponseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/tags", exchange -> {
                         byte[] bytes = tagsResponseBody.getBytes(StandardCharsets.UTF_8);
                         exchange.getResponseHeaders().add("Content-Type", "application/json");
                         exchange.sendResponseHeaders(200, bytes.length);
                         try (OutputStream os = exchange.getResponseBody()) {
                             os.write(bytes);
                         }
                     });
        server.createContext("/v1/models", exchange -> {
                         byte[] bytes = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
                         exchange.sendResponseHeaders(200, bytes.length);
                         try (OutputStream os = exchange.getResponseBody()) {
                             os.write(bytes);
                         }
                     });
        server.start();
        return server;
    }

    private static String baseUrlOf(HttpServer server) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    /**
     * Spec §1 rule 3/§7: once live discovery has POSITIVELY confirmed a model cannot think, the combo must collapse to
     * only the "not set" entry — even if a level was previously requested/pinned for it.
     */
    @Test
    void comboCollapsesToNotSetForAModelDiscoveryConfirmsCannotThink() throws Exception {
        String body = "{\"models\":[{\"name\":\"qwen2.5-coder:14b\",\"capabilities\":[\"completion\",\"tools\",\"insert\"]}]}";
        HttpServer server = startFakeOllamaServer(body);
        try {
            String baseUrl = baseUrlOf(server);
            CountDownLatch done = new CountDownLatch(1);
            OllamaModelDiscovery.discoverAsync(baseUrl, models -> done.countDown(), hint -> {
                                       });
            assertTrue(done.await(5, TimeUnit.SECONDS), "discovery did not complete");

            OllamaAiInfoBarExtension ext = new OllamaAiInfoBarExtension();
            ext.setBaseUrl(baseUrl);
            SwingUtilities.invokeAndWait(() -> {
                ext.setSelectedModel("qwen2.5-coder:14b");
                ext.setSelectedReasoningEffort("high");
            });

            assertNull(ext.getSelectedReasoningEffort(),
                       "a model discovery confirms cannot think must collapse the combo to \"not set\", even though "
                       + "\"high\" was requested");
        }
        finally {
            server.stop(0);
        }
    }

    /**
     * The mirror case: a model discovery positively confirms CAN think must offer the static level list and let the
     * requested level actually be selected.
     */
    @Test
    void comboOffersLevelsForAModelDiscoveryConfirmsCanThink() throws Exception {
        String body = "{\"models\":[{\"name\":\"deepseek-v3.1:671b\",\"capabilities\":[\"completion\",\"tools\",\"thinking\"]}]}";
        HttpServer server = startFakeOllamaServer(body);
        try {
            String baseUrl = baseUrlOf(server);
            CountDownLatch done = new CountDownLatch(1);
            OllamaModelDiscovery.discoverAsync(baseUrl, models -> done.countDown(), hint -> {
                                       });
            assertTrue(done.await(5, TimeUnit.SECONDS), "discovery did not complete");

            OllamaAiInfoBarExtension ext = new OllamaAiInfoBarExtension();
            ext.setBaseUrl(baseUrl);
            SwingUtilities.invokeAndWait(() -> {
                ext.setSelectedModel("deepseek-v3.1:671b");
                ext.setSelectedReasoningEffort("high");
            });

            assertEquals("high", ext.getSelectedReasoningEffort());
        }
        finally {
            server.stop(0);
        }
    }
}

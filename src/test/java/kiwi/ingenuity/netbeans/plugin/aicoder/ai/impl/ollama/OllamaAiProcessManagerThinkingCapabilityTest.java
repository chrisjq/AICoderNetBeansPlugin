package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link OllamaAiProcessManager#applyThinkingCapabilityValidation} and
 * {@link OllamaAiProcessManager#resolveEffectiveReasoningEffort}, now that Ollama has live capability discovery
 * ({@code GET /api/tags}): only a SESSION-sourced value may ever be cleared/reported; a GLOBAL-sourced one is only ever
 * silently omitted. Mirrors {@code GrokAiProcessManagerReasoningEffortTest}'s directly-testable-method approach — no
 * process is spawned, no turn is run.
 */
class OllamaAiProcessManagerThinkingCapabilityTest {

    /**
     * Serves a fixed {@code /api/tags} body on an ephemeral loopback port — never the real Ollama, per the standing "do
     * not hammer the box" instruction.
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

    private static void awaitDiscovery(String baseUrl) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        OllamaModelDiscovery.discoverAsync(baseUrl, models -> done.countDown(), hint -> {
                                   });
        assertTrue(done.await(5, TimeUnit.SECONDS), "discovery did not complete");
    }

    private final List<AiProcessEvent> events = new ArrayList<>();
    private OllamaAiProcessManager manager;

    @BeforeEach
    void setup() {
        events.clear();
        manager = new OllamaAiProcessManager(events::add);
    }

    @Test
    void sessionSourcedValueIsClearedWithExactlyOneInfoWhenDiscoveryConfirmsModelCannotThink() throws Exception {
        HttpServer server = startFakeOllamaServer(
                "{\"models\":[{\"name\":\"qwen2.5-coder:14b\",\"capabilities\":[\"completion\",\"tools\",\"insert\"]}]}");
        try {
            String baseUrl = baseUrlOf(server);
            awaitDiscovery(baseUrl);
            AtomicBoolean cleared = new AtomicBoolean(false);
            manager.setOnReasoningEffortCleared(() -> cleared.set(true));

            String result = manager.applyThinkingCapabilityValidation(
                    new OllamaAiProcessManager.EffectiveReasoningEffort("high", true), baseUrl, "qwen2.5-coder:14b");

            assertNull(result, "an unsupported value must never be sent");
            assertTrue(cleared.get(), "a session-sourced value must trigger the persisted-clear callback");
            assertEquals(1, events.size(), "exactly one INFO event");
            assertTrue(events.get(0) instanceof StatusEvent se && se.type() == StatusEventTypeEnum.INFO);
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void globalSourcedValueIsSilentlyOmittedWithNoInfoWhenDiscoveryConfirmsModelCannotThink() throws Exception {
        HttpServer server = startFakeOllamaServer(
                "{\"models\":[{\"name\":\"qwen2.5-coder:14b\",\"capabilities\":[\"completion\",\"tools\",\"insert\"]}]}");
        try {
            String baseUrl = baseUrlOf(server);
            awaitDiscovery(baseUrl);
            AtomicBoolean cleared = new AtomicBoolean(false);
            manager.setOnReasoningEffortCleared(() -> cleared.set(true));

            String result = manager.applyThinkingCapabilityValidation(
                    new OllamaAiProcessManager.EffectiveReasoningEffort("high", false), baseUrl, "qwen2.5-coder:14b");

            assertNull(result, "an unsupported value must never be sent");
            assertFalse(cleared.get(), "spec rule 3a: a global-sourced value must never be cleared");
            assertTrue(events.isEmpty(), "spec rule 3a: a global-sourced value must never fire an INFO event");
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void supportedValueIsSentUnchangedRegardlessOfSource() throws Exception {
        HttpServer server = startFakeOllamaServer(
                "{\"models\":[{\"name\":\"deepseek-v3.1:671b\",\"capabilities\":[\"completion\",\"tools\",\"thinking\"]}]}");
        try {
            String baseUrl = baseUrlOf(server);
            awaitDiscovery(baseUrl);

            String result = manager.applyThinkingCapabilityValidation(
                    new OllamaAiProcessManager.EffectiveReasoningEffort("high", true), baseUrl, "deepseek-v3.1:671b");

            assertEquals("high", result);
            assertTrue(events.isEmpty());
        }
        finally {
            server.stop(0);
        }
    }

    /**
     * Discovery has not reported on this exact model at all — spec rule 3a's "confirmed unsupported" consequence must
     * not fire on incomplete information; the value is sent optimistically and the 4xx retry
     * ({@code OllamaAiProcessManager.chatWithReasoningEffortRetry}) remains the backstop if the model turns out unable
     * to think after all.
     */
    @Test
    void unknownModelSendsTheValueOptimisticallyAndNeverClears() {
        String result = manager.applyThinkingCapabilityValidation(
                new OllamaAiProcessManager.EffectiveReasoningEffort("high", true),
                "http://127.0.0.1:1", "a-model-discovery-never-reported-" + System.nanoTime());

        assertEquals("high", result);
        assertTrue(events.isEmpty());
    }

    @Test
    void unsetValuePassesThroughAsNullWithoutConsultingDiscovery() {
        String result = manager.applyThinkingCapabilityValidation(
                new OllamaAiProcessManager.EffectiveReasoningEffort(null, false), "http://127.0.0.1:1", "any-model");

        assertNull(result);
        assertTrue(events.isEmpty());
    }

    @Test
    void resolveEffectiveReasoningEffortSessionWinsOverGlobalWhenBothAreSet() {
        String before = OllamaPluginSettings.getReasoningEffort();
        try {
            OllamaPluginSettings.setReasoningEffort("medium");
            OllamaSessionSettings settings = new OllamaSessionSettings();
            settings.setReasoningEffort("high");

            OllamaAiProcessManager.EffectiveReasoningEffort effective = manager.resolveEffectiveReasoningEffort(settings);

            assertEquals("high", effective.value());
            assertTrue(effective.fromSession());
        }
        finally {
            OllamaPluginSettings.setReasoningEffort(before);
        }
    }

    @Test
    void resolveEffectiveReasoningEffortFallsBackToGlobalWhenSessionIsUnset() {
        String before = OllamaPluginSettings.getReasoningEffort();
        try {
            OllamaPluginSettings.setReasoningEffort("medium");
            OllamaSessionSettings settings = new OllamaSessionSettings();

            OllamaAiProcessManager.EffectiveReasoningEffort effective = manager.resolveEffectiveReasoningEffort(settings);

            assertEquals("medium", effective.value());
            assertFalse(effective.fromSession());
        }
        finally {
            OllamaPluginSettings.setReasoningEffort(before);
        }
    }
}

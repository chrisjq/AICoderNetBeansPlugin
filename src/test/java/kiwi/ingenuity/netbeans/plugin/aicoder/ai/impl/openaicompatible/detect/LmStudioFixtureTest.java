package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class LmStudioFixtureTest {

    @Test
    @Timeout(5)
    void autoDetectsLoadedBonsaiWithCapabilitiesAndWindow() throws Exception {
        Facts facts = facts(ServerTypeEnum.AUTO, "prism-ml/bonsai-27b", false);
        assertEquals(ServerKind.LMSTUDIO, facts.latest().kind());
        assertTrue(hasLoaded(facts.latest(), 131072));
        assertEquals(Boolean.TRUE, facts.latest().capabilities().tools());
        assertEquals(262144, facts.latest().lmStudioMaxContextLength());
        facts.close();
    }

    @Test
    @Timeout(5)
    void explicitOllamaFallsBackToDetectedLmStudioOnSecondCycle() throws Exception {
        Facts facts = facts(ServerTypeEnum.OLLAMA, "prism-ml/bonsai-27b", false);
        facts.registry.postReply("id");
        assertEquals(ServerKind.LMSTUDIO, facts.latest().kind());
        assertTrue(hasLoaded(facts.latest(), 131072));
        facts.close();
    }

    @Test
    @Timeout(5)
    void autoDetectsSplashLoadedWindow() throws Exception {
        Facts facts = facts(ServerTypeEnum.AUTO, "qwen3.8-27b-splash", false);
        assertTrue(hasLoaded(facts.latest(), 150000));
        assertEquals(List.of("off", "on"), facts.latest().capabilities().reasoningLevels());
        facts.close();
    }

    @Test
    @Timeout(5)
    void qwenModelReportsAllAdvertisedReasoningLevels() throws Exception {
        Facts facts = facts(ServerTypeEnum.AUTO, "qwen/qwen3.8-27b", false);
        assertEquals(List.of("off", "low", "medium", "xhigh", "on"), facts.latest().capabilities().reasoningLevels());
        facts.close();
    }

    @Test
    @Timeout(5)
    void notLoadedModelKeepsOnlyTheUpperBound() throws Exception {
        Facts facts = facts(ServerTypeEnum.AUTO, "google/gemma-4-26b-a4b-qat", false);
        assertFalse(facts.latest().windowReadings().stream()
                .anyMatch(reading -> reading.source() == WindowReading.Source.LMSTUDIO_LOADED));
        assertEquals(262144, facts.latest().lmStudioMaxContextLength());
        facts.close();
    }

    @Test
    @Timeout(5)
    void fallsBackToV0WhenV1IsNotFound() throws Exception {
        Facts facts = facts(ServerTypeEnum.AUTO, "prism-ml/bonsai-27b", true);
        assertEquals(ServerKind.LMSTUDIO, facts.latest().kind());
        assertTrue(hasLoaded(facts.latest(), 131072));
        facts.close();
    }

    private static Facts facts(ServerTypeEnum type, String model, boolean v1NotFound) throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        FixtureProber prober = new FixtureProber(v1NotFound);
        PollerRegistry registry = new PollerRegistry(
                OpenAiCompatibleLimits.defaults().withRedetectInterval(Duration.ofSeconds(30)), scheduler, 8,
                (root, key) -> prober);
        registry.join("id", "http://localhost:1234", "hash", null, type, model, prober);
        return new Facts(registry, prober, scheduler);
    }

    private static boolean hasLoaded(DetectedFacts facts, int tokens) {
        return facts.windowReadings().stream().anyMatch(reading
                -> reading.source() == WindowReading.Source.LMSTUDIO_LOADED && reading.tokens() == tokens);
    }

    private static JsonElement resource(String name) {
        String path = "/kiwi/ingenuity/netbeans/plugin/aicoder/ai/impl/openaicompatible/detect/" + name;
        try (InputStream input = LmStudioFixtureTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing fixture " + path);
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Facts(PollerRegistry registry, FixtureProber prober, ScheduledExecutorService scheduler) {

        DetectedFacts latest() {
            return prober.facts.get(prober.facts.size() - 1);
        }

        void close() {
            registry.leave("id");
            scheduler.shutdownNow();
        }
    }

    private static final class FixtureProber implements ServerPoller.Prober, ServerPoller.FactsListener {

        private final boolean v1NotFound;
        private final List<DetectedFacts> facts = new CopyOnWriteArrayList<>();

        FixtureProber(boolean v1NotFound) {
            this.v1NotFound = v1NotFound;
        }

        @Override
        public CompletableFuture<ProbeClient.ProbeResult> probe(String path) {
            if (ServerPoller.LM_V1.equals(path)) {
                return CompletableFuture.completedFuture(v1NotFound
                                                         ? new ProbeClient.ProbeResult(404, null, ProbeClient.Kind.NOT_FOUND)
                                                         : ok(resource("lmstudio-api-v1-models.json")));
            }
            if (ServerPoller.LM_V0.equals(path)) {
                return CompletableFuture.completedFuture(ok(resource("lmstudio-api-v0-models.json")));
            }
            if (ServerPoller.V1_MODELS.equals(path)) {
                return CompletableFuture.completedFuture(ok(resource("lmstudio-v1-models.json")));
            }
            if (ServerPoller.OLLAMA_TAGS.equals(path) || ServerPoller.OLLAMA_PS.equals(path)
                    || ServerPoller.OLLAMA_SHOW.equals(path)) {
                return CompletableFuture.completedFuture(ok(JsonParser.parseString(
                        "{\"error\":\"Unexpected endpoint or method. (GET /api/tags)\"}")));
            }
            return CompletableFuture.completedFuture(new ProbeClient.ProbeResult(404, null, ProbeClient.Kind.NOT_FOUND));
        }

        @Override
        public void onFacts(DetectedFacts value, boolean reachable) {
            facts.add(value);
        }

        private static ProbeClient.ProbeResult ok(JsonElement json) {
            return new ProbeClient.ProbeResult(200, json, ProbeClient.Kind.OK);
        }
    }
}

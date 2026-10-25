package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ServerPollerTest {

    private static final char[] KEY = new char[]{'k'};

    @Test
    @Timeout(5)
    void sharedByRootAndKeyAndStopsWhenTheLastSessionLeaves() throws Exception {
        FakeProber prober = new FakeProber();
        ScheduledExecutorService scheduler = scheduler();
        try {
            PollerRegistry registry = registry(scheduler, prober, 8, Duration.ofMillis(40));
            prober.hold();
            registry.join("a", "http://localhost:11434", "hash", KEY, ServerTypeEnum.OLLAMA, "llama", prober);
            registry.join("b", "http://localhost:11434/v1", "hash", KEY, ServerTypeEnum.OLLAMA, "mistral", prober);
            registry.join("c", "http://localhost:11434", "other-hash", KEY, ServerTypeEnum.OLLAMA, "llama", prober);

            assertEquals(2, registry.size());
            registry.leave("a");
            assertEquals(2, registry.size());
            registry.leave("b");
            registry.leave("c");
            assertEquals(0, registry.size());
            int calls = prober.calls.get();
            Thread.sleep(150);
            assertEquals(calls, prober.calls.get());
            prober.release();
            assertEquals(calls, prober.calls.get());
        }
        finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    @Timeout(5)
    void probeSetIsTheUnionAndNarrowsOnceTheKindIsKnown() {
        FakeProber prober = new FakeProber();
        prober.tagsSignature = true;
        ScheduledExecutorService scheduler = scheduler();
        try {
            PollerRegistry hosted = registry(scheduler, prober, 8, Duration.ofSeconds(30));
            hosted.join("hosted", "https://api.openai.com/v1", "hash", KEY, ServerTypeEnum.AUTO, "gpt-4", prober);
            assertEquals(List.of(ServerPoller.V1_MODELS), paths(prober));

            prober.paths.clear();
            PollerRegistry local = registry(scheduler, prober, 8, Duration.ofSeconds(30));
            local.join("auto", "http://localhost:11434", "hash", KEY, ServerTypeEnum.AUTO, "llama", prober);
            assertTrue(prober.paths.contains(ServerPoller.OLLAMA_TAGS));
            assertTrue(prober.paths.contains(ServerPoller.LLAMA_PROPS));
            assertTrue(prober.paths.contains(ServerPoller.V1_MODELS));
            assertEquals(ServerKind.OLLAMA, prober.facts.get(prober.facts.size() - 1).kind());

            prober.paths.clear();
            local.postReply("auto");
            assertTrue(prober.paths.contains(ServerPoller.OLLAMA_TAGS));
            assertTrue(prober.paths.contains(ServerPoller.V1_MODELS));
            assertFalse(prober.paths.contains(ServerPoller.LLAMA_PROPS));
            assertFalse(prober.paths.contains(ServerPoller.LM_V1));

            prober.paths.clear();
            prober.tagsSignature = true;
            PollerRegistry explicit = registry(scheduler, prober, 8, Duration.ofSeconds(30));
            explicit.join("chosen", "http://127.0.0.1:11434", "hash", KEY, ServerTypeEnum.OLLAMA, "llama", prober);
            assertTrue(paths(prober).contains(ServerPoller.OLLAMA_SHOW));
            assertFalse(paths(prober).contains(ServerPoller.V1_MODELS));
            assertFalse(paths(prober).contains(ServerPoller.LLAMA_PROPS));
            prober.paths.clear();
            explicit.postReply("chosen");
            assertTrue(paths(prober).contains(ServerPoller.V1_MODELS));
            assertTrue(paths(prober).contains(ServerPoller.OLLAMA_SHOW));
            assertFalse(paths(prober).contains(ServerPoller.LLAMA_PROPS));

            prober.tagsSignature = false;
            prober.paths.clear();
            PollerRegistry mismatch = registry(scheduler, prober, 8, Duration.ofSeconds(30));
            mismatch.join("mismatch", "http://127.0.0.1:11434", "other", KEY, ServerTypeEnum.OLLAMA, "llama", prober);
            assertEquals(ServerKind.GENERIC, prober.facts.get(prober.facts.size() - 1).kind());

            prober.tagsSignature = true;
            prober.paths.clear();
            PollerRegistry union = registry(scheduler, prober, 8, Duration.ofSeconds(30));
            union.join("ollama", "http://localhost:8080", "union", KEY, ServerTypeEnum.OLLAMA, "llama", prober);
            prober.paths.clear();
            union.join("llama", "http://localhost:8080", "union", KEY, ServerTypeEnum.LLAMA_CPP, "model", prober);
            List<String> unionPaths = paths(prober);
            assertTrue(unionPaths.contains(ServerPoller.OLLAMA_TAGS));
            assertTrue(unionPaths.contains(ServerPoller.OLLAMA_SHOW));
            assertTrue(unionPaths.contains(ServerPoller.LLAMA_PROPS));
            assertTrue(unionPaths.contains(ServerPoller.V1_MODELS));
            assertFalse(unionPaths.contains(ServerPoller.LM_V1));
        }
        finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    @Timeout(5)
    void perModelProbesAreCappedAndJustRepliedGoesFirst() {
        FakeProber prober = new FakeProber();
        ScheduledExecutorService scheduler = scheduler();
        try {
            PollerRegistry registry = registry(scheduler, prober, 2, Duration.ofSeconds(30));
            prober.hold();
            registry.join("a", "http://localhost:11434", "hash", null, ServerTypeEnum.OLLAMA, "a", prober);
            registry.join("b", "http://localhost:11434", "hash", null, ServerTypeEnum.OLLAMA, "b", prober);
            registry.join("c", "http://localhost:11434", "hash", null, ServerTypeEnum.OLLAMA, "c", prober);
            prober.release();
            prober.release();

            List<String> second = showModels(prober.cycles.get(1));
            assertEquals(List.of("b", "c"), second);
            prober.cycles.clear();
            registry.postReply("c");
            List<String> bumped = showModels(prober.cycles.get(prober.cycles.size() - 1));
            assertEquals("c", bumped.get(0));
            assertTrue(bumped.size() <= 2);
        }
        finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    @Timeout(5)
    void periodicTickIsSkippedWhileAnotherTriggerRunsOneMoreCycle() throws Exception {
        FakeProber prober = new FakeProber();
        ScheduledExecutorService scheduler = scheduler();
        try {
            PollerRegistry registry = registry(scheduler, prober, 8, Duration.ofMillis(30));
            prober.hold();
            registry.join("a", "http://localhost:11434", "hash", null, ServerTypeEnum.OLLAMA, "llama", prober);
            int during = prober.calls.get();
            registry.periodicTick("a");
            registry.postReply("a");
            assertEquals(during, prober.calls.get());
            int cycles = prober.cycles.size();
            prober.release();
            assertEquals(cycles + 1, prober.cycles.size());
            assertTrue(prober.publications.await(2, TimeUnit.SECONDS));
        }
        finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    @Timeout(5)
    void retargetLeavesTheOldPollerBeforeJoiningTheNewOne() {
        List<String> roots = new CopyOnWriteArrayList<>();
        ScheduledExecutorService scheduler = scheduler();
        try {
            PollerRegistry registry = new PollerRegistry(fast(Duration.ofSeconds(30)), scheduler, 8,
                                                         (root, key) -> new FakeProber() {
                                                     @Override
                                                     public CompletableFuture<ProbeClient.ProbeResult> probe(String path) {
                                                         roots.add(root);
                                                         return super.probe(path);
                                                     }
                                                 });
            registry.join("a", "http://localhost:11434", "hash", KEY, ServerTypeEnum.OLLAMA, "llama",
                          (facts, reachable) -> {
                          });
            int oldProbes = roots.size();
            assertTrue(oldProbes > 0);
            registry.retarget("a", "http://localhost:1234", "hash", KEY, ServerTypeEnum.LM_STUDIO, "model",
                              (facts, reachable) -> {
                              });
            assertTrue(roots.subList(oldProbes, roots.size()).stream().allMatch(root -> root.contains("1234")));
            assertEquals(1, registry.size());
        }
        finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    @Timeout(5)
    void lastGoodFactsAreKeptThroughAnOutage() {
        FakeProber prober = new FakeProber();
        prober.tagsSignature = true;
        ScheduledExecutorService scheduler = scheduler();
        try {
            PollerRegistry registry = registry(scheduler, prober, 8, Duration.ofSeconds(30));
            registry.join("a", "http://localhost:11434", "hash", null, ServerTypeEnum.AUTO, "llama", prober);
            DetectedFacts good = prober.facts.get(0);
            assertEquals(ServerKind.OLLAMA, good.kind());
            assertEquals(DetectedFacts.generation("http://localhost:11434", "llama", "hash"), good.generation());
            assertFalse(good.models().isEmpty());

            prober.unreachable = true;
            registry.postReply("a");
            assertFalse(prober.reachable.get(prober.reachable.size() - 1));
            assertEquals(good.kind(), prober.facts.get(prober.facts.size() - 1).kind());
            assertEquals(good.models(), prober.facts.get(prober.facts.size() - 1).models());

            prober.unreachable = false;
            prober.paths.clear();
            registry.postReply("a");
            assertTrue(prober.paths.contains(ServerPoller.LLAMA_PROPS));
        }
        finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    @Timeout(5)
    void endpointErrorBodiesAreNotCachedAsSuccessfulProbeResults() {
        FakeProber prober = new FakeProber();
        prober.tagsSignature = true;
        ScheduledExecutorService scheduler = scheduler();
        try {
            PollerRegistry registry = registry(scheduler, prober, 8, Duration.ofSeconds(30));
            registry.join("a", "http://localhost:11434", "hash", null, ServerTypeEnum.OLLAMA, "llama", prober);
            assertEquals(ServerKind.OLLAMA, prober.facts.get(prober.facts.size() - 1).kind());

            prober.endpointErrors = true;
            registry.postReply("a");
            assertEquals(ServerKind.GENERIC, prober.facts.get(prober.facts.size() - 1).kind());

            prober.endpointErrors = false;
            prober.tagsFailed = true;
            registry.postReply("a");

            assertEquals(ServerKind.OLLAMA, prober.facts.get(prober.facts.size() - 1).kind());
        }
        finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    @Timeout(5)
    void hostedTypeMismatchDoesNotExpandToImplementationSpecificProbes() {
        FakeProber prober = new FakeProber();
        ScheduledExecutorService scheduler = scheduler();
        try {
            PollerRegistry registry = registry(scheduler, prober, 8, Duration.ofSeconds(30));
            registry.join("hosted", "https://api.example.com", "hash", KEY, ServerTypeEnum.LM_STUDIO, "model", prober);
            prober.paths.clear();

            registry.postReply("hosted");

            assertEquals(List.of(ServerPoller.V1_MODELS), paths(prober));
        }
        finally {
            scheduler.shutdownNow();
        }
    }

    private static PollerRegistry registry(ScheduledExecutorService scheduler, FakeProber prober, int cap,
                                           Duration interval) {
        return new PollerRegistry(fast(interval), scheduler, cap, (root, key) -> prober);
    }

    private static OpenAiCompatibleLimits fast(Duration interval) {
        return OpenAiCompatibleLimits.defaults().withRedetectInterval(interval).withProbeTimeout(Duration.ofMillis(1));
    }

    private static ScheduledExecutorService scheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "poller-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static List<String> paths(FakeProber prober) {
        List<String> paths = new ArrayList<>();
        for (String recorded : prober.paths) {
            int space = recorded.indexOf(' ');
            paths.add(space < 0 ? recorded : recorded.substring(0, space));
        }
        return paths;
    }

    private static List<String> showModels(List<String> cycle) {
        List<String> models = new ArrayList<>();
        for (String recorded : cycle) {
            if (recorded.startsWith(ServerPoller.OLLAMA_SHOW)) {
                int model = recorded.indexOf("\"model\":\"");
                if (model >= 0) {
                    int start = model + "\"model\":\"".length();
                    models.add(recorded.substring(start, recorded.indexOf('"', start)));
                }
            }
        }
        return models;
    }

    private static class FakeProber implements ServerPoller.Prober, ServerPoller.FactsListener {

        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<List<String>> cycles = new CopyOnWriteArrayList<>();
        private final List<DetectedFacts> facts = new CopyOnWriteArrayList<>();
        private final List<Boolean> reachable = new CopyOnWriteArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch publications = new CountDownLatch(1);
        private volatile CompletableFuture<ProbeClient.ProbeResult> gate = CompletableFuture.completedFuture(null);
        private volatile boolean unreachable;
        private volatile boolean tagsSignature;
        private volatile boolean endpointErrors;
        private volatile boolean tagsFailed;
        private List<String> openCycle;

        @Override
        public CompletableFuture<ProbeClient.ProbeResult> probe(String path) {
            return probe(path, null);
        }

        @Override
        public CompletableFuture<ProbeClient.ProbeResult> probe(String path, String jsonBody) {
            calls.incrementAndGet();
            String recorded = jsonBody == null ? path : path + " " + jsonBody;
            paths.add(recorded);
            if (openCycle == null) {
                openCycle = new CopyOnWriteArrayList<>();
                cycles.add(openCycle);
            }
            openCycle.add(recorded);
            return gate.thenApply(ignored -> result(path));
        }

        void hold() {
            gate = new CompletableFuture<>();
        }

        void release() {
            CompletableFuture<ProbeClient.ProbeResult> done = gate;
            gate = new CompletableFuture<>();
            openCycle = null;
            done.complete(null);
        }

        @Override
        public void onFacts(DetectedFacts facts, boolean reachable) {
            this.facts.add(facts);
            this.reachable.add(reachable);
            publications.countDown();
        }

        private ProbeClient.ProbeResult result(String path) {
            if (unreachable) {
                return new ProbeClient.ProbeResult(0, null, ProbeClient.Kind.UNREACHABLE);
            }
            if (tagsFailed && ServerPoller.OLLAMA_TAGS.equals(path)) {
                return new ProbeClient.ProbeResult(0, null, ProbeClient.Kind.FAILED);
            }
            String json = endpointErrors && (ServerPoller.OLLAMA_TAGS.equals(path)
                    || ServerPoller.OLLAMA_PS.equals(path) || ServerPoller.OLLAMA_SHOW.equals(path))
                          ? "{\"error\":\"Unexpected endpoint or method.\"}"
                          : ServerPoller.OLLAMA_TAGS.equals(path) && tagsSignature
                            ? "{\"models\":[{\"name\":\"llama\",\"details\":{\"family\":\"llama\"}}]}"
                            : "{\"data\":[{\"id\":\"gpt-4\"}]}";
            return new ProbeClient.ProbeResult(200, JsonParser.parseString(json), ProbeClient.Kind.OK);
        }
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class OllamaChatContextBrokerTest {

    @Test
    void discoversRuntimeContextFromLoadedModel() {
        TestBroker broker = broker(url -> CompletableFuture.completedFuture(
                "{\"models\":[{\"name\":\"gpt-oss:20b\",\"context_length\":131072}]}"));
        try {
            broker.startContextDiscovery("http://ollama.example:11434/v1/");
            await(() -> broker.limitForTest() == 131072);
            assertEquals(131072, broker.limitForTest());
        } finally {
            broker.close();
        }
    }

    @Test
    void unloadedModelReturnsUnknownAndKeepsConfiguredTrimFallback() {
        AtomicInteger calls = new AtomicInteger();
        TestBroker broker = broker(url -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("{\"models\":[]}");
        });
        try {
            broker.startContextDiscovery("http://ollama.example:11434/v1");
            await(() -> calls.get() == 1);
            assertEquals(0, broker.limitForTest());
            assertEquals(12000, broker.trimThresholdForTest());
        } finally {
            broker.close();
        }
    }

    @Test
    void malformedOrUnreachableResponsesNeverEscapeOrInventALimit() {
        TestBroker malformed = broker(url -> CompletableFuture.completedFuture("{not-json"));
        TestBroker unreachable = broker(url -> CompletableFuture.failedFuture(
                new IllegalStateException("offline")));
        try {
            malformed.startContextDiscovery("http://ollama.example:11434/v1");
            unreachable.startContextDiscovery("http://ollama.example:11434/v1");
            assertEquals(0, malformed.limitForTest());
            assertEquals(0, unreachable.limitForTest());
        } finally {
            malformed.close();
            unreachable.close();
        }
    }

    @Test
    void usesDiscoveredWindowForTrimWithoutFetchingOnTrimPath() {
        AtomicInteger calls = new AtomicInteger();
        ContextBrokerSettings settings = new ContextBrokerSettings();
        settings.setTokenThreshold(0);
        TestBroker broker = new TestBroker(settings, url -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    "{\"models\":[{\"context_length\":100}]}");
        });
        try {
            broker.startContextDiscovery("http://ollama.example:11434/v1");
            await(() -> broker.limitForTest() == 100);
            assertEquals(80, broker.trimThresholdForTest());
            broker.append(new ChatMessage(ChatRole.USER, "x".repeat(240), java.util.List.of(), null));
            broker.trimIfNeeded();
            assertEquals(1, broker.entryCount());
            assertEquals(1, calls.get());
        } finally {
            broker.close();
        }
    }

    @Test
    void explicitThresholdWinsOverDiscoveredWindow() {
        ContextBrokerSettings settings = new ContextBrokerSettings();
        settings.setTokenThreshold(50);
        TestBroker broker = new TestBroker(settings, url -> CompletableFuture.completedFuture(
                "{\"models\":[{\"name\":\"model-a\",\"context_length\":100}]}"));
        try {
            broker.startContextDiscovery("http://ollama.example:11434/v1", "model-a");
            await(() -> broker.limitForTest() == 100);
            assertEquals(50, broker.trimThresholdForTest());
        } finally {
            broker.close();
        }
    }

    @Test
    void duplicateLoadedModelsUseSmallestRuntimeWindow() {
        String response = """
                 {"models":[{"name":"model-a","context_length":131072},{"name":"model-a","context_length":32768}]}
                 """;
        TestBroker broker = new TestBroker(new ContextBrokerSettings(), url -> CompletableFuture.completedFuture(response));
        try {
            broker.startContextDiscovery("http://ollama.example:11434/v1", "model-a");
            await(() -> broker.limitForTest() == 32768);
            assertEquals(32768, broker.limitForTest());
        } finally {
            broker.close();
        }
    }

    @Test
    void differentLoadedModelLeavesRuntimeWindowUnknown() {
        AtomicInteger calls = new AtomicInteger();
        TestBroker broker = new TestBroker(new ContextBrokerSettings(), url -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    "{\"models\":[{\"name\":\"other-model\",\"context_length\":100}]}");
        });
        try {
            broker.startContextDiscovery("http://ollama.example:11434/v1", "my-model");
            await(() -> calls.get() == 1);
            assertEquals(0, broker.limitForTest());
        } finally {
            broker.close();
        }
    }

    @Test
    void derivesNativeApiRootFromOpenAiCompatibleUrl() {
        assertEquals("http://ollama.example:11434/api/ps",
                OllamaChatContextBroker.nativeApiEndpoint("http://ollama.example:11434/v1/"));
    }

    private static TestBroker broker(Function<String, CompletableFuture<String>> api) {
        return new TestBroker(new ContextBrokerSettings(), api);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(condition.getAsBoolean());
    }

    @FunctionalInterface
    private interface BooleanSupplier {

        boolean getAsBoolean();
    }

    private static final class TestBroker extends OllamaChatContextBroker {

        TestBroker(ContextBrokerSettings settings,
                Function<String, CompletableFuture<String>> api) {
            super("test-session", settings, api);
        }

        int limitForTest() {
            return contextLimit();
        }

        int trimThresholdForTest() {
            return trimThreshold();
        }
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleHttpClientTest {

    private interface Script {

        void handle(HttpExchange exchange, int requestNumber) throws Exception;
    }

    private static final class FakeServer implements AutoCloseable {

        final HttpServer server;
        final ExecutorService pool = Executors.newCachedThreadPool();
        final AtomicInteger requests = new AtomicInteger();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<String> authorization = new CopyOnWriteArrayList<>();
        final List<String> protocols = new CopyOnWriteArrayList<>();
        final List<String> upgrades = new CopyOnWriteArrayList<>();

        FakeServer(Script script) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(pool);
            server.createContext("/v1/chat/completions", exchange -> {
                             int number = requests.incrementAndGet();
                             bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                             authorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                             protocols.add(exchange.getProtocol());
                             upgrades.add(String.valueOf(exchange.getRequestHeaders().getFirst("Upgrade")));
                             try {
                                 script.handle(exchange, number);
                             }
                             catch (Exception ignored) {
                                 // a closed or interrupted fake exchange ends the script
                             }
                             finally {
                                 exchange.close();
                             }
                         });
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        @Override
        public void close() {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    private static final class RecordingSink implements OpenAiCompatibleHttpClient.DebugSink {

        final List<String> sent = new CopyOnWriteArrayList<>();
        final List<String> received = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch retryEvent = new CountDownLatch(1);

        @Override
        public void sent(String json) {
            sent.add(json);
        }

        @Override
        public void received(String line) {
            received.add(line);
        }

        @Override
        public void event(String text) {
            events.add(text);
            if (text.startsWith("Retry")) {
                retryEvent.countDown();
            }
        }
    }

    private static OpenAiCompatibleLimits limits() {
        return OpenAiCompatibleLimits.defaults()
                .withCheckInterval(Duration.ofMillis(20))
                .withFirstByteTimeout(Duration.ofMillis(300))
                .withIdleTimeout(Duration.ofMillis(300))
                .withTotalCap(Duration.ofSeconds(2))
                .withRetryBackoff(Duration.ofMillis(50));
    }

    private static ChatRequestSpec spec(FakeServer server, Double temperature, Integer maxTokens) {
        return new ChatRequestSpec(server.base(), "model-x",
                                   List.of(new ChatMessage(ChatRole.USER, "hi", List.of(), null)),
                                   null, temperature, null, maxTokens, null);
    }

    private static OutputStream startSse(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        return exchange.getResponseBody();
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String delta(String text) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\n";
    }

    private static final String STOP = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";

    private static void reply(HttpExchange exchange, int status, String body, String... headerPairs)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            exchange.getResponseHeaders().add(headerPairs[i], headerPairs[i + 1]);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static ClientResult send(FakeServer server, ChatRequestSpec spec, CompatibilityFlags flags, char[] key,
                                     RequestHandle handle, List<String> deltas, RecordingSink sink)
            throws Exception {
        OpenAiCompatibleLimits limits = limits();
        return new OpenAiCompatibleHttpClient(limits)
                .send(spec, flags, key, List.of(), limits, handle, deltas::add, sink)
                .get(8, TimeUnit.SECONDS);
    }

    private static ClientResult send(FakeServer server) throws Exception {
        return send(server, spec(server, null, null), new CompatibilityFlags(), null, new RequestHandle(),
                    new CopyOnWriteArrayList<>(), new RecordingSink());
    }

    @Test
    @Timeout(10)
    void aProseStreamIsAssembledAndEndsCleanly() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> {
            OutputStream out = startSse(exchange);
            write(out, delta("Hel"));
            write(out, delta("lo"));
            write(out, STOP);
            write(out, "data: [DONE]\n\n");
        })) {
            List<String> deltas = new CopyOnWriteArrayList<>();
            RecordingSink sink = new RecordingSink();
            ClientResult result = send(server, spec(server, null, null), new CompatibilityFlags(), null,
                                       new RequestHandle(), deltas, sink);

            assertEquals(ClientResult.End.COMPLETE, result.end());
            assertEquals("Hello", result.reply().text());
            assertEquals("stop", result.reply().finishReason());
            assertTrue(result.reply().endedCleanly());
            assertFalse(result.retried());
            assertEquals(List.of("Hel", "lo"), deltas);
            assertEquals(1, sink.sent.size());
            assertTrue(sink.received.size() >= 3);
        }
    }

    @Test
    @Timeout(10)
    void aToolCallStreamIsAssembledByIndex() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> {
            OutputStream out = startSse(exchange);
            write(out, "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                  + "\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"a\\\":\"}}]}}]}\n\n");
            write(out, "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                  + "\"function\":{\"arguments\":\"1}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n");
            write(out, "data: [DONE]\n\n");
        })) {
            ClientResult result = send(server);

            assertEquals(ClientResult.End.COMPLETE, result.end());
            assertEquals(1, result.reply().toolCalls().size());
            assertEquals("call_1", result.reply().toolCalls().get(0).id());
            assertEquals("read", result.reply().toolCalls().get(0).name());
            assertEquals("{\"a\":1}", result.reply().toolCalls().get(0).argumentsJson());
        }
    }

    @Test
    @Timeout(10)
    void aNonStreamingJsonBodyIsParsedAsOneReply() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> reply(exchange, 200,
                                                                       "{\"choices\":[{\"message\":{\"content\":\"whole\"},\"finish_reason\":\"stop\"}],"
                                                                       + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}"))) {
            ClientResult result = send(server);

            assertEquals(ClientResult.End.COMPLETE, result.end());
            assertEquals("whole", result.reply().text());
            assertTrue(result.reply().endedCleanly());
            assertEquals(Integer.valueOf(7), result.reply().promptTokens());
        }
    }

    @Test
    @Timeout(10)
    void aFirstByteBeyondTheLimitIsAFirstByteTimeout() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> {
            Thread.sleep(2000);
            write(startSse(exchange), delta("late"));
        })) {
            long start = System.nanoTime();
            ClientResult result = send(server);

            assertEquals(ClientResult.End.TIMEOUT, result.end());
            assertEquals(ClientResult.TimeoutKind.FIRST_BYTE, result.timeoutKind());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000);
        }
    }

    @Test
    @Timeout(10)
    void aStallMidStreamIsAnIdleTimeoutAndKeepsThePartialText() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> {
            OutputStream out = startSse(exchange);
            write(out, delta("Hi"));
            Thread.sleep(5000);
        })) {
            ClientResult result = send(server);

            assertEquals(ClientResult.End.TIMEOUT, result.end());
            assertEquals(ClientResult.TimeoutKind.IDLE, result.timeoutKind());
            assertEquals("Hi", result.reply().text());
        }
    }

    @Test
    @Timeout(10)
    void aSlowTrickleBeyondTheTotalCapIsATotalTimeout() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> {
            OutputStream out = startSse(exchange);
            for (int i = 0; i < 60; i++) {
                write(out, delta("x"));
                Thread.sleep(100);
            }
        })) {
            ClientResult result = send(server);

            assertEquals(ClientResult.End.TIMEOUT, result.end());
            assertEquals(ClientResult.TimeoutKind.TOTAL, result.timeoutKind());
        }
    }

    @Test
    @Timeout(10)
    void anEofWithoutDoneAfterOutputIsNotACleanEnd() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> write(startSse(exchange), delta("partial")))) {
            ClientResult result = send(server);

            assertEquals(ClientResult.End.COMPLETE, result.end());
            assertEquals("partial", result.reply().text());
            assertTrue(result.reply().sawOutput());
            assertFalse(result.reply().endedCleanly());
            assertFalse(result.retried());
            assertEquals(1, server.requests.get());
        }
    }

    @Test
    @Timeout(10)
    void aDroppedConnectionBeforeOutputIsRetriedOnce() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> {
            if (n == 1) {
                return;
            }
            OutputStream out = startSse(exchange);
            write(out, delta("ok"));
            write(out, STOP);
        })) {
            ClientResult result = send(server);

            assertEquals(ClientResult.End.COMPLETE, result.end());
            assertEquals("ok", result.reply().text());
            assertTrue(result.retried());
            assertEquals(2, server.requests.get());
        }
    }

    @Test
    @Timeout(10)
    void aServiceUnavailableAnswerIsRetriedOnce() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> {
            if (n == 1) {
                reply(exchange, 503, "{\"error\":{\"message\":\"busy\"}}");
                return;
            }
            OutputStream out = startSse(exchange);
            write(out, delta("ok"));
            write(out, STOP);
        })) {
            ClientResult result = send(server);

            assertEquals(ClientResult.End.COMPLETE, result.end());
            assertTrue(result.retried());
            assertEquals(2, server.requests.get());
        }
    }

    @Test
    @Timeout(10)
    void aRateLimitWithRetryAfterZeroIsRetried() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> {
            if (n == 1) {
                reply(exchange, 429, "{\"error\":{\"message\":\"slow down\"}}", "Retry-After", "0");
                return;
            }
            OutputStream out = startSse(exchange);
            write(out, delta("ok"));
            write(out, STOP);
        })) {
            ClientResult result = send(server);

            assertEquals(ClientResult.End.COMPLETE, result.end());
            assertTrue(result.retried());
            assertEquals(2, server.requests.get());
        }
    }

    @Test
    @Timeout(10)
    void aRejectedKeyIsNotRetriedAndTheKeyIsSentAsBearer() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> reply(exchange, 401,
                                                                       "{\"error\":{\"message\":\"bad key\"}}"))) {
            ClientResult result = send(server, spec(server, null, null), new CompatibilityFlags(),
                                       "sk-1".toCharArray(), new RequestHandle(), new CopyOnWriteArrayList<>(),
                                       new RecordingSink());

            assertEquals(ClientResult.End.HTTP_ERROR, result.end());
            assertEquals(MappedError.Kind.KEY_REJECTED, result.error().kind());
            assertFalse(result.retried());
            assertEquals(1, server.requests.get());
            assertEquals("Bearer sk-1", server.authorization.get(0));
        }
    }

    @Test
    @Timeout(10)
    void noAuthorizationHeaderIsSentWithoutAKey() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> {
            OutputStream out = startSse(exchange);
            write(out, STOP);
        })) {
            send(server);

            assertEquals("null", server.authorization.get(0));
        }
    }

    @Test
    @Timeout(10)
    void aRejectedMaxTokensIsResentAsMaxCompletionTokens() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> {
            if (n == 1) {
                reply(exchange, 400, "{\"error\":{\"message\":\"Unsupported parameter: 'max_tokens' is not "
                      + "supported with this model. Use 'max_completion_tokens' instead.\"}}");
                return;
            }
            OutputStream out = startSse(exchange);
            write(out, delta("ok"));
            write(out, STOP);
        })) {
            CompatibilityFlags flags = new CompatibilityFlags();
            ClientResult result = send(server, spec(server, null, 100), flags, null, new RequestHandle(),
                                       new CopyOnWriteArrayList<>(), new RecordingSink());

            assertEquals(ClientResult.End.COMPLETE, result.end());
            assertFalse(result.retried());
            assertEquals(2, server.requests.get());
            assertTrue(server.bodies.get(0).contains("\"max_tokens\""));
            assertTrue(server.bodies.get(1).contains("\"max_completion_tokens\""));
            assertFalse(server.bodies.get(1).contains("\"max_tokens\""));
            assertTrue(flags.usesMaxCompletionTokens());
        }
    }

    @Test
    @Timeout(10)
    void aBare400WithOptionalFieldsIsResentWithoutThemAndTheFlagsTurnOff() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> {
            if (n == 1) {
                reply(exchange, 400, "{\"error\":{\"message\":\"Bad request\"}}");
                return;
            }
            OutputStream out = startSse(exchange);
            write(out, delta("ok"));
            write(out, STOP);
        })) {
            CompatibilityFlags flags = new CompatibilityFlags();
            RecordingSink sink = new RecordingSink();
            ClientResult result = send(server, spec(server, 0.5, null), flags, null, new RequestHandle(),
                                       new CopyOnWriteArrayList<>(), sink);

            assertEquals(ClientResult.End.COMPLETE, result.end());
            assertEquals(2, server.requests.get());
            assertTrue(server.bodies.get(0).contains("stream_options"));
            assertTrue(server.bodies.get(0).contains("\"temperature\""));
            assertFalse(server.bodies.get(1).contains("stream_options"));
            assertFalse(server.bodies.get(1).contains("\"temperature\""));
            assertFalse(flags.supportsStreamUsage());
            assertTrue(sink.events.contains(
                    "This server rejected optional request fields; they are no longer sent."));
        }
    }

    @Test
    @Timeout(10)
    void aBare400OnTheBlindResendIsFinalAndLeavesTheFlagsAlone() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> reply(exchange, 400,
                                                                       "{\"error\":{\"message\":\"Bad request\"}}"))) {
            CompatibilityFlags flags = new CompatibilityFlags();
            ClientResult result = send(server, spec(server, null, null), flags, null, new RequestHandle(),
                                       new CopyOnWriteArrayList<>(), new RecordingSink());

            assertEquals(ClientResult.End.HTTP_ERROR, result.end());
            assertEquals(MappedError.Kind.UNNAMED_REJECTION, result.error().kind());
            assertEquals(2, server.requests.get());
            assertTrue(flags.supportsStreamUsage());
        }
    }

    @Test
    @Timeout(10)
    void cancelMidStreamEndsWithin100msAndTheServerSeesTheDisconnect() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        try (FakeServer server = new FakeServer((exchange, n) -> {
            OutputStream out = startSse(exchange);
            try {
                while (true) {
                    write(out, delta("x"));
                    Thread.sleep(20);
                }
            }
            catch (IOException ex) {
                disconnected.countDown();
            }
        })) {
            OpenAiCompatibleLimits limits = limits().withIdleTimeout(Duration.ofSeconds(5))
                    .withFirstByteTimeout(Duration.ofSeconds(5)).withTotalCap(Duration.ofSeconds(30));
            RequestHandle handle = new RequestHandle();
            CountDownLatch firstDelta = new CountDownLatch(1);
            var future = new OpenAiCompatibleHttpClient(limits).send(spec(server, null, null),
                                                                 new CompatibilityFlags(), null, List.of(), limits, handle, text -> firstDelta.countDown(),
                                                                 new RecordingSink());
            assertTrue(firstDelta.await(5, TimeUnit.SECONDS));

            long start = System.nanoTime();
            handle.cancel();
            ClientResult result = future.get(100, TimeUnit.MILLISECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals(ClientResult.End.CANCELLED, result.end());
            assertTrue(elapsedMs < 100, "took " + elapsedMs + " ms");
            assertTrue(disconnected.await(3, TimeUnit.SECONDS), "the server never saw the disconnect");
        }
    }

    @Test
    @Timeout(10)
    void cancelDuringTheBackoffEndsAtOnce() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> reply(exchange, 503, "{\"error\":{}}"))) {
            OpenAiCompatibleLimits limits = limits().withRetryBackoff(Duration.ofSeconds(5));
            RequestHandle handle = new RequestHandle();
            RecordingSink sink = new RecordingSink();
            var future = new OpenAiCompatibleHttpClient(limits).send(spec(server, null, null),
                                                                 new CompatibilityFlags(), null, List.of(), limits, handle, text -> {
                                                         }, sink);
            assertTrue(sink.retryEvent.await(5, TimeUnit.SECONDS));

            long start = System.nanoTime();
            handle.cancel();
            ClientResult result = future.get(100, TimeUnit.MILLISECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals(ClientResult.End.CANCELLED, result.end());
            assertTrue(elapsedMs < 100, "took " + elapsedMs + " ms");
            assertEquals(1, server.requests.get());
        }
    }

    @Test
    @Timeout(10)
    void aRequestOpenedAfterCancelNeverReachesTheServer() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> write(startSse(exchange), STOP))) {
            RequestHandle handle = new RequestHandle();
            handle.cancel();
            ClientResult result = send(server, spec(server, null, null), new CompatibilityFlags(), null, handle,
                                       new CopyOnWriteArrayList<>(), new RecordingSink());

            assertEquals(ClientResult.End.CANCELLED, result.end());
            assertEquals(0, server.requests.get());
        }
    }

    @Test
    @Timeout(10)
    void theRequestUsesHttp11WithoutAnUpgrade() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> write(startSse(exchange), STOP))) {
            send(server);

            assertEquals(List.of("HTTP/1.1"), server.protocols);
            assertEquals(List.of("null"), server.upgrades);
        }
    }

    @Test
    @Timeout(10)
    void reasoningRejectionRetriesWithoutEffort() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> {
            if (n == 1) {
                reply(exchange, 400, "does not support thinking");
            }
            else {
                write(startSse(exchange), STOP);
            }
        }
        )) {
            CompatibilityFlags flags = new CompatibilityFlags();
            ChatRequestSpec request = new ChatRequestSpec(server.base(), "m",
                                                          List.of(new ChatMessage(ChatRole.USER, "hi", List.of(), null)), null,
                                                          null, null, null, "high");
            ClientResult result = send(server, request, flags, null, new RequestHandle(),
                                       new CopyOnWriteArrayList<>(), new RecordingSink());
            assertEquals(ClientResult.End.COMPLETE, result.end());
            assertEquals(2, server.requests.get());
            assertFalse(flags.supportsReasoningEffort());
            assertTrue(server.bodies.get(0).contains("reasoning_effort"));
            assertFalse(server.bodies.get(1).contains("reasoning_effort"));
        }
    }

    @Test
    @Timeout(10)
    void reasoningRejectionWithoutEffortDoesNotRetry() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n)
                -> reply(exchange, 400, "does not support thinking"))) {
            CompatibilityFlags flags = new CompatibilityFlags();
            flags.turnOff(CompatibilityFlags.STREAM_OPTIONS);
            ClientResult result = send(server, spec(server, null, null), flags, null, new RequestHandle(),
                                       new CopyOnWriteArrayList<>(), new RecordingSink());
            assertEquals(ClientResult.End.HTTP_ERROR, result.end());
            assertEquals(1, server.requests.get());
            assertTrue(flags.supportsReasoningEffort());
        }
    }

    @Test
    @Timeout(20)
    void aTenMegabyteSseLineEndsWithSizeLimit() throws Exception {
        try (FakeServer server = new FakeServer((exchange, n) -> {
            OutputStream out = startSse(exchange);
            write(out, "data: ");
            byte[] chunk = new byte[64 * 1024];
            Arrays.fill(chunk, (byte) 'x');
            for (int i = 0; i < 160; i++) {
                out.write(chunk);
                out.flush();
            }
        })) {
            ClientResult result = send(server);

            assertEquals(ClientResult.End.SIZE_LIMIT, result.end());
            assertNotNull(result.reply());
        }
    }
}

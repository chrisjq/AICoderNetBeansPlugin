package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatImage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A request whose images the server refuses is not retried by the client: the turn loop resends it without them. The
 * blind retry stays for every other bare rejection.
 */
class OpenAiCompatibleHttpClientImageTest {

    private static final String IMAGE_REFUSAL = "{\"error\":{\"message\":\"this model does not support image input\"}}";

    private static final class RefusingServer implements AutoCloseable {

        final HttpServer server;
        final ExecutorService pool = Executors.newCachedThreadPool();
        final AtomicInteger requests = new AtomicInteger();

        RefusingServer(String body) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(pool);
            server.createContext("/v1/chat/completions", exchange -> {
                             requests.incrementAndGet();
                             exchange.getRequestBody().readAllBytes();
                             byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                             exchange.getResponseHeaders().add("Content-Type", "application/json");
                             exchange.sendResponseHeaders(400, bytes.length);
                             exchange.getResponseBody().write(bytes);
                             exchange.close();
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

    private static OpenAiCompatibleLimits limits() {
        return OpenAiCompatibleLimits.defaults()
                .withCheckInterval(Duration.ofMillis(50))
                .withFirstByteTimeout(Duration.ofSeconds(5))
                .withIdleTimeout(Duration.ofSeconds(5))
                .withTotalCap(Duration.ofSeconds(8))
                .withRetryBackoff(Duration.ofMillis(50));
    }

    private static ClientResult send(RefusingServer server, ChatMessage last) throws Exception {
        ChatRequestSpec spec = new ChatRequestSpec(server.base(), "model-x",
                                                   List.of(new ChatMessage(ChatRole.USER, "look", List.of(), null),
                                                           last),
                                                   null, null, null, null, null);
        OpenAiCompatibleLimits limits = limits();
        // stream_options is on by default, so a bare 400 would normally get the blind retry.
        return new OpenAiCompatibleHttpClient(limits)
                .send(spec, new CompatibilityFlags(), null, List.of(), limits, new RequestHandle(), text -> {
              }, OpenAiCompatibleHttpClient.DebugSink.NONE)
                .get(8, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(10)
    void aRequestWithImagesRefusedForThemIsSentExactlyOnce() throws Exception {
        try (RefusingServer server = new RefusingServer(IMAGE_REFUSAL)) {
            ChatMessage image = new ChatMessage(ChatRole.USER, "Image from Read (call c1):", List.of(), null,
                                                List.of(new ChatImage("image/png", "QUJD", 2, 2)));

            ClientResult result = send(server, image);

            assertEquals(ClientResult.End.HTTP_ERROR, result.end());
            assertEquals(MappedError.Kind.UNNAMED_REJECTION, result.error().kind());
            assertEquals(1, server.requests.get(), "no blind retry with the image still in it");
        }
    }

    @Test
    @Timeout(10)
    void theSameRefusalWithoutImagesStillGetsTheBlindRetry() throws Exception {
        try (RefusingServer server = new RefusingServer(IMAGE_REFUSAL)) {
            send(server, new ChatMessage(ChatRole.USER, "and again", List.of(), null));

            assertEquals(2, server.requests.get(), "a request without images keeps the blind retry");
        }
    }

    @Test
    @Timeout(10)
    void anUnrelatedRefusalWithImagesStillGetsTheBlindRetry() throws Exception {
        try (RefusingServer server = new RefusingServer("{\"error\":{\"message\":\"bad request\"}}")) {
            ChatMessage image = new ChatMessage(ChatRole.USER, "Image from Read (call c1):", List.of(), null,
                                                List.of(new ChatImage("image/png", "QUJD", 2, 2)));

            send(server, image);

            assertEquals(2, server.requests.get());
        }
    }
}

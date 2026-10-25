package kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AcpDebugLogHookTest {

    @Test
    @Timeout(10)
    void observerReceivesSentAndReceivedWireLines() throws Exception {
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        PipedOutputStream inboundWriter = new PipedOutputStream();
        PipedInputStream inbound = new PipedInputStream(inboundWriter);
        AcpClientHandler handler = new AcpClientHandler() {
            @Override
            public void onSessionUpdate(String sessionId, JsonObject update) {
            }

            @Override
            public CompletableFuture<JsonObject> onRequestPermission(JsonObject params) {
                return CompletableFuture.completedFuture(new JsonObject());
            }

            @Override
            public CompletableFuture<JsonObject> onWriteTextFile(JsonObject params) {
                return CompletableFuture.completedFuture(new JsonObject());
            }

            @Override
            public CompletableFuture<JsonObject> onReadTextFile(JsonObject params) {
                return CompletableFuture.completedFuture(new JsonObject());
            }

            @Override
            public void onDisconnected(Exception cause) {
            }
        };
        AcpConnection connection = new AcpConnection(sent, inbound, handler);
        List<String> lines = new CopyOnWriteArrayList<>();
        connection.setDebugObserver((direction, line) -> lines.add(direction + ":" + line));
        connection.sendNotification(AcpMethodEnum.SESSION_CANCEL, new JsonObject());
        inboundWriter.write("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{}}\n".getBytes(StandardCharsets.UTF_8));
        inboundWriter.flush();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (lines.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        connection.close();
        inboundWriter.close();
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("SENT:")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("RECEIVED:")));
    }
}

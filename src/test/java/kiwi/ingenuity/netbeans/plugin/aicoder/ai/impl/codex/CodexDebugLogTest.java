package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import com.google.gson.JsonObject;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog.BackendDebugLog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog.BackendDebugLogWriter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog.DebugLogBudget;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("aicoder-debuglog")
class CodexDebugLogTest {

    @Test
    @Timeout(10)
    void logsRawSentAndReceivedJsonWithSecretsRedacted(@TempDir Path dir) throws Exception {
        try (PipedInputStream serverIn = new PipedInputStream(4096); PipedOutputStream pluginOut = new PipedOutputStream(serverIn); PipedInputStream pluginIn = new PipedInputStream(4096); PipedOutputStream serverOut = new PipedOutputStream(pluginIn)) {
            BackendDebugLog log = BackendDebugLogWriter.open(dir.resolve("backend-debug.log"), DebugLogBudget.defaults());
            log.registerSecret("secret-value");
            CountDownLatch received = new CountDownLatch(1);
            CodexJsonRpcClient client = new CodexJsonRpcClient(pluginOut, pluginIn,
                                                               (method, params) -> received.countDown(),
                                                               (method, params) -> java.util.concurrent.CompletableFuture.completedFuture(new JsonObject()),
                                                               cause -> {
                                                               });
            client.setDebugLogSink((direction, payload) -> log.log(direction, "jsonrpc", payload));

            JsonObject params = new JsonObject();
            params.addProperty("token", "secret-value");
            client.sendNotification("test/sent", params);
            serverOut.write(("{\"method\":\"test/received\",\"params\":{\"token\":\"secret-value\"}}\n")
                    .getBytes(StandardCharsets.UTF_8));
            serverOut.flush();
            assertTrue(received.await(5, TimeUnit.SECONDS));
            client.close();
            log.close().get(5, TimeUnit.SECONDS);

            String text = Files.readString(dir.resolve("backend-debug.log"));
            assertTrue(text.contains("SENT"));
            assertTrue(text.contains("RECEIVED"));
            assertFalse(text.contains("secret-value"));
        }
    }

    @Test
    @Timeout(10)
    void noSinkCreatesNoLog(@TempDir Path dir) throws Exception {
        try (PipedInputStream serverIn = new PipedInputStream(4096); PipedOutputStream pluginOut = new PipedOutputStream(serverIn); PipedInputStream pluginIn = new PipedInputStream(4096); PipedOutputStream serverOut = new PipedOutputStream(pluginIn)) {
            CountDownLatch received = new CountDownLatch(1);
            CodexJsonRpcClient client = new CodexJsonRpcClient(pluginOut, pluginIn,
                                                               (method, params) -> received.countDown(),
                                                               (method, params) -> java.util.concurrent.CompletableFuture.completedFuture(new JsonObject()),
                                                               cause -> {
                                                               });
            client.sendNotification("test/sent", new JsonObject());
            serverOut.write("{\"method\":\"test/received\"}\n".getBytes(StandardCharsets.UTF_8));
            serverOut.flush();
            assertTrue(received.await(5, TimeUnit.SECONDS));
            client.close();
        }
        assertFalse(Files.exists(dir.resolve("backend-debug.log")));
    }
}

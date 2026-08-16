package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AcpConnectionTest {

    // plugin→agent pipes
    private PipedInputStream agentIn;
    private PipedOutputStream pluginOut;
    // agent→plugin pipes
    private PipedInputStream pluginIn;
    private PipedOutputStream agentOut;

    private BufferedReader agentReader;
    private AcpConnection connection;
    private TestHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        agentIn = new PipedInputStream(65536);
        pluginOut = new PipedOutputStream(agentIn);
        pluginIn = new PipedInputStream(65536);
        agentOut = new PipedOutputStream(pluginIn);
        agentReader = new BufferedReader(new InputStreamReader(agentIn, StandardCharsets.UTF_8));
        handler = new TestHandler();
        connection = new AcpConnection(pluginOut, pluginIn, handler);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
        try {
            agentOut.close();
        }
        catch (Exception ignore) {
        }
    }

    private void agentSend(JsonObject msg) throws Exception {
        byte[] bytes = (msg.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        agentOut.write(bytes);
        agentOut.flush();
    }

    private JsonObject agentRead() throws Exception {
        String line = agentReader.readLine();
        assertNotNull(line, "expected a line from plugin");
        return JsonParser.parseString(line).getAsJsonObject();
    }

    @Test
    void requestResponseCorrelation() throws Exception {
        CompletableFuture<JsonObject> future = connection.sendRequest(AcpMethodEnum.INITIALIZE, new JsonObject());
        JsonObject outbound = agentRead();
        long id = outbound.get("id").getAsLong();
        assertEquals("initialize", outbound.get("method").getAsString());

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", 1);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", id);
        response.add("result", result);
        agentSend(response);

        JsonObject received = future.get(5, TimeUnit.SECONDS);
        assertEquals(1, received.get("protocolVersion").getAsInt());
    }

    @Test
    void sessionUpdateNotificationDispatchedToHandler() throws Exception {
        JsonObject update = new JsonObject();
        update.addProperty("sessionUpdate", "agent_message_chunk");
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", "ses_abc");
        params.add("update", update);
        JsonObject notif = new JsonObject();
        notif.addProperty("jsonrpc", "2.0");
        notif.addProperty("method", "session/update");
        notif.add("params", params);
        agentSend(notif);

        JsonObject received = handler.receivedUpdates.poll(5, TimeUnit.SECONDS);
        assertNotNull(received, "handler should receive session/update");
        assertEquals("agent_message_chunk", received.get("sessionUpdate").getAsString());
    }

    @Test
    void inboundPermissionRequestDispatchedAndReplied() throws Exception {
        JsonObject outcome = new JsonObject();
        outcome.addProperty("outcome", "selected");
        outcome.addProperty("optionId", "once");
        JsonObject handlerResult = new JsonObject();
        handlerResult.add("outcome", outcome);
        handler.permissionFuture = CompletableFuture.completedFuture(handlerResult);

        JsonObject params = new JsonObject();
        params.addProperty("sessionId", "ses_abc");
        JsonObject inboundReq = new JsonObject();
        inboundReq.addProperty("jsonrpc", "2.0");
        inboundReq.addProperty("id", 99);
        inboundReq.addProperty("method", "session/request_permission");
        inboundReq.add("params", params);
        agentSend(inboundReq);

        JsonObject reply = agentRead();
        assertEquals(99, reply.get("id").getAsLong());
        assertFalse(reply.has("error"), "reply must not have error field");
        JsonObject replyResult = reply.getAsJsonObject("result");
        assertNotNull(replyResult);
        assertEquals("once", replyResult.getAsJsonObject("outcome").get("optionId").getAsString());
    }

    @Test
    void deadlockGuard_readerNotBlockedByLongRunningPermission() throws Exception {
        // Permission handler that blocks indefinitely — simulates user reviewing a diff
        CompletableFuture<JsonObject> slowPermission = new CompletableFuture<>();
        handler.permissionFuture = slowPermission;

        JsonObject permReq = new JsonObject();
        permReq.addProperty("jsonrpc", "2.0");
        permReq.addProperty("id", 10);
        permReq.addProperty("method", "session/request_permission");
        permReq.add("params", new JsonObject());
        agentSend(permReq);

        // While permission is pending, send an independent outbound request
        CompletableFuture<JsonObject> outFuture = connection.sendRequest(AcpMethodEnum.SESSION_LIST, new JsonObject());
        JsonObject outbound = agentRead();
        long outId = outbound.get("id").getAsLong();
        assertEquals("session/list", outbound.get("method").getAsString());

        // Reply to the outbound request — reader must process this even though permission is still pending
        JsonObject listResp = new JsonObject();
        listResp.addProperty("jsonrpc", "2.0");
        listResp.addProperty("id", outId);
        listResp.add("result", new JsonObject());
        agentSend(listResp);

        // Must complete in 3 seconds; proves reader was not blocked by the pending permission
        assertNotNull(outFuture.get(3, TimeUnit.SECONDS), "reader must not block on pending permission");

        // Unblock permission so tearDown does not leave threads wedged
        slowPermission.complete(new JsonObject());
    }

    @Test
    void malformedLineSkippedAndSubsequentMessageProcessed() throws Exception {
        agentOut.write("not-valid-json\n".getBytes(StandardCharsets.UTF_8));
        agentOut.flush();

        JsonObject update = new JsonObject();
        update.addProperty("sessionUpdate", "usage_update");
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", "ses_x");
        params.add("update", update);
        JsonObject notif = new JsonObject();
        notif.addProperty("jsonrpc", "2.0");
        notif.addProperty("method", "session/update");
        notif.add("params", params);
        agentSend(notif);

        JsonObject received = handler.receivedUpdates.poll(5, TimeUnit.SECONDS);
        assertNotNull(received, "should receive update after malformed line");
        assertEquals("usage_update", received.get("sessionUpdate").getAsString());
    }

    @Test
    void errorResponseCompletesExceptionally() throws Exception {
        CompletableFuture<JsonObject> future = connection.sendRequest(AcpMethodEnum.SESSION_NEW, new JsonObject());
        JsonObject outbound = agentRead();
        long id = outbound.get("id").getAsLong();

        JsonObject error = new JsonObject();
        error.addProperty("code", AcpErrorCodeEnum.METHOD_NOT_FOUND.code());
        error.addProperty("message", "Method not found");
        JsonObject errorResp = new JsonObject();
        errorResp.addProperty("jsonrpc", "2.0");
        errorResp.addProperty("id", id);
        errorResp.add("error", error);
        agentSend(errorResp);

        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void eofNotifiesHandlerDisconnected() throws Exception {
        agentOut.close();
        assertTrue(handler.disconnectedLatch.await(5, TimeUnit.SECONDS), "onDisconnected must be called on EOF");
    }

    @Test
    void outputIsCompactSingleLine() throws Exception {
        JsonObject reqParams = new JsonObject();
        reqParams.addProperty("protocolVersion", 1);
        connection.sendRequest(AcpMethodEnum.INITIALIZE, reqParams);
        String line = agentReader.readLine();
        assertNotNull(line, "must have written a line");
        // If the output were pretty-printed, readLine() would return only "{" and parseString would fail
        JsonObject parsed = JsonParser.parseString(line).getAsJsonObject();
        assertEquals("initialize", parsed.get("method").getAsString());
        assertTrue(parsed.has("id"), "request must include id");
        assertTrue(parsed.has("params"), "request must include params");
    }

    static class TestHandler implements AcpClientHandler {

        final LinkedBlockingQueue<JsonObject> receivedUpdates = new LinkedBlockingQueue<>();
        final CountDownLatch disconnectedLatch = new CountDownLatch(1);
        volatile CompletableFuture<JsonObject> permissionFuture = CompletableFuture.completedFuture(new JsonObject());
        volatile CompletableFuture<JsonObject> writeFileFuture = CompletableFuture.completedFuture(new JsonObject());
        volatile CompletableFuture<JsonObject> readFileFuture = CompletableFuture.completedFuture(new JsonObject());

        @Override
        public void onSessionUpdate(String sessionId, JsonObject update) {
            receivedUpdates.offer(update);
        }

        @Override
        public CompletableFuture<JsonObject> onRequestPermission(JsonObject params) {
            return permissionFuture;
        }

        @Override
        public CompletableFuture<JsonObject> onWriteTextFile(JsonObject params) {
            return writeFileFuture;
        }

        @Override
        public CompletableFuture<JsonObject> onReadTextFile(JsonObject params) {
            return readFileFuture;
        }

        @Override
        public void onDisconnected(Exception cause) {
            disconnectedLatch.countDown();
        }
    }
}

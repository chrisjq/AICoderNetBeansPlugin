package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodexJsonRpcClientTest {

    private static boolean threadExists(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> name.equals(t.getName()) && t.isAlive());
    }

    /**
     * Names every surviving thread and dumps what it is executing. This test
     * matches threads by name across the whole JVM, so a survivor may belong to
     * a client created by an entirely different test class — the stack trace is
     * the only thing that distinguishes "close() failed to stop our thread"
     * from "someone else leaked one with the same name". Without it the failure
     * says only "expected false but was true", which is what made this hard to
     * place.
     */
    private static String dumpThreads(String name) {
        StringBuilder sb = new StringBuilder();
        Thread.getAllStackTraces().forEach((t, stack) -> {
            if (name.equals(t.getName()) && t.isAlive()) {
                sb.append("\n  --- ").append(t.getName())
                        .append(" (id=").append(t.getId())
                        .append(", state=").append(t.getState())
                        .append(", daemon=").append(t.isDaemon()).append(") ---");
                for (StackTraceElement e : stack) {
                    sb.append("\n      at ").append(e);
                }
            }
        });
        return sb.length() == 0 ? "\n  (no surviving thread at dump time — it died between poll and dump)" : sb.toString();
    }

    private static JsonObject tagged(String method, JsonObject params) {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("__method", method);
        wrapper.add("__params", params);
        return wrapper;
    }

    // plugin→server pipes
    private PipedInputStream serverIn;
    private PipedOutputStream pluginOut;
    // server→plugin pipes
    private PipedInputStream pluginIn;
    private PipedOutputStream serverOut;

    private BufferedReader serverReader;
    private CodexJsonRpcClient client;
    private LinkedBlockingQueue<JsonObject> receivedNotifications;
    private LinkedBlockingQueue<Object[]> receivedServerRequests;
    private CountDownLatch disconnectedLatch;
    private volatile CompletableFuture<JsonObject> serverRequestReply;

    @BeforeEach
    void setUp() throws Exception {
        serverIn = new PipedInputStream(65536);
        pluginOut = new PipedOutputStream(serverIn);
        pluginIn = new PipedInputStream(65536);
        serverOut = new PipedOutputStream(pluginIn);
        serverReader = new BufferedReader(new InputStreamReader(serverIn, StandardCharsets.UTF_8));
        receivedNotifications = new LinkedBlockingQueue<>();
        receivedServerRequests = new LinkedBlockingQueue<>();
        disconnectedLatch = new CountDownLatch(1);
        serverRequestReply = CompletableFuture.completedFuture(new JsonObject());
        client = new CodexJsonRpcClient(pluginOut, pluginIn,
                (method, params) -> receivedNotifications.offer(tagged(method, params)),
                (method, params) -> {
                    receivedServerRequests.offer(new Object[]{method, params});
                    return serverRequestReply;
                },
                cause -> disconnectedLatch.countDown());
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        try {
            serverOut.close();
        }
        catch (Exception ignore) {
        }
    }

    private void serverSend(JsonObject msg) throws Exception {
        byte[] bytes = (msg.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        serverOut.write(bytes);
        serverOut.flush();
    }

    private JsonObject serverRead() throws Exception {
        String line = serverReader.readLine();
        assertNotNull(line, "expected a line from the plugin");
        return JsonParser.parseString(line).getAsJsonObject();
    }

    @Test
    void requestResponseCorrelation() throws Exception {
        CompletableFuture<JsonObject> future = client.sendRequest("initialize", new JsonObject());
        JsonObject outbound = serverRead();
        long id = outbound.get("id").getAsLong();
        assertEquals("initialize", outbound.get("method").getAsString());

        JsonObject result = new JsonObject();
        result.addProperty("codexHome", "/home/user/.codex");
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", id);
        response.add("result", result);
        serverSend(response);

        JsonObject received = future.get(5, TimeUnit.SECONDS);
        assertEquals("/home/user/.codex", received.get("codexHome").getAsString());
    }

    @Test
    void idsIncrementAcrossMultipleRequests() throws Exception {
        client.sendRequest("initialize", new JsonObject());
        long firstId = serverRead().get("id").getAsLong();
        client.sendRequest("thread/start", new JsonObject());
        long secondId = serverRead().get("id").getAsLong();
        assertNotEquals(firstId, secondId, "each request must get a distinct id");
        assertEquals(firstId + 1, secondId, "ids increment monotonically");
    }

    @Test
    void notificationDispatchedToListener() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("status", "ready");
        JsonObject notif = new JsonObject();
        notif.addProperty("jsonrpc", "2.0");
        notif.addProperty("method", "thread/started");
        notif.add("params", params);
        serverSend(notif);

        JsonObject received = receivedNotifications.poll(5, TimeUnit.SECONDS);
        assertNotNull(received, "listener should receive the notification");
        assertEquals("thread/started", received.get("__method").getAsString());
        assertEquals("ready", received.getAsJsonObject("__params").get("status").getAsString());
    }

    @Test
    void inboundServerRequestDispatchedAndReplied() throws Exception {
        JsonObject replyResult = new JsonObject();
        replyResult.addProperty("decision", "approved");
        serverRequestReply = CompletableFuture.completedFuture(replyResult);

        JsonObject params = new JsonObject();
        params.addProperty("threadId", "th_abc");
        JsonObject inboundReq = new JsonObject();
        inboundReq.addProperty("jsonrpc", "2.0");
        inboundReq.addProperty("id", 99);
        inboundReq.addProperty("method", "guardian/reviewStarted");
        inboundReq.add("params", params);
        serverSend(inboundReq);

        JsonObject reply = serverRead();
        assertEquals(99, reply.get("id").getAsLong());
        assertFalse(reply.has("error"), "reply must not have an error field");
        assertEquals("approved", reply.getAsJsonObject("result").get("decision").getAsString());

        Object[] captured = receivedServerRequests.poll(5, TimeUnit.SECONDS);
        assertNotNull(captured);
        assertEquals("guardian/reviewStarted", captured[0]);
    }

    @Test
    void inboundServerRequestFailureSendsErrorResponse() throws Exception {
        serverRequestReply = CompletableFuture.failedFuture(new UnsupportedOperationException("no handler yet"));

        JsonObject inboundReq = new JsonObject();
        inboundReq.addProperty("jsonrpc", "2.0");
        inboundReq.addProperty("id", 7);
        inboundReq.addProperty("method", "unknown/method");
        inboundReq.add("params", new JsonObject());
        serverSend(inboundReq);

        JsonObject reply = serverRead();
        assertEquals(7, reply.get("id").getAsLong());
        assertTrue(reply.has("error"), "unsupported server requests must be answered with an error, not left hanging");
    }

    /**
     * The error response is the ONLY channel that can carry a reason back to Codex — FileChangeRequestApprovalResponse
     * has just a decision field and no message — so a handler refusing a request must be able to say why, and that
     * text must arrive intact. A dependent stage wraps the cause in a CompletionException, whose getMessage() is the
     * cause's toString(), so without unwrapping the wire message reads
     * "java.lang.UnsupportedOperationException: <reason>" and the reason is buried behind a Java class name.
     */
    @Test
    void inboundServerRequestFailureSendsTheReasonWithoutTheExceptionClassName() throws Exception {
        serverRequestReply = CompletableFuture.failedFuture(
                new UnsupportedOperationException("this client supports only add and update file changes"));

        JsonObject inboundReq = new JsonObject();
        inboundReq.addProperty("jsonrpc", "2.0");
        inboundReq.addProperty("id", 11);
        inboundReq.addProperty("method", "item/fileChange/requestApproval");
        inboundReq.add("params", new JsonObject());
        serverSend(inboundReq);

        JsonObject reply = serverRead();
        String message = reply.getAsJsonObject("error").get("message").getAsString();

        assertEquals("this client supports only add and update file changes", message,
                "the reason must reach Codex verbatim, with no exception class name prefixed");
    }

    @Test
    void deadlockGuard_readerNotBlockedByLongRunningServerRequest() throws Exception {
        CompletableFuture<JsonObject> slowReply = new CompletableFuture<>();
        serverRequestReply = slowReply;

        JsonObject blockingReq = new JsonObject();
        blockingReq.addProperty("jsonrpc", "2.0");
        blockingReq.addProperty("id", 10);
        blockingReq.addProperty("method", "guardian/reviewStarted");
        blockingReq.add("params", new JsonObject());
        serverSend(blockingReq);

        // While the server request is pending, an independent outbound request must
        // still complete — the reader thread must not be blocked by it.
        CompletableFuture<JsonObject> outFuture = client.sendRequest("thread/start", new JsonObject());
        JsonObject outbound = serverRead();
        long outId = outbound.get("id").getAsLong();
        assertEquals("thread/start", outbound.get("method").getAsString());

        JsonObject startResp = new JsonObject();
        startResp.addProperty("jsonrpc", "2.0");
        startResp.addProperty("id", outId);
        startResp.add("result", new JsonObject());
        serverSend(startResp);

        assertNotNull(outFuture.get(3, TimeUnit.SECONDS), "reader must not block on a pending server request");

        slowReply.complete(new JsonObject());
    }

    @Test
    void malformedLineSkippedAndSubsequentMessageProcessed() throws Exception {
        serverOut.write("not-valid-json\n".getBytes(StandardCharsets.UTF_8));
        serverOut.flush();

        JsonObject params = new JsonObject();
        params.addProperty("marker", "after-garbage");
        JsonObject notif = new JsonObject();
        notif.addProperty("jsonrpc", "2.0");
        notif.addProperty("method", "thread/tokenUsageUpdated");
        notif.add("params", params);
        serverSend(notif);

        JsonObject received = receivedNotifications.poll(5, TimeUnit.SECONDS);
        assertNotNull(received, "should receive the notification after a malformed line");
        assertEquals("after-garbage", received.getAsJsonObject("__params").get("marker").getAsString());
    }

    @Test
    void errorResponseCompletesExceptionally() throws Exception {
        CompletableFuture<JsonObject> future = client.sendRequest("thread/start", new JsonObject());
        JsonObject outbound = serverRead();
        long id = outbound.get("id").getAsLong();

        JsonObject error = new JsonObject();
        error.addProperty("code", CodexJsonRpcErrorCodeEnum.METHOD_NOT_FOUND.code());
        error.addProperty("message", "Method not found");
        JsonObject errorResp = new JsonObject();
        errorResp.addProperty("jsonrpc", "2.0");
        errorResp.addProperty("id", id);
        errorResp.add("error", error);
        serverSend(errorResp);

        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        Throwable cause = ex.getCause();
        assertTrue(cause instanceof CodexJsonRpcException, "cause must be CodexJsonRpcException");
        assertEquals(CodexJsonRpcErrorCodeEnum.METHOD_NOT_FOUND.code(), ((CodexJsonRpcException) cause).code());
    }

    @Test
    void eofNotifiesConnectionListenerDisconnected() throws Exception {
        serverOut.close();
        assertTrue(disconnectedLatch.await(5, TimeUnit.SECONDS), "onDisconnected must be called on EOF");
    }

    // ---- Dead-pipe detection: PrintWriter swallows IOException by design, so
    // writeMessage must check PrintWriter#checkError() itself — otherwise a write
    // to a dead process's stdin never surfaces, sendRequest's catch block never
    // fires, and the returned future (and whatever "a turn is in flight" flag a
    // caller tracks from it) hangs forever. Real busy-forever failure mode. ----
    @Test
    void sendRequestCompletesExceptionallyWhenPipeIsClosed() throws Exception {
        // serverIn is the sink pluginOut writes into; closing it simulates the
        // codex process's stdin pipe being gone (process died / pipe broken).
        serverIn.close();

        CompletableFuture<JsonObject> future = client.sendRequest("turn/start", new JsonObject());

        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS),
                "a write to a closed pipe must fail the future promptly, not hang forever");
        assertNotNull(ex);
    }

    @Test
    void sendNotificationOnClosedPipeThrowsRatherThanSilentlySwallowing() throws Exception {
        serverIn.close();
        assertThrows(Exception.class, () -> client.sendNotification("initialized", new JsonObject()),
                "a dead pipe must be surfaced, not silently swallowed by PrintWriter");
    }

    @Test
    void outputIsCompactSingleLine() throws Exception {
        JsonObject reqParams = new JsonObject();
        reqParams.addProperty("cwd", "/tmp");
        client.sendRequest("thread/start", reqParams);
        String line = serverReader.readLine();
        assertNotNull(line, "must have written a line");
        // If the output were pretty-printed, readLine() would return only "{" and parseString would fail.
        JsonObject parsed = JsonParser.parseString(line).getAsJsonObject();
        assertEquals("thread/start", parsed.get("method").getAsString());
        assertTrue(parsed.has("id"), "request must include id");
        assertTrue(parsed.has("params"), "request must include params");
    }

    @Test
    void pendingRequestsFailWhenClientCloses() throws Exception {
        CompletableFuture<JsonObject> future = client.sendRequest("initialize", new JsonObject());
        serverRead();
        client.close();
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    // ---- Streaming-order guard: the same trap commit d363682 fixed for OpenCode ----
    @Test
    void notificationsAreDeliveredInSubmittedOrder() throws Exception {
        // REGRESSION GUARD: with a cached thread pool the notify path would run
        // handlers concurrently, so notifications could be delivered out of order —
        // garbling streamed assistant text. Notification 0's handler blocks on a
        // latch; with a single-thread FIFO notify executor, notifications 1..N-1
        // must queue behind it rather than racing ahead.
        int n = 50;
        CountDownLatch firstGate = new CountDownLatch(1);
        LinkedBlockingQueue<String> delivered = new LinkedBlockingQueue<>();
        AtomicBoolean first = new AtomicBoolean(true);

        PipedInputStream localPluginIn = new PipedInputStream(65536);
        PipedOutputStream localServerOut = new PipedOutputStream(localPluginIn);
        PipedInputStream localServerIn = new PipedInputStream(65536);
        PipedOutputStream localPluginOut = new PipedOutputStream(localServerIn);

        CodexJsonRpcClient fresh = new CodexJsonRpcClient(localPluginOut, localPluginIn,
                (method, params) -> {
                    if (first.compareAndSet(true, false)) {
                        try {
                            firstGate.await(10, TimeUnit.SECONDS);
                        }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    delivered.offer(params.get("text").getAsString());
                },
                (method, params) -> CompletableFuture.completedFuture(new JsonObject()),
                cause -> {
                });
        try {
            StringBuilder expected = new StringBuilder();
            for (int i = 0; i < n; i++) {
                String text = "chunk-" + i;
                expected.append(text);
                JsonObject params = new JsonObject();
                params.addProperty("text", text);
                JsonObject notif = new JsonObject();
                notif.addProperty("jsonrpc", "2.0");
                notif.addProperty("method", "item/agentMessage/delta");
                notif.add("params", params);
                byte[] bytes = (notif.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                localServerOut.write(bytes);
                localServerOut.flush();
            }
            Thread.sleep(200);
            firstGate.countDown();

            StringBuilder received = new StringBuilder();
            for (int i = 0; i < n; i++) {
                String text = delivered.poll(5, TimeUnit.SECONDS);
                assertNotNull(text, "timed out waiting for chunk " + i);
                received.append(text);
            }
            assertEquals(expected.toString(), received.toString(),
                    "notifications must arrive in submitted order");
        }
        finally {
            firstGate.countDown();
            fresh.close();
            localServerOut.close();
        }
    }

    @Test
    void closeShutsBothExecutors() throws Exception {
        // Prime codex-notify by delivering a notification and waiting for it.
        JsonObject params = new JsonObject();
        params.addProperty("marker", "prime");
        JsonObject notif = new JsonObject();
        notif.addProperty("jsonrpc", "2.0");
        notif.addProperty("method", "thread/started");
        notif.add("params", params);
        serverSend(notif);
        assertNotNull(receivedNotifications.poll(3, TimeUnit.SECONDS), "listener must receive notification");

        // Prime codex-dispatch by completing a request.
        CompletableFuture<JsonObject> req = client.sendRequest("initialize", new JsonObject());
        JsonObject outbound = serverRead();
        long id = outbound.get("id").getAsLong();
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.addProperty("id", id);
        resp.add("result", new JsonObject());
        serverSend(resp);
        req.get(3, TimeUnit.SECONDS);

        assertTrue(threadExists("codex-notify"), "codex-notify must be alive before close");
        assertTrue(threadExists("codex-dispatch"), "codex-dispatch must be alive before close");

        client.close();

        // 2000ms is deliberate and adequate — do not raise it to make this pass.
        // When this failed in the full suite while passing standalone, the cause was
        // NOT scheduling latency: CodexAiProcessManager was orphaning a whole
        // CodexJsonRpcClient on disconnect/crash (nulling the field without calling
        // close(), the only thing that shuts the executors down), so a real leaked
        // codex-notify/codex-dispatch pair from an earlier test class was still alive
        // here. Threads are matched by name across the JVM, so this test sees them.
        // A leaked thread never terminates, so a longer deadline would only have
        // hidden a production resource leak behind a slower green tick.
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (!threadExists("codex-notify") && !threadExists("codex-dispatch")) {
                break;
            }
            Thread.sleep(20);
        }

        assertFalse(threadExists("codex-notify"),
                () -> "codex-notify thread must be gone after close" + dumpThreads("codex-notify"));
        assertFalse(threadExists("codex-dispatch"),
                () -> "codex-dispatch thread must be gone after close" + dumpThreads("codex-dispatch"));
    }
}

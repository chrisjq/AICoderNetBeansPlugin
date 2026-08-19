package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bidirectional newline-delimited JSON-RPC 2.0 transport for Codex's
 * {@code app-server} protocol (design doc §0a, §6).
 *
 * <p>
 * A single daemon reader thread reads lines and routes them in three ways:
 * <ol>
 * <li>method + id → inbound request from the server → dispatch executor,
 * answered via {@link CodexServerRequestHandler}
 * <li>method only → notification → notify executor (FIFO, single thread),
 * delivered via {@link CodexNotificationListener}
 * <li>id only → response to our own request → complete the pending future via
 * the dispatch executor
 * </ol>
 *
 * <p>
 * Notifications and the disconnect callback run on a single-thread executor
 * ({@code codex-notify}) to guarantee FIFO delivery order — deltas dispatched
 * out of order garble streamed text (design doc §8, commit {@code d363682}
 * fixed the same class of bug for OpenCode). Inbound requests (which may block
 * for a while awaiting a user decision in a later slice) and response-future
 * completions run on a cached-thread-pool executor ({@code codex-dispatch}) so
 * the reader thread is never blocked.
 *
 * <p>
 * Unlike ACP's {@code AcpConnection}, method names here are raw strings, not a
 * closed enum — {@code app-server} exposes on the order of a hundred RPC
 * methods (design doc §7) and this slice only needs three of them.
 */
public class CodexJsonRpcClient {

    private static final Gson GSON = new Gson();

    private final PrintWriter writer;
    private final InputStream inputStream;
    private final CodexNotificationListener notificationListener;
    private final CodexServerRequestHandler requestHandler;
    private final CodexConnectionListener connectionListener;
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ExecutorService notifyExecutor;
    private final ExecutorService dispatchExecutor;
    private final Thread readerThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public CodexJsonRpcClient(OutputStream out, InputStream in,
            CodexNotificationListener notificationListener,
            CodexServerRequestHandler requestHandler,
            CodexConnectionListener connectionListener) {
        this.writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), false);
        this.inputStream = in;
        this.notificationListener = notificationListener;
        this.requestHandler = requestHandler;
        this.connectionListener = connectionListener;
        this.notifyExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "codex-notify");
            t.setDaemon(true);
            return t;
        });
        this.dispatchExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "codex-dispatch");
            t.setDaemon(true);
            return t;
        });
        this.readerThread = new Thread(() -> readLoop(in), "codex-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    public CompletableFuture<JsonObject> sendRequest(String method, JsonObject params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("id", id);
        msg.addProperty("method", method);
        if (params != null) {
            msg.add("params", params);
        }
        try {
            writeMessage(msg);
        }
        catch (Exception e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    public void sendNotification(String method, JsonObject params) {
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("method", method);
        if (params != null) {
            msg.add("params", params);
        }
        writeMessage(msg);
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        readerThread.interrupt();
        try {
            inputStream.close();
        }
        catch (IOException ignore) {
        }
        notifyExecutor.shutdown();
        dispatchExecutor.shutdown();
        RuntimeException ex = new RuntimeException("CodexJsonRpcClient closed");
        pending.values().forEach(f -> f.completeExceptionally(ex));
        pending.clear();
        writeLock.lock();
        try {
            writer.close();
        }
        finally {
            writeLock.unlock();
        }
    }

    /**
     * {@link PrintWriter} swallows every {@link IOException} by design — a
     * write to a dead process's stdin (broken pipe) never throws, it just sets
     * an internal error flag. Without checking {@link PrintWriter#checkError()}
     * here, {@link #sendRequest} could never detect a dead connection: its
     * catch block exists but nothing ever reaches it, so the returned future
     * hangs forever and whatever flag the caller uses to track "a turn is in
     * flight" (e.g. {@code CodexAiProcessManager.processing}) never clears —
     * the exact busy-forever failure mode this project has hit before.
     */
    private void writeMessage(JsonObject message) {
        String line = GSON.toJson(message);
        writeLock.lock();
        try {
            writer.print(line);
            writer.print('\n');
            writer.flush();
            if (writer.checkError()) {
                throw new java.io.UncheckedIOException(
                        new IOException("write failed — Codex process pipe is closed"));
            }
        }
        finally {
            writeLock.unlock();
        }
    }

    private void readLoop(InputStream in) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    handleLine(trimmed);
                }
            }
        }
        catch (IOException e) {
            if (!closed.get()) {
                notifyExecutor.execute(() -> connectionListener.onDisconnected(e));
                return;
            }
        }
        if (!closed.get()) {
            notifyExecutor.execute(() -> connectionListener.onDisconnected(null));
        }
    }

    private void handleLine(String line) {
        try {
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
            boolean hasMethod = msg.has("method");
            boolean hasId = msg.has("id");

            if (hasMethod && hasId) {
                long id = msg.get("id").getAsLong();
                String method = msg.get("method").getAsString();
                JsonObject params = msg.has("params") ? msg.getAsJsonObject("params") : new JsonObject();
                dispatchExecutor.execute(() -> handleInboundRequest(id, method, params));
            }
            else if (hasMethod) {
                String method = msg.get("method").getAsString();
                JsonObject params = msg.has("params") ? msg.getAsJsonObject("params") : new JsonObject();
                notifyExecutor.execute(() -> notificationListener.onNotification(method, params));
            }
            else if (hasId) {
                long id = msg.get("id").getAsLong();
                CompletableFuture<JsonObject> future = pending.remove(id);
                if (future != null) {
                    if (msg.has("error")) {
                        JsonObject error = msg.getAsJsonObject("error");
                        int code = error.has("code") ? error.get("code").getAsInt() : 0;
                        String message = error.has("message") ? error.get("message").getAsString() : "unknown";
                        CodexJsonRpcException ex = new CodexJsonRpcException(code, message);
                        dispatchExecutor.execute(() -> future.completeExceptionally(ex));
                    }
                    else {
                        JsonObject result = msg.has("result") ? msg.getAsJsonObject("result") : new JsonObject();
                        dispatchExecutor.execute(() -> future.complete(result));
                    }
                }
            }
        }
        catch (Exception e) {
            // Malformed or unexpected message — drop silently
        }
    }

    private void handleInboundRequest(long id, String method, JsonObject params) {
        requestHandler.onServerRequest(method, params)
                .thenAccept(result -> sendSuccessResponse(id, result))
                .exceptionally(ex -> {
                    sendErrorResponse(id, CodexJsonRpcErrorCodeEnum.INTERNAL_ERROR.code(), ex.getMessage());
                    return null;
                });
    }

    private void sendSuccessResponse(long id, JsonObject result) {
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("id", id);
        msg.add("result", result != null ? result : new JsonObject());
        writeMessage(msg);
    }

    private void sendErrorResponse(long id, int code, String message) {
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        msg.add("error", error);
        writeMessage(msg);
    }
}

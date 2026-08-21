package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp;

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
 * Bidirectional nd-JSON JSON-RPC 2.0 transport for the Agent Client Protocol.
 *
 * <p>
 * A single daemon reader thread reads lines and routes them in three ways:
 * <ol>
 * <li>method + id → inbound request from agent → dispatch executor + reply
 * <li>method only → notification → notify executor (FIFO, single-thread)
 * <li>id only → response to our request → complete pending future via dispatch
 * executor
 * </ol>
 *
 * <p>
 * Notifications (session/update) and the disconnection callback are delivered
 * via a single-thread executor («acp-notify») to guarantee FIFO order. Inbound
 * requests (particularly session/request_permission, which can block up to 120
 * s waiting for user input) and response-future completions run on a
 * cached-thread-pool executor («acp-dispatch») so the reader thread is never
 * blocked.
 */
public class AcpConnection {

    private static final Gson GSON = new Gson();

    private final PrintWriter writer;
    private final InputStream inputStream;
    private final AcpClientHandler handler;
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ExecutorService notifyExecutor;
    private final ExecutorService dispatchExecutor;
    private final Thread readerThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public AcpConnection(OutputStream out, InputStream in, AcpClientHandler handler) {
        this.writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), false);
        this.inputStream = in;
        this.handler = handler;
        this.notifyExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "acp-notify");
            t.setDaemon(true);
            return t;
        });
        this.dispatchExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "acp-dispatch");
            t.setDaemon(true);
            return t;
        });
        this.readerThread = new Thread(() -> readLoop(in), "acp-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    public CompletableFuture<JsonObject> sendRequest(AcpMethodEnum method, JsonObject params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        JsonObject msg = new JsonObject();
        msg.addProperty(AcpJsonKeyEnum.JSONRPC.key(), "2.0");
        msg.addProperty(AcpJsonKeyEnum.ID.key(), id);
        msg.addProperty(AcpJsonKeyEnum.METHOD.key(), method.wireValue());
        if (params != null) {
            msg.add(AcpJsonKeyEnum.PARAMS.key(), params);
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

    public void sendNotification(AcpMethodEnum method, JsonObject params) {
        JsonObject msg = new JsonObject();
        msg.addProperty(AcpJsonKeyEnum.JSONRPC.key(), "2.0");
        msg.addProperty(AcpJsonKeyEnum.METHOD.key(), method.wireValue());
        if (params != null) {
            msg.add(AcpJsonKeyEnum.PARAMS.key(), params);
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
        RuntimeException ex = new RuntimeException("AcpConnection closed");
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

    private void writeMessage(JsonObject message) {
        String line = GSON.toJson(message);
        writeLock.lock();
        try {
            writer.print(line);
            writer.print('\n');
            writer.flush();
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
                notifyExecutor.execute(() -> handler.onDisconnected(e));
                return;
            }
        }
        if (!closed.get()) {
            notifyExecutor.execute(() -> handler.onDisconnected(null));
        }
    }

    private void handleLine(String line) {
        try {
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
            boolean hasMethod = msg.has(AcpJsonKeyEnum.METHOD.key());
            boolean hasId = msg.has(AcpJsonKeyEnum.ID.key());

            if (hasMethod && hasId) {
                long id = msg.get(AcpJsonKeyEnum.ID.key()).getAsLong();
                String method = msg.get(AcpJsonKeyEnum.METHOD.key()).getAsString();
                JsonObject params = msg.has(AcpJsonKeyEnum.PARAMS.key()) ? msg.getAsJsonObject(AcpJsonKeyEnum.PARAMS.key()) : new JsonObject();
                dispatchExecutor.execute(() -> handleInboundRequest(id, method, params));
            }
            else if (hasMethod) {
                String method = msg.get(AcpJsonKeyEnum.METHOD.key()).getAsString();
                JsonObject params = msg.has(AcpJsonKeyEnum.PARAMS.key()) ? msg.getAsJsonObject(AcpJsonKeyEnum.PARAMS.key()) : new JsonObject();
                notifyExecutor.execute(() -> handleNotification(method, params));
            }
            else if (hasId) {
                long id = msg.get(AcpJsonKeyEnum.ID.key()).getAsLong();
                CompletableFuture<JsonObject> future = pending.remove(id);
                if (future != null) {
                    if (msg.has(AcpJsonKeyEnum.ERROR.key())) {
                        JsonObject error = msg.getAsJsonObject(AcpJsonKeyEnum.ERROR.key());
                        int code = error.has(AcpJsonKeyEnum.CODE.key()) ? error.get(AcpJsonKeyEnum.CODE.key()).getAsInt() : 0;
                        String message = error.has(AcpJsonKeyEnum.MESSAGE.key()) ? error.get(AcpJsonKeyEnum.MESSAGE.key()).getAsString() : "unknown";
                        AcpException ex = new AcpException(code, message);
                        dispatchExecutor.execute(() -> future.completeExceptionally(ex));
                    }
                    else {
                        JsonObject result = msg.has(AcpJsonKeyEnum.RESULT.key()) ? msg.getAsJsonObject(AcpJsonKeyEnum.RESULT.key()) : new JsonObject();
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
        AcpMethodEnum m = AcpMethodEnum.fromWire(method);
        if (m == null) {
            sendErrorResponse(id, AcpErrorCodeEnum.METHOD_NOT_FOUND.code(), "Method not found: " + method);
            return;
        }
        CompletableFuture<JsonObject> resultFuture;
        switch (m) {
            case SESSION_REQUEST_PERMISSION:
                resultFuture = handler.onRequestPermission(params);
                break;
            case FS_WRITE_TEXT_FILE:
                resultFuture = handler.onWriteTextFile(params);
                break;
            case FS_READ_TEXT_FILE:
                resultFuture = handler.onReadTextFile(params);
                break;
            default:
                sendErrorResponse(id, AcpErrorCodeEnum.METHOD_NOT_FOUND.code(), "Unsupported inbound method: " + method);
                return;
        }
        resultFuture
                .thenAccept(result -> sendSuccessResponse(id, result))
                .exceptionally(ex -> {
                    sendErrorResponse(id, AcpErrorCodeEnum.INTERNAL_ERROR.code(), ex.getMessage());
                    return null;
                });
    }

    private void handleNotification(String method, JsonObject params) {
        if (AcpMethodEnum.SESSION_UPDATE.wireValue().equals(method)) {
            String sessionId = params.has(AcpJsonKeyEnum.SESSION_ID.key()) ? params.get(AcpJsonKeyEnum.SESSION_ID.key()).getAsString() : null;
            JsonObject update = params.has(AcpJsonKeyEnum.UPDATE.key()) ? params.getAsJsonObject(AcpJsonKeyEnum.UPDATE.key()) : new JsonObject();
            handler.onSessionUpdate(sessionId, update);
        }
    }

    private void sendSuccessResponse(long id, JsonObject result) {
        JsonObject msg = new JsonObject();
        msg.addProperty(AcpJsonKeyEnum.JSONRPC.key(), "2.0");
        msg.addProperty(AcpJsonKeyEnum.ID.key(), id);
        msg.add(AcpJsonKeyEnum.RESULT.key(), result != null ? result : new JsonObject());
        writeMessage(msg);
    }

    private void sendErrorResponse(long id, int code, String message) {
        JsonObject msg = new JsonObject();
        msg.addProperty(AcpJsonKeyEnum.JSONRPC.key(), "2.0");
        msg.addProperty(AcpJsonKeyEnum.ID.key(), id);
        JsonObject error = new JsonObject();
        error.addProperty(AcpJsonKeyEnum.CODE.key(), code);
        error.addProperty(AcpJsonKeyEnum.MESSAGE.key(), message);
        msg.add(AcpJsonKeyEnum.ERROR.key(), error);
        writeMessage(msg);
    }
}

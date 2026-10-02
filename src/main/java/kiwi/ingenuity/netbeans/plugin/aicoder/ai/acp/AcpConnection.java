package kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp;

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
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;

/**
 * Bidirectional nd-JSON JSON-RPC 2.0 transport for the Agent Client Protocol.
 *
 * <p>
 * A single daemon reader thread reads lines and routes them in three ways:
 * <ol>
 * <li>method + id → inbound request from agent → dispatch executor + reply
 * <li>method only → notification → notify executor (FIFO, single-thread)
 * <li>id only → response to our request → complete pending future via dispatch executor
 * </ol>
 *
 * <p>
 * Notifications (session/update) and the disconnection callback are delivered via a single-thread executor
 * («acp-notify») to guarantee FIFO order. Inbound requests (particularly session/request_permission, whose
 * approval response expires after 120 s) and response-future completions run on a cached-thread-pool executor
 * («acp-dispatch») so the reader thread is never blocked.
 */
public class AcpConnection {

    private static final Logger LOG = Logger.getLogger(AcpConnection.class.getName());

    private static final Gson GSON = new Gson();

    /**
     * Upper bound on each piece of agent-supplied error text (the error message and its detail) carried into
     * an {@link AcpException} message.
     */
    static final int MAX_ERROR_DETAIL_CHARS = 500;

    private final PrintWriter writer;
    private final InputStream inputStream;
    private final AcpClientHandler handler;
    private final String label;
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ExecutorService notifyExecutor;
    private final ExecutorService dispatchExecutor;
    private final Thread readerThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean streamEnded;

    public AcpConnection(OutputStream out, InputStream in, AcpClientHandler handler) {
        this(out, in, handler, "ACP");
    }

    /**
     * @param label backend name for the raw wire log ({@code "Grok"}, {@code "OpenCode"}) — purely cosmetic,
     *              never parsed, never sent on the wire. Every line in both directions is logged under this
     *              label, secrets redacted, when {@link PluginSettings#isDebugJson()} is set — the same gate
     *              every other ACP debug line in this plugin uses.
     */
    public AcpConnection(OutputStream out, InputStream in, AcpClientHandler handler, String label) {
        this.writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), false);
        this.inputStream = in;
        this.handler = handler;
        this.label = label != null ? label : "ACP";
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
            if (!writeMessage(msg)) {
                // PrintWriter swallows write errors, so without this a request to a dead agent never fails: its
                // future waits forever, and so does the turn that is waiting on it.
                throw new IOException("write failed — the OpenCode process pipe is closed");
            }
        }
        catch (Exception e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * True once {@link #close()} has run — the only thing that shuts the notify/dispatch executors down.
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * True once the reader has hit end of stream or a read error: nothing more will ever be read, so no
     * response can arrive. Set before the disconnect callback is queued.
     */
    public boolean isStreamEnded() {
        return streamEnded;
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

    /**
     * Runs {@code task} on the single-thread FIFO notification executor ({@code acp-notify}), the same
     * executor that delivers session/update notifications and the disconnection callback — so it is ordered
     * after every notification the reader already queued. The process manager uses this to sequence a
     * compaction's flag-clear and completion behind the streamed-back summary. Throws
     * {@link java.util.concurrent.RejectedExecutionException} once the connection is closed; callers run the
     * task inline then.
     */
    public void runOnNotifyThread(Runnable task) {
        notifyExecutor.execute(task);
    }

    /**
     * @return false when the write failed (the agent's stdin is closed); notifications and responses ignore
     *         it as before, only {@link #sendRequest} acts on it
     */
    private boolean writeMessage(JsonObject message) {
        String line = GSON.toJson(message);
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "{0} >> {1}", new Object[]{label, McpHookServerUtil.redactAllSecrets(line)});
        }
        writeLock.lock();
        try {
            writer.print(line);
            writer.print('\n');
            writer.flush();
            return !writer.checkError();
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
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.INFO, "{0} << {1}", new Object[]{label, McpHookServerUtil.redactAllSecrets(trimmed)});
                    }
                    handleLine(trimmed);
                }
            }
        }
        catch (IOException e) {
            streamEnded = true;
            if (!closed.get()) {
                notifyExecutor.execute(() -> handler.onDisconnected(e));
                return;
            }
        }
        streamEnded = true;
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
                        String message = error.has(AcpJsonKeyEnum.MESSAGE.key())
                                         ? redactAndBound(error.get(AcpJsonKeyEnum.MESSAGE.key()).getAsString()) : "unknown";
                        // A bare "Internal error" hides the real cause (e.g. Grok's "402 Payment Required: usage
                        // balance exhausted"), which agents put in error.data.message — show it to the user too.
                        String detail = errorDataMessage(error);
                        if (detail != null && !detail.equals(message)) {
                            message = message + " — " + detail;
                        }
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

    static String errorDataMessage(JsonObject error) {
        if (!error.has(AcpJsonKeyEnum.DATA.key()) || !error.get(AcpJsonKeyEnum.DATA.key()).isJsonObject()) {
            return null;
        }
        JsonObject data = error.getAsJsonObject(AcpJsonKeyEnum.DATA.key());
        if (!data.has(AcpJsonKeyEnum.MESSAGE.key()) || !data.get(AcpJsonKeyEnum.MESSAGE.key()).isJsonPrimitive()) {
            return null;
        }
        String detail = data.get(AcpJsonKeyEnum.MESSAGE.key()).getAsString();
        return detail.isBlank() ? null : redactAndBound(detail);
    }

    /**
     * Agent-supplied error text reaches the UI and the log, so the plugin's own session secrets are masked
     * and its size is bounded — an upstream error body can be arbitrarily large.
     */
    static String redactAndBound(String text) {
        String redacted = McpHookServerUtil.redactAllSecrets(text);
        return redacted.length() > MAX_ERROR_DETAIL_CHARS
               ? redacted.substring(0, MAX_ERROR_DETAIL_CHARS) + "…"
               : redacted;
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

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiJsonKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiTimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.JsonUtils;

/**
 * Owns ONE long-lived {@code pi --mode rpc} process for the whole plugin session. stdin is held open for the session's
 * lifetime; each RPC command is written as a JSONL frame carrying a caller-generated {@code id}
 * ({@code {type:<command name>, id, ...params}} — the command name IS the frame's {@code type}; there is no generic
 * {@code "command"} command), and the corresponding {@code {type:"response", command, success, data?, error?}} frame
 * echoes that id and completes the matching future ({@code command} names the request that produced it — pi sends this
 * field only in the response, never the request; see {@link #frameRequest}). Event frames (no id) are forwarded to
 * {@code eventLine} for {@code PiStreamJsonParser}. Response frames are forwarded too, so the parser can act on command
 * failures; successful {@code get_state}/{@code get_available_models}/{@code get_session_stats} results are read
 * directly by {@code PiAiProcessManager} off this same id-correlated future instead.
 *
 * <p>
 * Framing is strict JSONL: frames are delimited by LF only, a trailing CR is stripped, and U+2028/U+2029 inside JSON
 * strings are never treated as delimiters. A malformed frame is skipped (never forwarded, never completing a future)
 * and logged only when {@link PluginSettings#isDebugJson()} is set, so a torn line cannot kill the reader thread.
 */
public final class PiPersistentSession {

    private static final Logger LOG = Logger.getLogger(PiPersistentSession.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static PiPersistentSession launch(List<String> command, File workDir,
                                             Consumer<String> eventLine, Consumer<String> stderrConsumer) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null && workDir.isDirectory()) {
            pb.directory(workDir);
        }
        pb.redirectErrorStream(false);
        Process p = pb.start();
        PiPersistentSession s = new PiPersistentSession(p, eventLine, stderrConsumer);
        s.readerThread.start();
        s.stderrThread.start();
        return s;
    }

    /**
     * Frames an RPC command for the wire. pi has no generic {@code "command"} command — the command NAME is the frame's
     * own {@code type} (verified live 2026-09-18 against a real pi process: requests are
     * {@code {"id":"1","type":"get_state"}} / {@code {"id":"p1","type":"prompt","message":"…"}}; {@code "command"}
     * appears only in pi's own {@code response} frames, never in a request). The caller-built command object (produced
     * by {@code PiAiProcessManager.command(PiRpcCommandEnum)}) still carries the command name under
     * {@link PiJsonKeyEnum#COMMAND} as an internal-only marker — this method reads it off to become {@code type} and
     * strips it before copying the rest, so it is never itself written to the wire; every other field is preserved as a
     * top-level frame parameter alongside the correlation {@code id}, newline-terminated.
     */
    static String frameRequest(JsonObject command, String id) {
        JsonObject frame = new JsonObject();
        String type = JsonUtils.getString(command, PiJsonKeyEnum.COMMAND.key());
        frame.addProperty(PiJsonKeyEnum.TYPE.key(), type);
        frame.addProperty(PiJsonKeyEnum.ID.key(), id);
        for (Map.Entry<String, JsonElement> entry : command.entrySet()) {
            if (PiJsonKeyEnum.COMMAND.key().equals(entry.getKey())) {
                continue;
            }
            frame.add(entry.getKey(), entry.getValue());
        }
        return GSON.toJson(frame) + "\n";
    }

    /**
     * Reads one stdout JSONL stream: splits on LF, strips one trailing CR per frame, skips blank and malformed frames,
     * completes the future whose id the frame carries, and forwards every well-formed frame to {@code eventLine}. Runs
     * until EOF (process exit or closed stream).
     */
    static void pump(InputStream in, Map<String, CompletableFuture<JsonObject>> pending, Consumer<String> eventLine) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder frame = new StringBuilder();
            int c;
            while ((c = r.read()) != -1) {
                char ch = (char) c;
                if (ch == '\n') {
                    dispatchFrame(frame, pending, eventLine);
                    frame.setLength(0);
                }
                else {
                    frame.append(ch);
                }
            }
            if (frame.length() > 0) {
                dispatchFrame(frame, pending, eventLine);
            }
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Pi stream closed", e);
        }
        // The stream ended (EOF after process exit, or an I/O failure) and the process is no longer talking to us:
        // fail every pending future so waiters don't hang until the caller's own timeout.
        failAllPending(pending, "Pi stream ended");
    }

    /**
     * Fails every unfinished future and empties the map. Shared by {@code close()} (which ends the session on purpose)
     * and {@code pump()} (which detects the stream ending underneath it).
     */
    private static void failAllPending(Map<String, CompletableFuture<JsonObject>> pending, String message) {
        IOException ex = new IOException(message);
        for (CompletableFuture<JsonObject> future : pending.values()) {
            future.completeExceptionally(ex);
        }
        pending.clear();
    }

    private static void dispatchFrame(StringBuilder buf, Map<String, CompletableFuture<JsonObject>> pending,
                                      Consumer<String> eventLine) {
        if (buf.length() > 0 && buf.charAt(buf.length() - 1) == '\r') {
            buf.deleteCharAt(buf.length() - 1);
        }
        if (buf.isEmpty()) {
            return;
        }
        String line = buf.toString();
        try {
            JsonObject obj = GSON.fromJson(line, JsonObject.class);
            String id = obj == null ? null : JsonUtils.getString(obj, PiJsonKeyEnum.ID.key());
            if (id != null && !id.isBlank()) {
                CompletableFuture<JsonObject> future = pending.remove(id);
                if (future != null) {
                    future.complete(obj);
                }
            }
        }
        catch (RuntimeException e) {
            // A malformed frame (not even</think> json) must not kill the reader thread; it is skipped and
            // never forwarded. Logged only under the debug gate, like the parser's own malformed-line path.
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.FINE, "Skipping malformed pi frame: {0}", line);
            }
            return;
        }
        try {
            eventLine.accept(line);
        }
        catch (RuntimeException e) {
            // A throwing downstream listener must not kill the reader thread: the process stays alive, so a dead
            // reader would silently wedge the turn with no output.
            LOG.log(Level.WARNING, "Pi stream listener threw; continuing", e);
        }
    }

    /**
     * Reads one stderr stream and delivers each line to {@code consumer}. Line splitting here is line-based (not the
     * JSONL framing above) because stderr is free-form diagnostic output.
     */
    static void pumpStderr(InputStream in, Consumer<String> consumer) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    consumer.accept(line);
                }
                catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Pi stderr listener threw; continuing", e);
                }
            }
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Pi stderr closed", e);
        }
    }

    private final Process process;
    private final OutputStream stdin;
    private final Thread readerThread;
    private final Thread stderrThread;
    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    private PiPersistentSession(Process process, Consumer<String> eventLine, Consumer<String> stderrConsumer) {
        this.process = process;
        this.stdin = process.getOutputStream();
        this.readerThread = new Thread(() -> pump(process.getInputStream(), pending, eventLine), "pi-reader");
        this.stderrThread = new Thread(() -> pumpStderr(process.getErrorStream(), stderrConsumer), "pi-stderr");
        this.readerThread.setDaemon(true);
        this.stderrThread.setDaemon(true);
    }

    /**
     * Sends a command frame and returns a future completed with the {@code {type:"response", ...}} frame that echoes
     * the generated correlation id. The future completes exceptionally if the session is already closed or the write
     * fails; a response that never arrives leaves the future pending (its timeout is the caller's decision).
     */
    public CompletableFuture<JsonObject> send(JsonObject command) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        synchronized (this) {
            if (closed) {
                future.completeExceptionally(new IOException("Pi session is closed"));
                return future;
            }
            pending.put(id, future);
            try {
                stdin.write(frameRequest(command, id).getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            catch (IOException e) {
                pending.remove(id);
                future.completeExceptionally(e);
            }
        }
        return future;
    }

    /**
     * Writes a pre-framed line verbatim (newline-terminated) — used for {@code extension_ui_response} replies, which
     * are event frames with no correlation id.
     */
    public synchronized boolean sendRawLine(String jsonLine) {
        if (closed) {
            return false;
        }
        try {
            stdin.write((jsonLine + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            return true;
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Pi control write failed", e);
            return false;
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public Process process() {
        return process;
    }

    /**
     * Closes stdin (which makes an idle {@code pi --mode rpc} exit with code 0 and no shutdown event), fails every
     * pending future, then waits the close grace period before destroying a still-alive process.
     */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        failAllPending(pending, "Pi session closed");
        try {
            stdin.close();
        }
        catch (IOException ignored) {
        }
        Thread reaper = new Thread(() -> {
            try {
                if (!process.waitFor(PiTimeoutEnum.CLOSE_GRACE_MILLIS.millis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }, "pi-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

}

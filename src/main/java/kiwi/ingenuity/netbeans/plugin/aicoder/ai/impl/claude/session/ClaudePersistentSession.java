package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeJsonKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeTimeoutEnum;

/**
 * Owns ONE long-lived {@code claude --input-format stream-json} process for the whole plugin session. stdin is held
 * open for the session's lifetime; each user turn is written as a stream-json {@code user} line. A single reader thread
 * feeds every stdout line to {@code lineConsumer}.
 */
public final class ClaudePersistentSession {

    private static final Logger LOG = Logger.getLogger(ClaudePersistentSession.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static ClaudePersistentSession launch(List<String> command, File workDir,
            Consumer<String> lineConsumer, Consumer<String> stderrConsumer) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        configureMcpToolTimeout(pb);
        if (workDir != null && workDir.isDirectory()) {
            pb.directory(workDir);
        }
        pb.redirectErrorStream(false);
        Process p = pb.start();
        ClaudePersistentSession s = new ClaudePersistentSession(p, lineConsumer, stderrConsumer);
        s.readerThread.start();
        s.stderrThread.start();
        return s;
    }

    /**
     * Sets a default for Claude's HTTP MCP per-request timeout without overriding a value explicitly supplied in the
     * parent environment.
     */
    static void configureMcpToolTimeout(ProcessBuilder processBuilder) {
        String configured = processBuilder.environment().putIfAbsent("MCP_TOOL_TIMEOUT",
                Long.toString(ClaudeTimeoutEnum.MCP_TOOL_TIMEOUT_MILLIS.millis()));
        if (configured == null) {
            return;
        }
        try {
            if (Long.parseLong(configured) < 60_000L) {
                LOG.log(Level.INFO, "MCP_TOOL_TIMEOUT={0} ms is below 60000 ms; "
                        + "Claude will retain its 60-second HTTP MCP per-request limit", configured);
            }
        }
        catch (NumberFormatException e) {
            LOG.log(Level.INFO, "MCP_TOOL_TIMEOUT is not a valid millisecond value; "
                    + "preserving the user-supplied value", e);
        }
    }

    public static String frameUserMessage(String text) {
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty(ClaudeJsonKeyEnum.TYPE.key(), "user");
        JsonObject message = new JsonObject();
        message.addProperty(ClaudeJsonKeyEnum.ROLE.key(), "user");
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty(ClaudeJsonKeyEnum.TYPE.key(), "text");
        block.addProperty(ClaudeJsonKeyEnum.TEXT.key(), text);
        content.add(block);
        message.add(ClaudeJsonKeyEnum.CONTENT.key(), content);
        userMessage.add(ClaudeJsonKeyEnum.MESSAGE.key(), message);
        return GSON.toJson(userMessage) + "\n";
    }

    static void pump(InputStream in, Consumer<String> consumer) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    consumer.accept(line);
                }
                catch (RuntimeException e) {
                    // A throwing downstream listener must not kill the reader thread: the process
                    // stays alive, so a dead reader would silently wedge the turn with no output.
                    LOG.log(Level.WARNING, "Claude stream listener threw; continuing", e);
                }
            }
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Claude stream closed", e);
        }
    }

    private final Process process;
    private final OutputStream stdin;
    private final Thread readerThread;
    private final Thread stderrThread;
    private volatile boolean closed = false;

    private ClaudePersistentSession(Process process, Consumer<String> lineConsumer, Consumer<String> stderrConsumer) {
        this.process = process;
        this.stdin = process.getOutputStream();
        this.readerThread = new Thread(() -> pump(process.getInputStream(), lineConsumer), "claude-reader");
        this.stderrThread = new Thread(() -> pump(process.getErrorStream(), stderrConsumer), "claude-stderr");
        this.readerThread.setDaemon(true);
        this.stderrThread.setDaemon(true);
    }

    public synchronized boolean sendUserTurn(String text) {
        if (closed) {
            return false;
        }
        try {
            stdin.write(frameUserMessage(text).getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            return true;
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Claude stdin write failed", e);
            return false;
        }
    }

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
            LOG.log(Level.FINE, "Claude control write failed", e);
            return false;
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public Process process() {
        return process;
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            stdin.close();
        }
        catch (IOException ignored) {
        }
        Thread reaper = new Thread(() -> {
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }, "claude-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.session.ClaudeAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Manages an AI CLI process using stream-json style output. Public API uses
 * AiEventListener for abstraction. All Claude-binary specific logic (launch,
 * mcp registration, stream-json parsing) stays internal. Used by ClaudeBackend.
 */
public class ClaudeAiProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(ClaudeAiProcessManager.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private boolean firstMessage = true;
    private long cachedContextWindow = 0;
    // Kept open across the turn so cancel() can write a graceful interrupt to stdin.
    // Guarded by stdinLock (a separate lock from `this`) because cancel() holds `this`
    // while reading it and the process thread must write it without deadlocking on `this`.
    private volatile OutputStream currentStdin = null;
    private final Object stdinLock = new Object();
    private ClaudeAiMcpRegistrar registrar = null;
    private ClaudeAiSession claudeAiSession = null;

    public ClaudeAiProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    @Override
    public synchronized void start(String executablePath, String model) {
        stop();
        this.executablePath = executablePath;
        this.model = model;

        if (!ClaudeExecutableLocator.isExecutableFile(executablePath)) {
            running = false;
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatStartFailed("executable not found at " + executablePath)));
            return;
        }

        if (currentSession == null) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatSessionNotConfigured()));
            return;
        }
        sessionId = currentSession.id();
        firstMessage = true;

        ClaudeAiMcpRegistrar reg = new ClaudeAiMcpRegistrar(sessionId, executablePath);
        if (!McpServerRegistry.register(reg)) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatMcpSetupFailed()));
            return;
        }
        registrar = reg;
        claudeAiSession = new ClaudeAiSession(currentSession, listener);

        running = true;
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY, StatusMessageUtil.formatReady("Claude")));
    }

    @Override
    public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        if (pendingDiff || !running || processing) {
            return;
        }
        // cancel() nulls currentProcess before the background thread's cleanup block can reset
        // cancelledByUser, so it can persist across turns. Clear it here — a new turn always
        // starts clean regardless of how the previous one ended.
        cancelledByUser = false;
        processing = true;

        // pinning logic same as original
        if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
            sessionWorkingDir = workingDir;
        }
        File effectiveWorkDir = sessionWorkingDir != null ? sessionWorkingDir : workingDir;
        String sid = sessionId;
        boolean isFirst = firstMessage;
        firstMessage = false;
        ClaudeAiSession capturedAiSession = claudeAiSession;

        Thread t = new Thread(() -> {
            Path capturedConfigDir;
            synchronized (ClaudeAiProcessManager.this) {
                capturedConfigDir = sessionConfigDir;
            }

            List<String> args = new ArrayList<>();
            args.add("-p");
            args.add("--output-format");
            args.add("stream-json");
            args.add("--input-format");
            args.add("stream-json");
            args.add("--verbose");
            args.add("--include-partial-messages");
            if (isFirst) {
                args.add("--session-id");
                args.add(sid);
            }
            else {
                args.add("--resume");
                args.add(sid);
            }
            args.add("--model");
            args.add(model);
            args.add("--allowedTools");
            args.add("Read,Edit,Write,Bash,Glob,Grep," + McpToolEnum.allMcpNames());
            if (capturedConfigDir != null) {
                Path memoryDir = capturedConfigDir.resolve("memory");
                try {
                    Files.createDirectories(memoryDir);
                    JsonObject settings = new JsonObject();
                    settings.addProperty("autoMemoryDirectory", memoryDir.toString());
                    args.add("--settings");
                    args.add(GSON.toJson(settings));
                }
                catch (IOException e) {
                    LOG.log(Level.WARNING, "Could not create memory directory: {0}", e.getMessage());
                }
            }

            List<String> stderrLines = new CopyOnWriteArrayList<>();
            Process p = null;

            try {
                List<String> cmd = ClaudeExecutableLocator.buildHostCommand(executablePath, args.toArray(String[]::new));
                ProcessBuilder pb = new ProcessBuilder(cmd);
                if (effectiveWorkDir != null && effectiveWorkDir.isDirectory()) {
                    pb.directory(effectiveWorkDir);
                }
                pb.redirectErrorStream(false);
                p = pb.start();
                synchronized (ClaudeAiProcessManager.this) {
                    if (!running) {
                        // stop()/cancel() arrived while process was starting — kill it immediately
                        p.destroyForcibly();
                        return;
                    }
                    currentProcess = p;
                }

                // --input-format stream-json: the prompt must be a one-line JSON user
                // message. Stdin is deliberately left OPEN afterwards so cancel() can write
                // a graceful interrupt control_request to it; the read loop closes it once the
                // turn's `result` event arrives (Claude waits for stdin EOF to exit).
                OutputStream stdin = p.getOutputStream();
                synchronized (stdinLock) {
                    currentStdin = stdin;
                }
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.WARNING, "ai prompt [{0}]: {1}", new Object[]{sid, text});
                }
                JsonObject userMessage = new JsonObject();
                userMessage.addProperty(ClaudeJsonKeyEnum.TYPE.key(), "user");
                JsonObject message = new JsonObject();
                message.addProperty(ClaudeJsonKeyEnum.ROLE.key(), "user");
                JsonArray contentArr = new JsonArray();
                JsonObject textBlock = new JsonObject();
                textBlock.addProperty(ClaudeJsonKeyEnum.TYPE.key(), "text");
                textBlock.addProperty(ClaudeJsonKeyEnum.TEXT.key(), text);
                contentArr.add(textBlock);
                message.add(ClaudeJsonKeyEnum.CONTENT.key(), contentArr);
                userMessage.add(ClaudeJsonKeyEnum.MESSAGE.key(), message);
                stdin.write((GSON.toJson(userMessage) + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();

                boolean debugJson = PluginSettings.isDebugJson();

                final Process proc = p;
                Thread stderrThread = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (debugJson) {
                                LOG.log(Level.INFO, "claude stderr [{0}]: {1}", new Object[]{sid, line});
                            }
                            else {
                                LOG.log(Level.FINE, "claude stderr: [{0}]", line);
                            }
                            stderrLines.add(line);
                        }
                    }
                    catch (IOException e) {
                        LOG.log(Level.FINE, "stderr closed", e);
                    }
                }, "ai-stderr");
                stderrThread.setDaemon(true);
                stderrThread.start();

                // Clear processing before firing TurnCompleteEvent so refreshInputEnabled() on
                // the EDT sees isProcessing()==false immediately (the thread may still be draining
                // stderr and waiting on p.waitFor() for up to ~2 seconds after this point).
                // When cancelledByUser is true, suppress all events to prevent stale output from
                // appearing in the conversation panel after the user clicked Stop.
                // turnComplete signals the read loop to stop: with stdin held open the process
                // does not exit on its own after the `result` event — it waits for stdin EOF —
                // so the loop must break on TurnCompleteEvent and then close stdin.
                final boolean[] turnComplete = {false};
                AiProcessEventListener parserListener = event -> {
                    boolean isTurnComplete = event instanceof TurnCompleteEvent;
                    synchronized (ClaudeAiProcessManager.this) {
                        if (isTurnComplete) {
                            processing = false;
                            turnComplete[0] = true;
                        }
                        if (cancelledByUser) {
                            return;
                        }
                    }
                    listener.onAiProcessEvent(event);
                };

                ClaudeStreamJsonParser parser = new ClaudeStreamJsonParser(parserListener);
                synchronized (ClaudeAiProcessManager.this) {
                    parser.initCachedContextWindow(cachedContextWindow);
                }
                if (capturedAiSession != null) {
                    parser.setOnFirstSessionId(capturedAiSession::registerClaudeSessionAlias);
                    String parserId = capturedAiSession.getId();
                    parser.setFileAllowed(path -> {
                        var server = McpServerRegistry.getServer();
                        return server == null || parserId == null || server.isFileAllowed(parserId, path);
                    });
                }

                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (debugJson) {
                            LOG.log(Level.WARNING, "ai json [{0}]: {1}", new Object[]{sid, line});
                        }
                        else {
                            LOG.log(Level.FINE, "ai stdout: {0}", line);
                        }
                        parser.parseLine(line);
                        synchronized (ClaudeAiProcessManager.this) {
                            if (turnComplete[0]) {
                                break;
                            }
                        }
                    }
                }

                // Turn finished (or stream ended): close stdin so the process receives EOF
                // and exits. On the cancel path cancel() already closed it (currentStdin null).
                synchronized (stdinLock) {
                    if (currentStdin != null) {
                        try {
                            currentStdin.close();
                        }
                        catch (IOException ignored) {
                        }
                        currentStdin = null;
                    }
                }

                long cw = parser.getCachedContextWindow();
                if (cw > 0) {
                    synchronized (ClaudeAiProcessManager.this) {
                        cachedContextWindow = cw;
                    }
                }

                stderrThread.join(2000);
                boolean exited = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                if (!exited) {
                    p.destroyForcibly();
                }
                int code = exited ? p.exitValue() : -1;

                boolean shouldReport;
                ClaudeAiMcpRegistrar deadReg = null;
                ClaudeAiSession deadSession = null;
                synchronized (ClaudeAiProcessManager.this) {
                    shouldReport = (currentProcess == p) && !cancelledByUser;
                    if (currentProcess == p) {
                        processing = false;
                        currentProcess = null;
                        cancelledByUser = false;
                        if (code != 0) {
                            running = false;
                            deadReg = registrar;
                            registrar = null;
                            deadSession = claudeAiSession;
                            claudeAiSession = null;
                        }
                    }
                }
                if (deadReg != null) {
                    McpServerRegistry.deregister(deadReg);
                }
                if (deadSession != null) {
                    deadSession.dispose();
                }
                if (shouldReport && code != 0) {
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.EXITED, "AI exited (code " + code + ")"));
                }

            }
            catch (Exception e) {
                OutputStream stdinRef;
                synchronized (stdinLock) {
                    stdinRef = currentStdin;
                    currentStdin = null;
                }
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                boolean wasUserCancel;
                ClaudeAiMcpRegistrar failedReg = null;
                ClaudeAiSession failedSession = null;
                synchronized (ClaudeAiProcessManager.this) {
                    wasUserCancel = cancelledByUser;
                    cancelledByUser = false;
                    processing = false;
                    currentProcess = null;
                    running = false;
                    if (!wasUserCancel) {
                        failedReg = registrar;
                        registrar = null;
                        failedSession = claudeAiSession;
                        claudeAiSession = null;
                    }
                }
                // Don't orphan the child process / leak its stdin on the error path.
                if (p != null) {
                    p.destroyForcibly();
                }
                if (stdinRef != null) {
                    try {
                        stdinRef.close();
                    }
                    catch (Exception ignored) {
                    }
                }
                if (failedReg != null) {
                    McpServerRegistry.deregister(failedReg);
                }
                if (failedSession != null) {
                    failedSession.dispose();
                }
                if (!wasUserCancel) {
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, StatusMessageUtil.formatSendFailed(e.getMessage())));
                }
            }
        }, "ai-prompt");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void cancel() {
        OutputStream stdin;
        Process proc;
        synchronized (this) {
            if (!processing) {
                return;
            }
            cancelledByUser = true;
            processing = false;
            synchronized (stdinLock) {
                stdin = currentStdin;
                currentStdin = null;
            }
            proc = currentProcess;
            currentProcess = null;
        }
        boolean interruptSent = false;
        if (stdin != null) {
            try {
                // Graceful interrupt: Claude (with --input-format stream-json) aborts the
                // in-flight turn cleanly. Closing stdin then sends EOF so it exits.
                JsonObject interrupt = new JsonObject();
                interrupt.addProperty(ClaudeJsonKeyEnum.TYPE.key(), "control_request");
                interrupt.addProperty(ClaudeJsonKeyEnum.REQUEST_ID.key(), "req_cancel");
                JsonObject request = new JsonObject();
                request.addProperty(ClaudeJsonKeyEnum.SUBTYPE.key(), "interrupt");
                interrupt.add(ClaudeJsonKeyEnum.REQUEST.key(), request);
                stdin.write((GSON.toJson(interrupt) + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                stdin.close();
                interruptSent = true;
            }
            catch (IOException e) {
                LOG.log(Level.FINE, "Interrupt write failed, destroying process", e);
            }
        }
        if (interruptSent && proc != null) {
            // Don't block the EDT: give Claude up to 3s to exit after the interrupt,
            // then force-kill from a daemon thread as a fallback.
            Process toKill = proc;
            Thread killer = new Thread(() -> {
                try {
                    if (!toKill.waitFor(3, TimeUnit.SECONDS)) {
                        toKill.destroyForcibly();
                    }
                }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }, "ai-cancel-fallback");
            killer.setDaemon(true);
            killer.start();
        }
        else if (proc != null) {
            proc.destroyForcibly();
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, StatusMessageUtil.formatStopped()));
    }

    /**
     * Graceful interrupt without suppressing partial output: unlike cancel()
     * this does not set cancelledByUser, so partial results are still emitted,
     * and it does not force-kill the process. The turn aborts cleanly and the
     * read loop closes stdin on the next result event.
     */
    @Override
    public void interrupt(InterruptTypeEnum type) {
        synchronized (stdinLock) {
            if (currentStdin == null) {
                return;
            }
            try {
                JsonObject interrupt = new JsonObject();
                interrupt.addProperty(ClaudeJsonKeyEnum.TYPE.key(), "control_request");
                interrupt.addProperty(ClaudeJsonKeyEnum.REQUEST_ID.key(), "req_interrupt");
                JsonObject request = new JsonObject();
                request.addProperty(ClaudeJsonKeyEnum.SUBTYPE.key(), "interrupt");
                interrupt.add(ClaudeJsonKeyEnum.REQUEST.key(), request);
                currentStdin.write((GSON.toJson(interrupt) + "\n").getBytes(StandardCharsets.UTF_8));
                currentStdin.flush();
            }
            catch (IOException ignored) {
            }
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        processing = false;
        cancelledByUser = true;
        if (currentProcess != null) {
            currentProcess.destroyForcibly();
            currentProcess = null;
        }

        ClaudeAiSession sessionToDispose = claudeAiSession;
        claudeAiSession = null;
        if (sessionToDispose != null) {
            sessionToDispose.dispose();
        }

        ClaudeAiMcpRegistrar reg = registrar;
        registrar = null;
        if (reg != null) {
            McpServerRegistry.deregister(reg);
        }

        sessionId = null;
        sessionWorkingDir = null;
        firstMessage = true;
        pendingDiff = false;
        cachedContextWindow = 0;
        sessionConfigDir = null;
    }

    @Override
    public synchronized void resumeSession(String existingSessionId) {
        if (existingSessionId == null || existingSessionId.isBlank()) {
            return;
        }
        sessionId = existingSessionId;
        sessionWorkingDir = null;
        firstMessage = false;
    }

    @Override
    public synchronized boolean isMcpActive() {
        return registrar != null;
    }

}

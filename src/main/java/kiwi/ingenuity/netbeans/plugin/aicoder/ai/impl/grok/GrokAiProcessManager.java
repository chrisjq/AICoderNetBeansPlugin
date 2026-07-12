package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events.GrokTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.session.GrokAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Manages the {@code grok} CLI (xAI's Grok CLI, https://docs.x.ai/build/cli).
 * Unlike Claude's persistent {@code --input-format stream-json} conversation,
 * grok's headless mode (https://docs.x.ai/build/cli/headless-scripting) is a
 * one-shot process per turn: each prompt spawns {@code grok -p "<prompt>"} with
 * either {@code -s <sessionId>} (first turn, creates the named headless
 * session) or {@code -r <sessionId>} (subsequent turns, resumes it) so the
 * grok-side session persists across turns even though the OS process does not.
 * Output is captured with {@code --output-format json} and parsed by
 * {@link GrokResponseParser} once the process exits.
 */
public class GrokAiProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(GrokAiProcessManager.class.getName());

    private volatile boolean firstMessage = true;
    private volatile GrokAiMcpRegistrar registrar = null;
    private GrokAiSession grokAiSession = null;

    public GrokAiProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    /**
     * Ask the process to exit gracefully (SIGTERM-equivalent), give it up to 5
     * seconds to do so, and only escalate to a forced kill if it is still alive
     * afterwards.
     */
    private static void terminateProcess(Process p) {
        if (p == null) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    @Override
    public synchronized void start(String executablePath, String model) {
        stop();
        this.executablePath = executablePath;
        this.model = model;

        if (!GrokExecutableLocator.isExecutableFile(executablePath)) {
            running = false;
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatStartFailed("grok executable not found at " + executablePath)));
            return;
        }
        if (currentSession == null) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatSessionNotConfigured()));
            return;
        }
        sessionId = currentSession.id();
        firstMessage = true;

        if (registrar != null) {
            McpServerRegistry.deregister(registrar);
            registrar = null;
        }

        GrokAiMcpRegistrar reg = new GrokAiMcpRegistrar(sessionId, executablePath);
        boolean mcpReady;
        try {
            mcpReady = McpServerRegistry.register(reg).get(2, TimeUnit.MINUTES);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mcpReady = false;
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "MCP registration failed for session " + sessionId, e);
            mcpReady = false;
        }
        if (!mcpReady) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatMcpSetupFailed()));
            return;
        }
        registrar = reg;
        grokAiSession = new GrokAiSession(currentSession, listener);

        running = true;
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY, StatusMessageUtil.formatReady("Grok")));
    }

    @Override
    public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        if (pendingDiff || !running || processing) {
            return;
        }
        cancelledByUser = false;
        processing = true;

        if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
            sessionWorkingDir = workingDir;
        }
        File effectiveWorkDir = sessionWorkingDir != null ? sessionWorkingDir : workingDir;
        String sid = sessionId;
        // Do NOT clear firstMessage here. If the first turn fails before grok creates
        // the session on disk, the next prompt must still use -s (create). Eagerly
        // flipping to -r causes "session not found" on retry. firstMessage is cleared
        // in runTurn only after a successful create, or when the session exists on disk
        // after a failed/cancelled first attempt.
        boolean isFirst = firstMessage;
        List<File> projDirs = projectDirs != null ? projectDirs : List.of();

        Thread t = new Thread(() -> runTurn(text, effectiveWorkDir, sid, isFirst, projDirs), "grok-turn");
        t.setDaemon(true);
        t.start();
    }

    private void runTurn(String text, File workDir, String sid, boolean isFirst, List<File> projDirs) {
        List<String> args = new ArrayList<>();
        args.add("-p");
        args.add(text);
        args.add(isFirst ? "-s" : "-r");
        args.add(sid);
        args.add("--model");
        args.add(model);
        args.add("--output-format");
        args.add("json");
        args.add("--always-approve");
        args.add("--no-alt-screen");
        args.add("--no-auto-update");
        // Disable grok's cross-session memory feature (https://docs.x.ai/build/cli/reference)
        // so each plugin session starts fresh instead of pulling in context
        // recalled from the user's other grok sessions/projects.
        args.add("--no-memory");
        // Multi-project access (Claude's --add-dir equivalent). Grok has no --add-dir;
        // Claude settings additionalDirectories is parsed but "not supported" (CLI 0.2.93).
        // Documented multi-path mechanism: explicit --cwd + path-scoped --allow rules
        // (Read/Edit/Write/Grep globs) for every open NetBeans project directory.
        appendProjectDirArgs(args, workDir, projDirs);

        Process p = null;
        StringBuilder stdout = new StringBuilder();
        List<String> stderrLines = new CopyOnWriteArrayList<>();
        boolean debugJson = PluginSettings.isDebugJson();

        try {
            List<String> cmd = GrokExecutableLocator.buildHostCommand(executablePath, args.toArray(String[]::new));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workDir != null && workDir.isDirectory()) {
                pb.directory(workDir);
            }
            pb.redirectErrorStream(false);
            p = pb.start();
            synchronized (this) {
                if (!running) {
                    // stop() arrived while the process was starting — kill it and leave
                    // firstMessage alone (stop() already reset it when appropriate).
                    terminateProcess(p);
                    processing = false;
                    return;
                }
                currentProcess = p;
            }

            if (debugJson) {
                LOG.log(Level.WARNING, "grok prompt [{0}]: {1}", new Object[]{sid, text});
            }

            final Process proc = p;
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (debugJson) {
                            LOG.log(Level.INFO, "grok stderr [{0}]: {1}", new Object[]{sid, line});
                        }
                        stderrLines.add(line);
                    }
                }
                catch (IOException e) {
                    LOG.log(Level.FINE, "grok stderr closed", e);
                }
            }, "grok-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (debugJson) {
                        LOG.log(Level.WARNING, "grok json [{0}]: {1}", new Object[]{sid, line});
                    }
                    stdout.append(line).append('\n');
                }
            }
            stderrThread.join(2000);

            boolean exited = p.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                terminateProcess(p);
            }
            int code = exited ? p.exitValue() : -1;

            boolean shouldReport;
            synchronized (this) {
                shouldReport = (currentProcess == p) && !cancelledByUser;
                if (currentProcess == p) {
                    currentProcess = null;
                }
            }

            if (shouldReport) {
                if (code == 0) {
                    // First turn succeeded: session is now on disk; subsequent turns use -r.
                    markFirstMessageDone();
                    AiProcessEventListener parserListener = event -> {
                        boolean isTurnComplete = event instanceof TurnCompleteEvent;
                        synchronized (GrokAiProcessManager.this) {
                            if (isTurnComplete) {
                                processing = false;
                            }
                            if (cancelledByUser) {
                                return;
                            }
                        }
                        listener.onAiProcessEvent(event);
                    };
                    GrokTokenUsageEvent usage = GrokUsageSignalsReader.read(workDir, sid, model);
                    if (usage != null) {
                        parserListener.onAiProcessEvent(usage);
                    }
                    new GrokResponseParser(parserListener).parse(stdout.toString());
                }
                else {
                    // Failed first create: only switch to -r if the CLI left a session on disk.
                    resolveFirstMessageAfterAttempt(isFirst, sid);
                    synchronized (this) {
                        processing = false;
                    }
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.EXITED,
                            StatusMessageUtil.formatExited("Grok", code, stderrLines)));
                }
            }
            else {
                // Cancelled (or process superseded): same create-vs-resume resolution.
                resolveFirstMessageAfterAttempt(isFirst, sid);
                synchronized (this) {
                    processing = false;
                }
            }
        }
        catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            boolean wasUserCancel;
            synchronized (this) {
                wasUserCancel = cancelledByUser;
                cancelledByUser = false;
                processing = false;
                currentProcess = null;
            }
            resolveFirstMessageAfterAttempt(isFirst, sid);
            if (p != null) {
                terminateProcess(p);
            }
            if (!wasUserCancel) {
                LOG.log(Level.WARNING, "Grok turn failed", e);
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        StatusMessageUtil.formatSendFailed(e.getMessage())));
            }
        }
    }

    /**
     * Grant the headless CLI access to every open NetBeans project, mirroring
     * Claude's {@code --add-dir} loop.
     *
     * <p>Grok exposes no {@code --add-dir}. Instead:
     * <ul>
     *   <li>{@code --cwd} pins the session working directory (also set on
     *       {@link ProcessBuilder#directory(File)} for child processes)</li>
     *   <li>path-scoped {@code --allow} rules grant native Read/Edit/Write/Grep
     *       under each project root (repeatable; works with headless mode)</li>
     * </ul>
     * MCP tools already receive the same list via
     * {@code McpHookServer.updateSessionScope}; these flags cover the CLI's own
     * filesystem tools.
     */
    static void appendProjectDirArgs(List<String> args, File workDir, List<File> projDirs) {
        if (workDir != null && workDir.isDirectory()) {
            args.add("--cwd");
            args.add(canonicalPath(workDir));
        }
        if (projDirs == null || projDirs.isEmpty()) {
            return;
        }
        for (File d : projDirs) {
            if (d == null || !d.isDirectory()) {
                continue;
            }
            String root = canonicalPath(d);
            // Stable absolute globs: strip trailing separator so "/foo/**" is well-formed.
            while (root.length() > 1 && (root.endsWith("/") || root.endsWith("\\"))) {
                root = root.substring(0, root.length() - 1);
            }
            String glob = root + "/**";
            args.add("--allow");
            args.add("Read(" + glob + ")");
            args.add("--allow");
            args.add("Edit(" + glob + ")");
            args.add("--allow");
            args.add("Write(" + glob + ")");
            args.add("--allow");
            args.add("Grep(" + glob + ")");
        }
    }

    private static String canonicalPath(File f) {
        try {
            return f.getCanonicalPath();
        }
        catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    /** Clears {@code firstMessage} so the next prompt uses {@code -r}. */
    private synchronized void markFirstMessageDone() {
        firstMessage = false;
    }

    /**
     * After a non-success first-turn attempt, keep {@code -s} if grok never created
     * the session; switch to {@code -r} only when the session directory already exists
     * (e.g. CLI created it then exited non-zero, or user cancelled mid-create).
     */
    private void resolveFirstMessageAfterAttempt(boolean wasFirst, String sid) {
        if (!wasFirst) {
            return;
        }
        if (GrokUsageSignalsReader.sessionExists(sid)) {
            markFirstMessageDone();
        }
    }

    /**
     * Grok headless mode has no documented mid-turn graceful-abort control
     * message (unlike Claude's stdin control_request or Copilot's
     * session.abort()), so Cancel hard-kills the OS process. Mail has nothing
     * to inject into since there is no persistent session to interrupt.
     */
    @Override
    public synchronized void interrupt(InterruptTypeEnum type) {
        if (type == InterruptTypeEnum.Cancel) {
            cancelledByUser = true;
            processing = false;
            if (currentProcess != null) {
                terminateProcess(currentProcess);
                currentProcess = null;
            }
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, StatusMessageUtil.formatStopped()));
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        processing = false;
        cancelledByUser = true;
        if (currentProcess != null) {
            terminateProcess(currentProcess);
            currentProcess = null;
        }
        GrokAiSession sess = grokAiSession;
        grokAiSession = null;
        if (sess != null) {
            sess.dispose();
        }
        GrokAiMcpRegistrar reg = registrar;
        registrar = null;
        if (reg != null) {
            McpServerRegistry.deregister(reg);
        }
        sessionId = null;
        sessionWorkingDir = null;
        pendingDiff = false;
        sessionConfigDir = null;
        firstMessage = true;
    }

    // Called from the EDT (history load applies the stored session id; the
    // diff/tool-use path checks MCP state). Deliberately NOT synchronized:
    // start() holds this manager's monitor for seconds (CLI spawn + MCP
    // registration), and sharing the monitor here froze the NetBeans UI
    // whenever a tab opened while a start was in flight. All fields touched
    // are volatile, so visibility is preserved without the lock.
    @Override
    public void resumeSession(String existingSessionId) {
        if (existingSessionId == null || existingSessionId.isBlank()) {
            return;
        }
        sessionId = existingSessionId;
        firstMessage = false;
        sessionWorkingDir = null;
    }

    @Override
    public boolean isMcpActive() {
        return registrar != null;
    }
}

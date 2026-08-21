package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.session.ClaudeAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.session.ClaudePersistentSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Manages Claude via ONE long-lived {@code claude --input-format stream-json}
 * process per plugin session (see {@link ClaudePersistentSession}). The process
 * is launched lazily on the first turn and kept alive across turns. On an
 * unexpected process exit (e.g. credit exhausted) the turn is unwedged and the
 * exit surfaced, but the process is NOT auto-restarted — the next user message
 * relaunches it via {@code --resume}.
 */
public class ClaudeAiProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(ClaudeAiProcessManager.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_STDERR_LINES = 100;

    private static JsonObject buildInterruptRequest() {
        JsonObject interrupt = new JsonObject();
        interrupt.addProperty(ClaudeJsonKeyEnum.TYPE.key(), "control_request");
        interrupt.addProperty(ClaudeJsonKeyEnum.REQUEST_ID.key(), "req_interrupt");
        JsonObject request = new JsonObject();
        request.addProperty(ClaudeJsonKeyEnum.SUBTYPE.key(), "interrupt");
        interrupt.add(ClaudeJsonKeyEnum.REQUEST.key(), request);
        return interrupt;
    }

    private volatile boolean firstMessage = true;
    private long cachedContextWindow = 0;
    protected volatile boolean turnInterrupted = false;
    protected volatile boolean awaitingCancelResult = false;
    private volatile ClaudeAiMcpRegistrar registrar = null;
    private ClaudeAiSession claudeAiSession = null;
    protected volatile ClaudePersistentSession persistentSession = null;
    private ClaudeStreamJsonParser parser = null;
    private final List<String> recentStderr = new CopyOnWriteArrayList<>();
    private Set<String> launchedProjectDirs = Set.of();
    private int launchCount = 0;

    int cancelWatchdogMillis = 5000;

    public ClaudeAiProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    protected ClaudePersistentSession launchPersistentSession(List<String> cmd, File workDir,
            Consumer<String> stdoutLine, Consumer<String> stderrLine) throws IOException {
        return ClaudePersistentSession.launch(cmd, workDir, stdoutLine, stderrLine);
    }

    boolean isAwaitingCancelResult() {
        return awaitingCancelResult;
    }

    boolean isTurnInterrupted() {
        return turnInterrupted;
    }

    int getLaunchCount() {
        return launchCount;
    }

    ClaudePersistentSession getPersistentSession() {
        return persistentSession;
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

        if (registrar != null) {
            McpServerRegistry.deregister(registrar);
            registrar = null;
        }

        ClaudeAiMcpRegistrar reg = new ClaudeAiMcpRegistrar(sessionId, executablePath);
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
        claudeAiSession = new ClaudeAiSession(currentSession, listener);

        running = true;
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY, StatusMessageUtil.formatReady("Claude")));
    }

    private List<String> buildLaunchCommand(String sid, boolean resume, List<File> projDirs, Path configDir) {
        List<String> args = new ArrayList<>();
        args.add("-p");
        args.add("--output-format");
        args.add("stream-json");
        args.add("--input-format");
        args.add("stream-json");
        args.add("--verbose");
        args.add("--include-partial-messages");
        if (resume) {
            args.add("--resume");
            args.add(sid);
        }
        else {
            args.add("--session-id");
            args.add(sid);
        }
        args.add("--model");
        args.add(model);
        args.add("--allowedTools");
        args.add("Read,Edit,Write,Bash,Glob,Grep," + McpToolEnum.allMcpNames());
        for (File d : projDirs) {
            if (d != null && d.isDirectory()) {
                args.add("--add-dir");
                args.add(d.getPath());
            }
        }
        if (configDir != null) {
            Path memoryDir = configDir.resolve("memory");
            try {
                Files.createDirectories(memoryDir);
                JsonObject settings = new JsonObject();
                settings.addProperty(ClaudeJsonKeyEnum.AUTO_MEMORY_DIRECTORY.key(), memoryDir.toString());
                args.add("--settings");
                args.add(GSON.toJson(settings));
            }
            catch (IOException e) {
                LOG.log(Level.WARNING, "Could not create memory directory: {0}", e.getMessage());
            }
        }
        return ClaudeExecutableLocator.buildHostCommand(executablePath, args.toArray(String[]::new));
    }

    private AiProcessEventListener buildParserListener() {
        return event -> {
            boolean isTurnComplete = event instanceof TurnCompleteEvent;
            boolean isFailure = event instanceof StatusEvent fse && fse.type() == StatusEventTypeEnum.FAILED;
            boolean suppress;
            synchronized (ClaudeAiProcessManager.this) {
                if (isTurnComplete) {
                    processing = false;
                    awaitingCancelResult = false;
                    long cw = parser != null ? parser.getCachedContextWindow() : 0;
                    if (cw > 0) {
                        cachedContextWindow = cw;
                    }
                }
                else if (isFailure) {
                    processing = false;
                }
                suppress = cancelledByUser || (turnInterrupted && event instanceof StatusEvent se
                        && se.type() == StatusEventTypeEnum.INTERRUPTED);
                if (isTurnComplete || isFailure) {
                    turnInterrupted = false;
                    cancelledByUser = false;
                }
            }
            if (suppress) {
                return;
            }
            listener.onAiProcessEvent(event);
        };
    }

    private void addStderr(String line) {
        recentStderr.add(line);
        while (recentStderr.size() > MAX_STDERR_LINES) {
            recentStderr.remove(0);
        }
    }

    private synchronized ClaudePersistentSession ensureSession(File workDir, List<File> projDirs) throws IOException {
        Set<String> currentDirs = Set.copyOf(projDirs.stream()
                .filter(d -> d != null && d.isDirectory())
                .map(File::getPath)
                .toList());
        if (persistentSession != null && persistentSession.isAlive()) {
            if (launchedProjectDirs.containsAll(currentDirs)) {
                return persistentSession;
            }
            persistentSession.close();
            persistentSession = null;
        }
        boolean resume = !firstMessage;
        List<String> cmd = buildLaunchCommand(sessionId, resume, projDirs, sessionConfigDir);
        final ClaudeStreamJsonParser p = new ClaudeStreamJsonParser(buildParserListener());
        p.initCachedContextWindow(cachedContextWindow);
        if (claudeAiSession != null) {
            p.setOnFirstSessionId(claudeAiSession::registerClaudeSessionAlias);
            String pid = claudeAiSession.getId();
            p.setFileAllowed(path -> {
                var server = McpServerRegistry.getServer();
                return server == null || pid == null || server.isFileAllowed(pid, path);
            });
        }
        parser = p;
        recentStderr.clear();
        final String sid = sessionId;
        ClaudePersistentSession launched = launchPersistentSession(cmd, workDir,
                line -> {
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.INFO, "ai json [{0}]: {1}", new Object[]{sid, line});
                    }
                    p.parseLine(line);
                },
                err -> {
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.WARNING, "claude stderr [{0}]: {1}", new Object[]{sid, err});
                    }
                    addStderr(err);
                });
        persistentSession = launched;
        launchedProjectDirs = currentDirs;
        launchCount++;
        launched.process().onExit().thenRun(() -> handleProcessExit(launched));
        firstMessage = false;
        return persistentSession;
    }

    /**
     * Called when the persistent process exits. Unwedges the current turn and
     * surfaces the exit, but does NOT auto-restart: running stays true so the
     * user's next message relaunches the process via ensureSession (--resume).
     * Ignores stale exits from a superseded session (recycle/stop/relaunch).
     */
    private void handleProcessExit(ClaudePersistentSession dead) {
        boolean suppress;
        synchronized (this) {
            if (persistentSession != dead) {
                return;
            }
            processing = false;
            persistentSession = null;
            awaitingCancelResult = false;
            suppress = cancelledByUser || turnInterrupted;
        }
        int code = dead.process().exitValue();
        if (!suppress && code != 0) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.EXITED,
                    StatusMessageUtil.formatExited("AI", code, new ArrayList<>(recentStderr))));
        }
    }

    @Override
    public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        if (pendingDiff || !running || processing || awaitingCancelResult) {
            return;
        }
        cancelledByUser = false;
        turnInterrupted = false;

        if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
            sessionWorkingDir = workingDir;
        }
        File effectiveWorkDir = sessionWorkingDir != null ? sessionWorkingDir : workingDir;
        List<File> projDirs = projectDirs != null ? projectDirs : List.of();

        ClaudePersistentSession session;
        try {
            session = ensureSession(effectiveWorkDir, projDirs);
        }
        catch (IOException e) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatSendFailed(e.getMessage())));
            return;
        }
        processing = true;
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "ai prompt [{0}]: {1}", new Object[]{sessionId, text});
        }
        if (!session.sendUserTurn(text)) {
            ClaudePersistentSession failedSession = session;
            persistentSession = null;
            failedSession.close();
            try {
                session = ensureSession(effectiveWorkDir, projDirs);
                if (!session.sendUserTurn(text)) {
                    processing = false;
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                            StatusMessageUtil.formatSendFailed("Claude session not available")));
                }
            }
            catch (IOException e) {
                processing = false;
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        StatusMessageUtil.formatSendFailed(e.getMessage())));
            }
        }
    }

    @Override
    public void interrupt(InterruptTypeEnum type) {
        ClaudePersistentSession s = persistentSession;
        if (s == null) {
            // "Stop did nothing because nothing was running" and "Stop ran but
            // output kept coming" are indistinguishable after the fact — this
            // branch is the first of those. A silent no-op is what let Codex's
            // equivalent Mail drop go unnoticed for so long, so both types are
            // logged here, not just Cancel.
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "Claude interrupt: IGNORED, no session (type={0}, session={1})",
                        new Object[]{type, sessionId});
            }
            return;
        }
        String interruptLine = GSON.toJson(buildInterruptRequest());
        switch (type) {
            case Mail -> {
                synchronized (this) {
                    if (!processing) {
                        if (PluginSettings.isDebugJson()) {
                            LOG.log(Level.INFO,
                                    "Claude interrupt: Mail IGNORED, no turn in flight — message will arrive via "
                                    + "normal inbox flush (session={0})", sessionId);
                        }
                        return;
                    }
                    turnInterrupted = true;
                }
                s.sendRawLine(interruptLine);
            }
            case Cancel -> {
                synchronized (this) {
                    if (!processing) {
                        if (PluginSettings.isDebugJson()) {
                            LOG.log(Level.INFO, "Claude interrupt: IGNORED, no turn in flight (session={0})", sessionId);
                        }
                        return;
                    }
                    cancelledByUser = true;
                    awaitingCancelResult = true;
                    processing = false;
                }
                // Stamping the moment the user actually pressed Stop is the only way
                // to measure the wind-down tail afterwards: without it, "it carried
                // on after I stopped it" cannot be told apart from a normal
                // wind-down, and the agent's own log gives no click time to compare
                // against.
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "Claude interrupt: user pressed Stop, cancelling turn (session={0}, connected=true)",
                            sessionId);
                }
                s.sendRawLine(interruptLine);
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "Claude interrupt: control_request(interrupt) sent (session={0})", sessionId);
                }
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, StatusMessageUtil.formatStopped()));
                Thread watchdog = new Thread(() -> {
                    try {
                        Thread.sleep(cancelWatchdogMillis);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    synchronized (ClaudeAiProcessManager.this) {
                        if (!awaitingCancelResult || persistentSession != s) {
                            return;
                        }
                        persistentSession = null;
                        awaitingCancelResult = false;
                    }
                    s.close();
                }, "ai-cancel-watchdog");
                watchdog.setDaemon(true);
                watchdog.start();
            }
        }
    }

    @Override
    public synchronized void stop() {
        // Logged before the state is torn down, so the record says what was
        // actually in flight at the moment of the stop rather than the
        // cleared-out aftermath.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "Claude stop: shutting session down (session={0}, turnInFlight={1}, sessionAlive={2})",
                    new Object[]{sessionId, processing, persistentSession != null});
        }
        running = false;
        processing = false;
        cancelledByUser = true;

        ClaudePersistentSession s = persistentSession;
        persistentSession = null;
        if (s != null) {
            s.close();
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
        pendingDiff = false;
        cachedContextWindow = 0;
        sessionConfigDir = null;
        parser = null;
        firstMessage = true;
        recentStderr.clear();
        launchedProjectDirs = Set.of();
        launchCount = 0;
    }

    public synchronized void recycleForModelChange() {
        ClaudePersistentSession s = persistentSession;
        persistentSession = null;
        if (s != null) {
            s.close();
        }
    }

    @Override
    public void resumeSession(String existingSessionId) {
        if (existingSessionId == null || existingSessionId.isBlank()) {
            return;
        }
        sessionId = existingSessionId;
        sessionWorkingDir = null;
        firstMessage = false;
    }

    @Override
    public boolean isMcpActive() {
        return registrar != null;
    }

}

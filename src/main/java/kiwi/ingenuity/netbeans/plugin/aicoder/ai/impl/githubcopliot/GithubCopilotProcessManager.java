package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.events.GithubCopilotFatalErrorEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.events.GithubCopilotTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.session.GithubCopilotAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Drives the GitHub Copilot CLI (`copilot -p ... --output-format json`) one
 * process per turn, mirroring the Claude CLI integration. Session continuity is
 * via --session-id (same UUID each turn: created on the first call, resumed
 * after). Plugin MCP tools are exposed to the CLI per-session via
 * --additional-mcp-config pointing at the shared McpHookServer endpoint.
 */
public class GithubCopilotProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(GithubCopilotProcessManager.class.getName());

    /**
     * Matches the CLI's per-turn context-usage log line, e.g.
     * {@code CompactionProcessor: Utilization 10.1% (20236/200000 tokens) below threshold 80%}.
     * Group 1 = used tokens, group 2 = context-window total.
     */
    private static final Pattern UTILIZATION = Pattern.compile("Utilization\\s+[0-9.]+%\\s+\\((\\d+)/(\\d+)\\s+tokens\\)");

    // Copilot CLI --session-id (its own resume identifier). Normally equals the
    // plugin session id, but is replaced with a fresh UUID after a corrupted
    // session so MCP routing (which keys on the plugin session id) is unaffected.
    private String copilotSessionId = null;
    // Set when the CLI reports the session file is corrupted / cannot be loaded.
    // On the next start() we then use a fresh session id instead of reusing the
    // broken one (which would fail every turn).
    private volatile boolean sessionCorrupted = false;
    private GithubCopilotMcpRegistrar registrar = null;
    private GithubCopilotAiSession copilotAiSession = null;

    public GithubCopilotProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    @Override
    public synchronized void start(String executablePath, String model) {
        stop();
        this.executablePath = executablePath;
        this.model = model;

        if (!GithubCopilotExecutableLocator.isExecutableFile(executablePath)) {
            running = false;
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatStartFailed("copilot executable not found at " + executablePath)));
            listener.onAiProcessEvent(new GithubCopilotFatalErrorEvent(
                    "EXECUTABLE_NOT_FOUND", "GitHub Copilot CLI not found"));
            return;
        }
        if (currentSession == null) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatSessionNotConfigured()));
            return;
        }
        // MCP endpoint path + SessionRegistry always key on the stable plugin
        // session id so tool routing works. The Copilot CLI --session-id is a
        // separate identifier that may be reset to a fresh UUID after a corrupted
        // session, without disturbing MCP routing.
        sessionId = currentSession.id();
        if (sessionCorrupted) {
            copilotSessionId = java.util.UUID.randomUUID().toString();
            sessionCorrupted = false;
        }
        else {
            copilotSessionId = currentSession.id();
        }

        GithubCopilotMcpRegistrar reg = new GithubCopilotMcpRegistrar(sessionId);
        if (!McpServerRegistry.register(reg)) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatMcpSetupFailed()));
            return;
        }
        registrar = reg;
        // Register the MCP-layer session so plugin tool calls route to this
        // session, and so it is visible to peers (ListAiSessions) and can
        // receive inbox messages.
        copilotAiSession = new GithubCopilotAiSession(currentSession, listener);
        running = true;
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY, StatusMessageUtil.formatReady("GitHub Copilot")));
        listener.onAiProcessEvent(new GithubCopilotFatalErrorEvent(null, null));
    }

    private String buildMcpConfigJson() {
        McpHookServer server = McpServerRegistry.getServer();
        if (server == null || sessionId == null) {
            return null;
        }
        String endpoint = server.getBaseUrl() + "/mcp/" + AiTypeEnum.GitHubCoPilot.key();
        JsonObject inner = new JsonObject();
        inner.addProperty("type", "http");
        inner.addProperty("url", endpoint);
        JsonArray tools = new JsonArray();
        tools.add("*");
        inner.add("tools", tools);
        JsonObject servers = new JsonObject();
        servers.add(StringConst.PLUGIN_ID, inner);
        JsonObject root = new JsonObject();
        root.add("mcpServers", servers);
        return root.toString();
    }

    /**
     * Reads the Copilot CLI's own log (written under {@code --log-dir}) and
     * fires a {@link GithubCopilotTokenUsageEvent} carrying the real
     * context-window usage. The CLI logs a line each turn like
     * {@code CompactionProcessor: Utilization 10.1% (20236/200000 tokens) ...}
     * — the only place it exposes used/total context tokens (the JSON stream
     * omits them). Keeps only the newest log file (one is written per turn) so
     * the working dir's {@code logs/} folder does not accumulate. Best-effort:
     * silent on any failure, leaving the bar on its previous value.
     */
    private void fireContextUsageFromLog(Path logDir, String model) {
        if (logDir == null) {
            return;
        }
        try {
            if (!Files.isDirectory(logDir)) {
                return;
            }
            List<Path> logs;
            try (Stream<Path> files = Files.list(logDir)) {
                logs = files.filter(f -> f.getFileName().toString().endsWith(".log"))
                        .sorted(Comparator.comparingLong((Path f) -> f.toFile().lastModified()).reversed())
                        .toList();
            }
            if (logs.isEmpty()) {
                return;
            }
            Path newest = logs.get(0);
            // Prune older per-turn logs to keep <workdir>/logs tidy.
            for (int i = 1; i < logs.size(); i++) {
                try {
                    Files.deleteIfExists(logs.get(i));
                }
                catch (IOException ignore) {
                }
            }
            long used = -1;
            long total = -1;
            for (String line : Files.readAllLines(newest, StandardCharsets.UTF_8)) {
                Matcher m = UTILIZATION.matcher(line);
                if (m.find()) {
                    used = Long.parseLong(m.group(1));
                    total = Long.parseLong(m.group(2));
                }
            }
            if (used >= 0 && total > 0) {
                listener.onAiProcessEvent(
                        new GithubCopilotTokenUsageEvent(
                                (int) used, (int) total, model));
            }
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "Could not read Copilot context usage from " + logDir, e);
        }
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
        String sid = copilotSessionId;
        String currentModel = model;
        // Stable plugin session id (NOT copilotSessionId, which may be reset to a
        // fresh UUID after corruption) — keys the per-session config/log dir.
        String pluginSessionId = currentSession != null ? currentSession.id() : null;
        String mcpConfig = buildMcpConfigJson();
        List<File> projDirs = projectDirs != null ? projectDirs : List.of();

        Thread t = new Thread(() -> {
            List<String> stderrLines = new CopyOnWriteArrayList<>();
            Process p = null;
            Path logDir = null;
            try {
                // Under Debug JSON, log the full outgoing prompt (the -p argument,
                // which carries the session identity block) so it can be verified
                // in the IDE log — only stdout responses were logged before.
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "copilot prompt (-p): {0}", text);
                }
                List<String> args = new ArrayList<>();
                args.add("-p");
                args.add(text);
                args.add("--output-format");
                args.add("json");
                args.add("--stream");
                args.add("on");
                args.add("--allow-all-tools");
                // Deny Copilot's native file-mutation tools so it edits via the
                // plugin's permission-gated MCP tools (ApplyEdit/WriteFile), which
                // route through the NetBeans Accept/Reject diff panel.
                args.add("--deny-tool");
                args.add("edit");
                args.add("--deny-tool");
                args.add("create");
                args.add("--no-color");
                // Point the CLI's own log at the per-session config dir
                // (~/.ai-coder/github_copilot/{sessionId}/logs) so after the turn
                // we can read its "CompactionProcessor: Utilization X% (used/total
                // tokens)" line — the only place Copilot exposes real context-
                // window usage (the JSON stream omits it). This dir is removed when
                // the session is deleted. Path.resolve keeps it platform-independent.
                if (pluginSessionId != null) {
                    try {
                        Path dir = PluginUtil.getPluginAiSessionConfigDir(AiTypeEnum.GitHubCoPilot, pluginSessionId).resolve("logs");
                        Files.createDirectories(dir);
                        logDir = dir;
                        args.add("--log-dir");
                        args.add(dir.toString());
                    }
                    catch (IOException ioe) {
                        LOG.log(Level.FINE, "Could not create Copilot log dir", ioe);
                    }
                }
                // Do NOT add --enable-memory: prompt mode (-p) has memory
                // disabled by default, which is what we want. Adding it would
                // turn Copilot's persistent memory back on for every turn.
                if (currentModel != null && !currentModel.isBlank()) {
                    args.add("--model");
                    args.add(currentModel);
                }
                if (sid != null) {
                    args.add("--session-id");
                    args.add(sid);
                }
                if (effectiveWorkDir != null && effectiveWorkDir.isDirectory()) {
                    args.add("-C");
                    args.add(effectiveWorkDir.getPath());
                }
                for (File d : projDirs) {
                    if (d != null && d.isDirectory()) {
                        args.add("--add-dir");
                        args.add(d.getPath());
                    }
                }
                if (mcpConfig != null) {
                    args.add("--additional-mcp-config");
                    args.add(mcpConfig);
                }

                List<String> cmd = GithubCopilotExecutableLocator.buildHostCommand(executablePath, args.toArray(String[]::new));
                ProcessBuilder pb = new ProcessBuilder(cmd);
                if (effectiveWorkDir != null && effectiveWorkDir.isDirectory()) {
                    pb.directory(effectiveWorkDir);
                }
                pb.redirectErrorStream(false);
                p = pb.start();
                synchronized (GithubCopilotProcessManager.this) {
                    if (!running) {
                        p.destroyForcibly();
                        return;
                    }
                    currentProcess = p;
                }
                p.getOutputStream().close();

                final Process proc = p;
                boolean debugJson = PluginSettings.isDebugJson();
                Thread stderrThread = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (stderrLines.size() < 1000) {
                                stderrLines.add(line);
                            }
                            if (debugJson) {
                                LOG.log(Level.INFO, "copilot stderr: {0}", line);
                            }
                        }
                    }
                    catch (IOException ignored) {
                    }
                }, "copilot-stderr");
                stderrThread.setDaemon(true);
                stderrThread.start();

                // Clear `processing` before TurnCompleteEvent reaches the UI so that
                // refreshInputEnabled() (which calls setSendEnabled(!isProcessing())) re-enables
                // the input box. Without this the event arrives while the process is still
                // running, so the chat box stays disabled after the first reply.
                AiProcessEventListener parserListener = event -> {
                    boolean isTurnComplete = event instanceof TurnCompleteEvent;
                    boolean shouldFire;
                    synchronized (GithubCopilotProcessManager.this) {
                        if (isTurnComplete) {
                            processing = false;
                        }
                        shouldFire = !cancelledByUser;
                    }
                    if (shouldFire) {
                        listener.onAiProcessEvent(event);
                    }
                };
                GithubCopilotStreamJsonParser parser = new GithubCopilotStreamJsonParser(parserListener);
                if (copilotAiSession != null) {
                    parser.setOnSessionId(copilotAiSession::registerSessionAlias);
                }
                // Capture a human-readable error (e.g. quota exceeded) reported in
                // the JSON stream so we can show it instead of a bare exit code.
                final java.util.concurrent.atomic.AtomicReference<String> apiError
                        = new java.util.concurrent.atomic.AtomicReference<>();
                parser.setOnError(apiError::set);
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (debugJson) {
                            LOG.log(Level.INFO, "copilot json: {0}", line);
                        }
                        parser.parseLine(line);
                    }
                }

                stderrThread.join(2000);
                boolean exited = p.waitFor(120, TimeUnit.SECONDS);
                if (!exited) {
                    p.destroyForcibly();
                }
                int code = exited ? p.exitValue() : -1;

                boolean shouldReport;
                GithubCopilotMcpRegistrar deadReg = null;
                GithubCopilotAiSession deadSession = null;
                synchronized (GithubCopilotProcessManager.this) {
                    shouldReport = (currentProcess == p) && !cancelledByUser;
                    if (currentProcess == p) {
                        processing = false;
                        currentProcess = null;
                        cancelledByUser = false;
                        if (code != 0) {
                            running = false;
                            deadReg = registrar;
                            registrar = null;
                            deadSession = copilotAiSession;
                            copilotAiSession = null;
                        }
                    }
                }
                if (deadReg != null) {
                    McpServerRegistry.deregister(deadReg);
                }
                if (deadSession != null) {
                    deadSession.dispose();
                }
                // Real context-window usage the CLI logged during this turn.
                fireContextUsageFromLog(logDir, currentModel);
                if (shouldReport && code != 0) {
                    String joined = String.join("\n", stderrLines);
                    if (joined.contains("could not be loaded") || joined.contains("corrupted")) {
                        sessionCorrupted = true;
                    }
                    String lower = joined.toLowerCase();
                    if (lower.contains("not logged in") || lower.contains("authenticat")
                            || lower.contains("unauthorized") || lower.contains("copilot login")) {
                        listener.onAiProcessEvent(new GithubCopilotFatalErrorEvent(
                                "AUTHENTICATION_REQUIRED", "Not authenticated — run `copilot login` in a terminal"));
                    }
                    if (lower.contains("not available") && currentModel != null
                            && !"auto".equalsIgnoreCase(currentModel)) {
                        synchronized (GithubCopilotProcessManager.this) {
                            model = "auto";
                        }
                        GithubCopilotPluginSettings.setModel("auto");
                        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                "Model '" + currentModel + "' is not available for your account — switched to 'auto'. Please resend your message."));
                    }
                    // Prefer the human-readable error from the JSON stream (e.g.
                    // "You have exceeded your monthly quota") so the user sees why
                    // the turn produced no output, not just a bare exit code.
                    String apiErr = apiError.get();
                    String message;
                    if (apiErr != null && !apiErr.isBlank()) {
                        message = "GitHub Copilot: " + apiErr;
                    }
                    else {
                        String detail = stderrLines.isEmpty() ? ""
                                : ": " + String.join("\n", stderrLines.subList(0, Math.min(stderrLines.size(), 10)));
                        message = "GitHub Copilot exited (code " + code + ")" + detail;
                    }
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.EXITED, message));
                }
            }
            catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                boolean wasUserCancel;
                GithubCopilotMcpRegistrar failedReg = null;
                GithubCopilotAiSession failedSession = null;
                synchronized (GithubCopilotProcessManager.this) {
                    wasUserCancel = cancelledByUser;
                    cancelledByUser = false;
                    processing = false;
                    currentProcess = null;
                    running = false;
                    if (!wasUserCancel) {
                        failedReg = registrar;
                        registrar = null;
                        failedSession = copilotAiSession;
                        copilotAiSession = null;
                    }
                }
                if (p != null) {
                    p.destroyForcibly();
                }
                if (failedReg != null) {
                    McpServerRegistry.deregister(failedReg);
                }
                if (failedSession != null) {
                    failedSession.dispose();
                }
                if (!wasUserCancel) {
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                            StatusMessageUtil.formatSendFailed(e.getMessage())));
                }
            }
            finally {
                synchronized (GithubCopilotProcessManager.this) {
                    processing = false;
                }
            }
        }, "copilot-prompt");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void cancel() {
        Process proc;
        synchronized (this) {
            if (!processing) {
                return;
            }
            cancelledByUser = true;
            processing = false;
            proc = currentProcess;
            currentProcess = null;
        }
        if (proc != null) {
            proc.destroyForcibly();
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, StatusMessageUtil.formatStopped()));
    }

    /**
     * Graceful interrupt: sends SIGTERM so the Copilot CLI can flush its
     * session file before exiting, reducing the risk of session corruption.
     * Falls back to SIGKILL after 5 seconds if the process has not exited.
     * Unlike Claude (which accepts a JSON interrupt on stdin), Copilot runs
     * one-shot with stdin closed immediately, so no in-band signal is possible.
     */
    @Override
    public void interrupt(InterruptTypeEnum type) {
        switch (type) {
            case User -> {
                Process proc = currentProcess;
                if (proc == null) {
                    return;
                }
                proc.destroy();  // SIGTERM — graceful shutdown
                Thread killer = new Thread(() -> {
                    try {
                        if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                            proc.destroyForcibly();
                        }
                    }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }, "copilot-interrupt-fallback");
                killer.setDaemon(true);
                killer.start();
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
        GithubCopilotAiSession sess = copilotAiSession;
        copilotAiSession = null;
        if (sess != null) {
            sess.dispose();
        }
        GithubCopilotMcpRegistrar reg = registrar;
        registrar = null;
        if (reg != null) {
            McpServerRegistry.deregister(reg);
        }
        sessionId = null;
        copilotSessionId = null;
        sessionWorkingDir = null;
        pendingDiff = false;
        sessionConfigDir = null;
    }

    @Override
    public synchronized void resumeSession(String existingSessionId) {
        if (existingSessionId == null || existingSessionId.isBlank()) {
            return;
        }
        copilotSessionId = existingSessionId;
        sessionWorkingDir = null;
    }

    @Override
    public synchronized boolean isMcpActive() {
        return registrar != null;
    }

    public void onTabActivated() {
    }
}

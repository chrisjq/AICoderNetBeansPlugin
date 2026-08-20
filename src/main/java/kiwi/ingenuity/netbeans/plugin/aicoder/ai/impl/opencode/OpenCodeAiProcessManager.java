package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpConnection;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpErrorCodeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpMethodEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Manages an OpenCode agent via one long-lived {@code opencode acp} process per
 * plugin session. The process is spawned lazily on the first
 * {@link #sendPrompt} call. MCP wiring is deferred to a later slice.
 *
 * <p>
 * <b>Safety invariant:</b> The child process is always launched with
 * {@code OPENCODE_CONFIG_CONTENT} set to force {@code ask} permission for all
 * file edits, bash commands and external-directory access. Without this,
 * OpenCode's defaults allow silent file mutations even when the client
 * advertises {@code fs.writeTextFile} capability — confirmed by live probe
 * (design doc §4).
 */
public class OpenCodeAiProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(OpenCodeAiProcessManager.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_STDERR_LINES = 100;
    static final String PLUGIN_VERSION = "1.2";

    /**
     * Builds the value for the OPENCODE_CONFIG_CONTENT environment variable.
     * Forces "ask" permission for all edit, bash and external-directory
     * operations, and denies sub-agent spawning outright. This MERGES with the
     * user's existing config — it does not replace it (verified by live probe,
     * design doc §4) — so it constrains only the sessions this plugin launches
     * and leaves the user's own {@code opencode} CLI usage alone.
     */
    static String buildPermissionConfigJson() {
        JsonObject permission = new JsonObject();
        permission.addProperty("edit", "ask");
        permission.addProperty("bash", "ask");
        permission.addProperty("external_directory", "ask");
        // Sub-agents run invisibly: they get their own session, never surface in
        // this session's transcript, and their failures are not reported back. A
        // live OpenCode session sat "busy" for 80 minutes after a spawned explore
        // sub-agent died mid-stream with nothing logged — the parent simply waited
        // forever. The plugin also already provides multi-agent work through peer
        // sessions, which ARE visible and interruptible, so nothing is lost.
        //
        // "deny" is OpenCode's own vocabulary here: it applies
        // {"permission":"task","action":"deny"} to every sub-agent it spawns to
        // stop them recursing.
        permission.addProperty("task", "deny");
        JsonObject config = new JsonObject();
        config.add("permission", permission);
        return GSON.toJson(config);
    }

    static JsonObject buildInitializeParams(String pluginVersion) {
        JsonObject fs = new JsonObject();
        fs.addProperty("readTextFile", true);
        fs.addProperty("writeTextFile", true);
        JsonObject capabilities = new JsonObject();
        capabilities.add("fs", fs);
        capabilities.addProperty("terminal", false);
        JsonObject info = new JsonObject();
        info.addProperty("name", "aicoder-netbeans");
        info.addProperty("version", pluginVersion);
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", 1);
        params.add("clientCapabilities", capabilities);
        params.add("clientInfo", info);
        return params;
    }

    static JsonObject buildSessionResumeParams(String acpSessionId, String cwd, String mcpEndpointUrl) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", acpSessionId);
        params.addProperty("cwd", cwd);
        JsonArray mcpServers = new JsonArray();
        if (mcpEndpointUrl != null) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "http");
            entry.addProperty("name", StringConst.PLUGIN_ID);
            entry.addProperty("url", mcpEndpointUrl);
            entry.add("headers", new JsonArray());
            mcpServers.add(entry);
        }
        params.add("mcpServers", mcpServers);
        return params;
    }

    static JsonObject buildSessionNewParams(String absoluteCwd) {
        return buildSessionNewParams(absoluteCwd, null);
    }

    static JsonObject buildSessionNewParams(String absoluteCwd, String mcpEndpointUrl) {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", absoluteCwd);
        JsonArray mcpServers = new JsonArray();
        if (mcpEndpointUrl != null) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "http");
            entry.addProperty("name", StringConst.PLUGIN_ID);
            entry.addProperty("url", mcpEndpointUrl);
            entry.add("headers", new JsonArray());
            mcpServers.add(entry);
        }
        params.add("mcpServers", mcpServers);
        return params;
    }

    static String resolveSessionId(boolean resumed, String resumeId, JsonObject sessionResult) {
        // session/resume response contains only configOptions; use the requested id directly.
        // session/new always returns sessionId in its response.
        if (resumed) {
            return resumeId;
        }
        return sessionResult != null && sessionResult.has("sessionId")
                ? sessionResult.get("sessionId").getAsString() : null;
    }

    private volatile AcpConnection connection = null;
    private volatile String acpSessionId = null;
    volatile String pendingAcpResumeId = null;
    volatile JsonArray sessionConfigOptions = null;
    private volatile OpenCodeAcpClientHandler activeHandler = null;
    private final List<String> recentStderr = new CopyOnWriteArrayList<>();
    private volatile OpenCodeAiMcpRegistrar registrar = null;
    private OpenCodeAiSession openCodeAiSession = null;
    volatile Runnable onSessionEstablished = null;

    public OpenCodeAiProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    void setOnSessionEstablished(Runnable r) {
        this.onSessionEstablished = r;
    }

    @Override
    public synchronized void start(String executablePath, String model) {
        stop();
        this.executablePath = executablePath;
        this.model = model;

        if (!OpenCodeExecutableLocator.isExecutableFile(executablePath)) {
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

        // MCP registration: start the shared HTTP server. Degrade gracefully on failure.
        OpenCodeAiMcpRegistrar reg = new OpenCodeAiMcpRegistrar(sessionId);
        try {
            boolean ok = McpServerRegistry.register(reg).get(30, TimeUnit.SECONDS);
            if (ok) {
                registrar = reg;
            }
            else {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "MCP server registration returned false — running without MCP tools"));
            }
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "MCP server registration failed; running without MCP tools", e);
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "MCP server unavailable — running without MCP tools"));
        }

        if (PluginSettings.isDebugJson()) {
            String m = currentSession.settings() instanceof OpenCodeSessionSettings ocs ? ocs.model() : null;
            LOG.log(Level.INFO,
                    "OpenCode start() [{0}]: registering OpenCodeAiSession — session#={1} settings#={2} model={3}",
                    new Object[]{sessionId, System.identityHashCode(currentSession),
                        System.identityHashCode(currentSession.settings()), m});
        }
        openCodeAiSession = new OpenCodeAiSession(currentSession, listener);
        running = true;
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY, StatusMessageUtil.formatReady("OpenCode")));
    }

    /**
     * Spawns the opencode process and performs the ACP handshake. Always called
     * on a background thread — this method blocks up to 30 s on
     * {@code initialize} and again on {@code session/new}. The instance monitor
     * is held only for brief state writes, never across the blocking waits, so
     * other synchronized methods (stop(), cancel via interrupt()) can run
     * concurrently.
     */
    protected void spawnAndHandshake(File workDir) throws Exception {
        List<String> cmd = OpenCodeExecutableLocator.buildHostCommand(
                executablePath, "acp", "--cwd", workDir.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.environment().put("OPENCODE_CONFIG_CONTENT", buildPermissionConfigJson());

        recentStderr.clear();
        Process process = pb.start();

        // Register the process under the lock so stop() can destroy it if it races us here.
        synchronized (this) {
            if (!running) {
                process.destroyForcibly();
                throw new IOException("stop() called before handshake began");
            }
            currentProcess = process;
        }

        startStderrDrainer(process);

        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(listener, this::onHandlerDisconnected);
        AcpConnection conn = new AcpConnection(process.getOutputStream(), process.getInputStream(), handler);

        process.onExit().thenRun(() -> handleProcessExit(process));

        // ---- Blocking wait 1: initialize (outside the monitor) ----
        JsonObject initResult;
        try {
            initResult = conn.sendRequest(AcpMethodEnum.INITIALIZE, buildInitializeParams(PLUGIN_VERSION))
                    .get(30, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            conn.close();
            synchronized (this) {
                if (currentProcess == process) {
                    currentProcess = null;
                }
            }
            process.destroyForcibly();
            throw new IOException("ACP initialize failed: " + e.getMessage(), e);
        }
        int proto = initResult.has("protocolVersion") ? initResult.get("protocolVersion").getAsInt() : -1;
        if (proto != 1) {
            conn.close();
            synchronized (this) {
                if (currentProcess == process) {
                    currentProcess = null;
                }
            }
            process.destroyForcibly();
            throw new IOException("Unsupported ACP protocol version: " + proto + " (expected 1)");
        }

        // ---- Blocking wait 2: session/resume (if stored) or session/new ----
        // Use the registry's single source of truth for the endpoint URL — correct for all sessions,
        // not just the first (the registrar only receives addMcpEndpoint on the first of its type).
        String mcpBaseUrl = McpServerRegistry.endpointUrlFor(AiTypeEnum.OPENCODE);
        String resumeId = pendingAcpResumeId;
        JsonObject sessionResult = null;
        boolean resumed = false;

        if (resumeId != null) {
            try {
                JsonObject resumeResult = conn.sendRequest(AcpMethodEnum.SESSION_RESUME,
                        buildSessionResumeParams(resumeId, workDir.getAbsolutePath(), mcpBaseUrl))
                        .get(30, TimeUnit.SECONDS);
                // session/resume returns only configOptions (no sessionId) — the client
                // supplied the id in the request; a non-exception return means resume succeeded.
                sessionResult = resumeResult;
                resumed = true;
            }
            catch (Exception e) {
                LOG.log(Level.INFO, "session/resume failed; falling back to session/new: {0}", e.getMessage());
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "Previous OpenCode session could not be resumed; starting fresh"));
            }
        }

        if (!resumed) {
            try {
                sessionResult = conn.sendRequest(AcpMethodEnum.SESSION_NEW,
                        buildSessionNewParams(workDir.getAbsolutePath(), mcpBaseUrl))
                        .get(30, TimeUnit.SECONDS);
            }
            catch (Exception e) {
                conn.close();
                synchronized (this) {
                    if (currentProcess == process) {
                        currentProcess = null;
                    }
                }
                process.destroyForcibly();
                throw new IOException("ACP session/new failed: " + e.getMessage(), e);
            }
        }

        String sid = resolveSessionId(resumed, resumeId, sessionResult);
        if (sid == null || sid.isBlank()) {
            conn.close();
            synchronized (this) {
                if (currentProcess == process) {
                    currentProcess = null;
                }
            }
            process.destroyForcibly();
            throw new IOException("session/new returned no sessionId");
        }

        // ---- Publish results under the lock; bail if stop() ran during the waits ----
        synchronized (this) {
            if (!running) {
                conn.close();
                process.destroyForcibly();
                if (currentProcess == process) {
                    currentProcess = null;
                }
                throw new IOException("stop() called during handshake");
            }
            acpSessionId = sid;
            pendingAcpResumeId = null;
            if (currentSession != null) {
                if (currentSession.settings() instanceof OpenCodeSessionSettings) {
                    ((OpenCodeSessionSettings) currentSession.settings()).setAcpSessionId(sid);
                }
                currentSession.putExtra("opencode_acp_session_id", sid);
            }
            if (sessionResult.has("configOptions") && sessionResult.get("configOptions").isJsonArray()) {
                sessionConfigOptions = sessionResult.getAsJsonArray("configOptions");
            }
            activeHandler = handler;
            this.connection = conn;
        }
        if (resumed) {
            LOG.log(Level.INFO, "Resumed OpenCode ACP session: {0}", acpSessionId);
        }
        else {
            LOG.log(Level.INFO, "Started new OpenCode ACP session: {0}", acpSessionId);
        }
        Runnable cb = onSessionEstablished;
        if (cb != null) {
            cb.run();
        }
        boolean settingsChanged = applyInitialModeIfNeeded();
        if (settingsChanged && cb != null) {
            // Validation replaced or cleared a stored value — re-persist so the
            // corrected settings survive the next IDE restart.
            cb.run();
        }
        // Notify info bar that config options are now available (fired outside
        // the monitor to avoid deadlock; the info bar guards with invokeLater).
        if (sessionConfigOptions != null) {
            listener.onAiProcessEvent(new OpenCodeConfigOptionsEvent(sessionConfigOptions));
        }
    }

    boolean applyInitialModeIfNeeded() {
        if (sessionConfigOptions == null || currentSession == null) {
            return false;
        }
        if (!(currentSession.settings() instanceof OpenCodeSessionSettings)) {
            return false;
        }
        OpenCodeSessionSettings s = (OpenCodeSessionSettings) currentSession.settings();
        boolean settingsChanged = false;
        // Model first: effort's available values depend on which model is active
        // (design doc §13), so applyInitialEffortOption must see configOptions
        // AFTER a model switch, not before.
        settingsChanged |= applyInitialModelOption(s);
        settingsChanged |= applyInitialModeOption(s);
        settingsChanged |= applyInitialEffortOption(s);
        return settingsChanged;
    }

    /**
     * Reconciles the session's chosen model against what the agent actually
     * started with. {@code session/new} carries no model parameter — OpenCode
     * always picks its own model when a session is created — so without this,
     * every session silently ran whatever OpenCode defaulted to (typically
     * {@code opencode/big-pickle}) regardless of what the user chose per
     * session. Unlike mode/effort, an unavailable choice is never silently
     * substituted here: it is surfaced (status message + log) instead, because
     * running a different model than the user asked for without telling them is
     * the entire bug this method exists to fix.
     */
    private boolean applyInitialModelOption(OpenCodeSessionSettings s) {
        String agentCurrentModel = null;
        List<String> availableModels = new ArrayList<>();
        for (JsonElement el : sessionConfigOptions) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject opt = el.getAsJsonObject();
            if ("model".equals(opt.has("id") ? opt.get("id").getAsString() : null)) {
                if (opt.has("currentValue")) {
                    agentCurrentModel = opt.get("currentValue").getAsString();
                }
                if (opt.has("options") && opt.get("options").isJsonArray()) {
                    for (JsonElement v : opt.getAsJsonArray("options")) {
                        if (v.isJsonObject() && v.getAsJsonObject().has("value")) {
                            availableModels.add(v.getAsJsonObject().get("value").getAsString());
                        }
                    }
                }
                break;
            }
        }
        String requestedModel = s.model();
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenCode configOptions model: agent reports \"{0}\" (session requested \"{1}\")",
                    new Object[]{agentCurrentModel, requestedModel});
            if (requestedModel != null && !requestedModel.isBlank() && !requestedModel.equals(agentCurrentModel)) {
                LOG.log(Level.WARNING,
                        "OpenCode model mismatch: session settings say \"{0}\" but the agent started with \"{1}\"",
                        new Object[]{requestedModel, agentCurrentModel});
            }
        }
        if (requestedModel == null || requestedModel.isBlank() || requestedModel.equals(agentCurrentModel)) {
            return false;
        }
        if (!availableModels.isEmpty() && !availableModels.contains(requestedModel)) {
            LOG.log(Level.WARNING,
                    "OpenCode session requested model \"{0}\" but the agent does not offer it (available: {1}); "
                    + "running \"{2}\" instead", new Object[]{requestedModel, availableModels, agentCurrentModel});
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Model \"" + requestedModel + "\" is not available; running \"" + agentCurrentModel
                    + "\" instead"));
            return false;
        }
        try {
            JsonArray updated = setConfigOption("model", requestedModel).get(30, TimeUnit.SECONDS);
            sessionConfigOptions = updated;
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "OpenCode rejected model \"{0}\": {1}",
                    new Object[]{requestedModel, e.getMessage()});
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Model \"" + requestedModel + "\" was rejected by OpenCode; running \"" + agentCurrentModel
                    + "\" instead"));
        }
        return false;
    }

    private boolean applyInitialModeOption(OpenCodeSessionSettings s) {
        String agentCurrentMode = null;
        List<String> availableModes = new ArrayList<>();
        for (JsonElement el : sessionConfigOptions) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject opt = el.getAsJsonObject();
            if ("mode".equals(opt.has("id") ? opt.get("id").getAsString() : null)) {
                if (opt.has("currentValue")) {
                    agentCurrentMode = opt.get("currentValue").getAsString();
                }
                if (opt.has("options") && opt.get("options").isJsonArray()) {
                    for (JsonElement v : opt.getAsJsonArray("options")) {
                        if (v.isJsonObject() && v.getAsJsonObject().has("value")) {
                            availableModes.add(v.getAsJsonObject().get("value").getAsString());
                        }
                    }
                }
                break;
            }
        }
        String effectiveMode = s.mode();
        if (effectiveMode == null || effectiveMode.isBlank()) {
            effectiveMode = OpenCodePluginSettings.DEFAULT_MODE;
        }
        boolean settingsChanged = false;
        if (!availableModes.isEmpty() && !availableModes.contains(effectiveMode)) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Mode \"" + effectiveMode + "\" is not available; using agent default"));
            effectiveMode = agentCurrentMode;
            s.setMode(effectiveMode);
            settingsChanged = true;
        }
        if (effectiveMode != null && !effectiveMode.equals(agentCurrentMode)) {
            try {
                JsonArray updated = setConfigOption("mode", effectiveMode).get(30, TimeUnit.SECONDS);
                sessionConfigOptions = updated;
            }
            catch (Exception e) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "Mode \"" + effectiveMode + "\" rejected by OpenCode; using default"));
            }
        }
        return settingsChanged;
    }

    private boolean applyInitialEffortOption(OpenCodeSessionSettings s) {
        String agentCurrentEffort = null;
        List<String> availableEfforts = new ArrayList<>();
        boolean effortOptionExists = false;
        for (JsonElement el : sessionConfigOptions) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject opt = el.getAsJsonObject();
            if ("effort".equals(opt.has("id") ? opt.get("id").getAsString() : null)) {
                effortOptionExists = true;
                if (opt.has("currentValue")) {
                    agentCurrentEffort = opt.get("currentValue").getAsString();
                }
                if (opt.has("options") && opt.get("options").isJsonArray()) {
                    for (JsonElement v : opt.getAsJsonArray("options")) {
                        if (v.isJsonObject() && v.getAsJsonObject().has("value")) {
                            availableEfforts.add(v.getAsJsonObject().get("value").getAsString());
                        }
                    }
                }
                break;
            }
        }
        String storedEffort = s.effort();
        if (!effortOptionExists) {
            if (storedEffort != null) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "Effort setting \"" + storedEffort + "\" ignored: model does not support effort"));
                s.setEffort(null);
                return true;
            }
            return false;
        }
        if (storedEffort == null) {
            return false;
        }
        if (!availableEfforts.contains(storedEffort)) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Effort \"" + storedEffort + "\" is not available; clearing stored effort"));
            s.setEffort(null);
            return true;
        }
        if (!storedEffort.equals(agentCurrentEffort)) {
            try {
                JsonArray updated = setConfigOption("effort", storedEffort).get(30, TimeUnit.SECONDS);
                sessionConfigOptions = updated;
            }
            catch (Exception e) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "Effort \"" + storedEffort + "\" rejected by OpenCode"));
            }
        }
        return false;
    }

    private void startStderrDrainer(Process process) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.WARNING, "opencode stderr: {0}", line);
                    }
                    recentStderr.add(line);
                    while (recentStderr.size() > MAX_STDERR_LINES) {
                        recentStderr.remove(0);
                    }
                }
            }
            catch (IOException e) {
                LOG.log(Level.FINE, "opencode stderr drainer ended", e);
            }
        }, "opencode-stderr");
        t.setDaemon(true);
        t.start();
    }

    private void handleProcessExit(Process dead) {
        boolean suppress;
        synchronized (this) {
            if (currentProcess != dead) {
                return; // stale exit from a superseded process
            }
            processing = false;
            currentProcess = null;
            suppress = cancelledByUser;
        }
        int code = dead.exitValue();
        if (!suppress && code != 0) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.EXITED,
                    StatusMessageUtil.formatExited("OpenCode", code, new ArrayList<>(recentStderr))));
        }
    }

    private void onHandlerDisconnected() {
        // handleProcessExit handles exit reporting; this is defensive cleanup only.
        synchronized (this) {
            processing = false;
        }
    }

    @Override
    public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        if (pendingDiff || !running || processing) {
            return;
        }
        cancelledByUser = false;

        if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
            sessionWorkingDir = workingDir;
        }
        File effectiveWorkDir = sessionWorkingDir != null ? sessionWorkingDir : workingDir;

        if (connection == null) {
            // spawnAndHandshake blocks for up to 60 s; sendPrompt runs on the EDT.
            // Hand off to a background thread and return immediately so the UI stays responsive.
            // processing=true prevents a second submit from racing the handshake.
            processing = true;
            final File wd = effectiveWorkDir;
            new Thread(() -> handshakeAndSend(text, wd, projectDirs), "opencode-handshake").start();
            return;
        }

        sendTurn(text);
    }

    /**
     * Background-thread entry point when no ACP connection exists yet. Calls
     * {@link #spawnAndHandshake} (which blocks up to 60 s), then re-enters
     * {@link #sendPrompt} once the connection is live. Runs entirely outside
     * the instance monitor during the blocking wait.
     */
    private void handshakeAndSend(String text, File workDir, List<File> projectDirs) {
        try {
            spawnAndHandshake(workDir);
        }
        catch (Exception e) {
            synchronized (this) {
                processing = false;
            }
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatSendFailed(e.getMessage())));
            return;
        }
        // Reset processing so the re-entry into sendPrompt can rearm it.
        synchronized (this) {
            processing = false;
            if (!running) {
                return; // stop() was called while we were handshaking
            }
        }
        // Connection is now established; re-enter the normal send path.
        sendPrompt(text, workDir, projectDirs);
    }

    private synchronized void sendTurn(String text) {
        JsonObject promptItem = new JsonObject();
        promptItem.addProperty("type", "text");
        promptItem.addProperty("text", text);
        JsonArray promptArray = new JsonArray();
        promptArray.add(promptItem);

        JsonObject params = new JsonObject();
        params.addProperty("sessionId", acpSessionId);
        params.add("prompt", promptArray);

        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "opencode prompt [{0}]: {1}", new Object[]{acpSessionId, text});
        }
        processing = true;
        CompletableFuture<JsonObject> promptFuture = connection.sendRequest(AcpMethodEnum.SESSION_PROMPT, params);
        promptFuture
                .thenAccept(this::handleTurnComplete)
                .exceptionally(ex -> {
                    handleTurnError(ex);
                    return null;
                });
    }

    void handleTurnComplete(JsonObject result) {
        boolean wasRunning;
        synchronized (this) {
            processing = false;
            wasRunning = running;
            cancelledByUser = false;
        }
        if (wasRunning) {
            listener.onAiProcessEvent(new TurnCompleteEvent());
        }
    }

    void handleTurnError(Throwable ex) {
        Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
        synchronized (this) {
            processing = false;
            cancelledByUser = false;
        }
        if (cause instanceof AcpException) {
            AcpException ae = (AcpException) cause;
            if (ae.code() == AcpErrorCodeEnum.REQUEST_CANCELLED.code()) {
                // -32800: session/cancel was acknowledged; treat as normal cancel completion
                listener.onAiProcessEvent(new TurnCompleteEvent());
                return;
            }
            if (ae.code() == AcpErrorCodeEnum.AUTH_REQUIRED.code()) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        "Run `opencode auth login` in the terminal"));
                return;
            }
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                StatusMessageUtil.formatSendFailed(cause != null ? cause.getMessage() : ex.getMessage())));
    }

    @Override
    public void interrupt(InterruptTypeEnum type) {
        if (type != InterruptTypeEnum.Cancel) {
            // Mail is unimplemented here — this drop was previously silent, the same
            // shape as the bug just fixed in CodexAiProcessManager. Logged rather than
            // fixed: implementing ACP mid-turn steering (if OpenCode's protocol even
            // supports it) is out of scope for this task, which is Codex-specific;
            // reported to the Planner separately as a likely second instance.
            if (type == InterruptTypeEnum.Mail && PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO,
                        "OpenCode interrupt: Mail IGNORED (unimplemented) — message will arrive via normal inbox "
                        + "flush (session={0})", acpSessionId);
            }
            return;
        }
        AcpConnection conn;
        String sid;
        OpenCodeAcpClientHandler h;
        synchronized (this) {
            if (!processing) {
                // Worth recording: "I pressed Stop and nothing happened" and "I
                // pressed Stop and it kept talking" look identical afterwards, and
                // this branch is the first of those.
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "OpenCode interrupt: IGNORED, no turn in flight (session={0})", acpSessionId);
                }
                return;
            }
            cancelledByUser = true;
            processing = false;
            conn = connection;
            sid = acpSessionId;
            h = activeHandler;
        }
        // session/cancel is a notification, so OpenCode winds the turn down at its
        // own pace and updates can still arrive afterwards. Stamping the moment the
        // user actually pressed Stop is the only way to measure that tail: without
        // it, "it carried on after I stopped it" cannot be told apart from a normal
        // wind-down, and the agent's own log gives no click time to compare against.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenCode interrupt: user pressed Stop, cancelling turn (session={0}, connected={1})",
                    new Object[]{sid, conn != null});
        }
        if (h != null) {
            h.cancelPendingPermissions();
        }
        if (conn != null && sid != null) {
            JsonObject params = new JsonObject();
            params.addProperty("sessionId", sid);
            conn.sendNotification(AcpMethodEnum.SESSION_CANCEL, params);
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "OpenCode interrupt: session/cancel sent (session={0})", sid);
            }
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, StatusMessageUtil.formatStopped()));
    }

    @Override
    public synchronized void stop() {
        // Logged before the state is torn down, so the record says what was actually
        // in flight at the moment of the stop rather than the cleared-out aftermath.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenCode stop: shutting session down (session={0}, turnInFlight={1}, connected={2}, processAlive={3})",
                    new Object[]{acpSessionId, processing, connection != null,
                        currentProcess != null && currentProcess.isAlive()});
        }
        running = false;
        processing = false;
        cancelledByUser = true;

        OpenCodeAcpClientHandler h = activeHandler;
        activeHandler = null;
        AcpConnection conn = connection;
        connection = null;
        String sid = acpSessionId;
        acpSessionId = null;
        pendingAcpResumeId = null;
        Process proc = currentProcess;
        currentProcess = null;

        if (h != null) {
            h.cancelPendingPermissions();
        }
        if (conn != null && sid != null) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("sessionId", sid);
                conn.sendRequest(AcpMethodEnum.SESSION_CLOSE, params).get(5, TimeUnit.SECONDS);
            }
            catch (Exception e) {
                LOG.log(Level.FINE, "session/close timed out or failed during stop", e);
            }
            conn.close();
        }
        if (proc != null) {
            proc.destroy();
        }

        OpenCodeAiSession sess = openCodeAiSession;
        openCodeAiSession = null;
        OpenCodeAiMcpRegistrar reg = registrar;
        registrar = null;

        if (sess != null) {
            sess.dispose();
        }
        if (reg != null) {
            McpServerRegistry.deregister(reg);
        }

        sessionId = null;
        sessionWorkingDir = null;
        pendingDiff = false;
        sessionConfigOptions = null;
        recentStderr.clear();
    }

    @Override
    public void resumeSession(String existingSessionId) {
        if (existingSessionId == null || existingSessionId.isBlank()) {
            return;
        }
        if (!existingSessionId.startsWith("ses_")) {
            // Guard against plugin-level UUIDs; OpenCode session ids always start with ses_.
            // AiTopComponent.loadHistory() passes the plugin UUID here — the override in
            // OpenCodeAiImplementation.resumeSession() is the primary defence, but this
            // ensures no stray caller can ever poison the pending resume slot.
            LOG.log(Level.FINE, "resumeSession: ignoring non-ACP id ''{0}''", existingSessionId);
            return;
        }
        pendingAcpResumeId = existingSessionId;
    }

    public synchronized boolean isSessionLive() {
        return acpSessionId != null;
    }

    @Override
    public boolean isMcpActive() {
        return registrar != null;
    }

    /**
     * The configOptions array captured from the session/new response (design
     * doc §13). Null before the ACP handshake completes.
     */
    public JsonArray configOptions() {
        return sessionConfigOptions;
    }

    /**
     * Changes one session config option (model, effort, or mode — the only
     * three ids OpenCode accepts; anything else fails server-side with
     * InvalidConfigOptionError). Completes with the COMPLETE configOptions
     * snapshot from the response, not just the changed entry — options are
     * interdependent (e.g. effort depends on the selected model), and no
     * config_option_update notification is sent for this change, so the
     * response is the only source of truth (design doc §13).
     */
    public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
        AcpConnection conn = connection;
        String sid = acpSessionId;
        if (conn == null || sid == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("OpenCode session is not active"));
        }
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sid);
        params.addProperty("configId", configId);
        params.addProperty("value", value);
        return conn.sendRequest(AcpMethodEnum.SESSION_SET_CONFIG_OPTION, params)
                .thenApply(result -> {
                    JsonArray options = result != null && result.has("configOptions")
                            && result.get("configOptions").isJsonArray()
                            ? result.getAsJsonArray("configOptions")
                            : new JsonArray();
                    sessionConfigOptions = options;
                    return options;
                });
    }
}

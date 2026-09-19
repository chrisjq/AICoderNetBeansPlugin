package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.JsonUtils;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Manages Claude via ONE long-lived {@code claude --input-format stream-json} process per plugin session (see
 * {@link ClaudePersistentSession}). The process is launched lazily on the first turn and kept alive across turns. On an
 * unexpected process exit (e.g. credit exhausted) the turn is unwedged and the exit surfaced, but the process is NOT
 * auto-restarted — the next user message relaunches it via {@code --resume}.
 */
public class ClaudeAiProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(ClaudeAiProcessManager.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_STDERR_LINES = 100;
    /**
     * Master switch for logging {@code input_json_delta} tool-input fragments to "ai json". Default OFF.
     *
     * <p>
     * Why off: a fragment cannot be redacted — a secret's characters can straddle two separate chunks, and no per-line
     * redactor can ever see it whole. This is demonstrated, not theoretical: a live-captured log line contains {@code "partial_json":"{\"sessionId\": \"b154400c-bbb"} — a
     * 36-character UUID cut off after 12. Individually these fragments are
     * near-unreadable anyway, and the ASSEMBLED tool input is already logged
     * in the following {@code assistant} event (see
     * {@link ClaudeStreamJsonParser}), which is the version anyone would
     * actually want to read.
     *
     * <p>
     * Flip to {@code true} only when debugging a tool input that never
     * assembles — a stream dying mid-block, or the CLI truncating — where the
     * fragments are the only evidence left. Everything else in the stream
     * keeps logging regardless of this flag: {@code message_start} (token
     * accounting), {@code message_stop}/{@code message_delta} (stop reasons),
     * {@code content_block_start}/{@code stop}, and {@code content_block_delta}
     * carrying {@code text_delta} (the assistant's own live text) — see
     * {@link #isInputJsonDeltaFragment}, which this flag gates.
     */
    static final boolean LOG_INPUT_JSON_DELTA_FRAGMENTS = false;

    private static JsonObject buildInterruptRequest() {
        JsonObject interrupt = new JsonObject();
        interrupt.addProperty(ClaudeJsonKeyEnum.TYPE.key(), "control_request");
        interrupt.addProperty(ClaudeJsonKeyEnum.REQUEST_ID.key(), "req_interrupt");
        JsonObject request = new JsonObject();
        request.addProperty(ClaudeJsonKeyEnum.SUBTYPE.key(), "interrupt");
        interrupt.add(ClaudeJsonKeyEnum.REQUEST.key(), request);
        return interrupt;
    }

    /**
     * True iff {@code line} is a {@code stream_event} wrapping a {@code content_block_delta} whose {@code delta.type}
     * is {@code input_json_delta} — a tool-input fragment. Confirmed against a live-captured log line (see
     * {@link #LOG_INPUT_JSON_DELTA_FRAGMENTS}); the other {@code content_block_delta} carrier, {@code text_delta} (the
     * assistant's own live-typing text), deliberately does NOT match this predicate, nor do
     * {@code content_block_start/stop}, {@code message_start} (full usage/token accounting), {@code message_delta}, or
     * {@code message_stop} (stop reason) — none of those carry tool arguments, and message_start especially is
     * genuinely useful for debugging. Parses defensively: anything unparseable or not matching returns false, so the
     * failure mode is "logs too much" rather than "silently swallows real content". Detection is unconditional —
     * {@link #LOG_INPUT_JSON_DELTA_FRAGMENTS} only gates whether a detected fragment is then logged, not whether it is
     * detected, so this method's tests do not depend on the flag.
     */
    static boolean isInputJsonDeltaFragment(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            if (!"stream_event".equals(JsonUtils.getString(obj, ClaudeJsonKeyEnum.TYPE.key()))) {
                return false;
            }
            JsonObject event = obj.getAsJsonObject(ClaudeJsonKeyEnum.EVENT.key());
            if (event == null || !"content_block_delta".equals(JsonUtils.getString(event, ClaudeJsonKeyEnum.TYPE.key()))) {
                return false;
            }
            JsonObject delta = event.getAsJsonObject(ClaudeJsonKeyEnum.DELTA.key());
            return delta != null && "input_json_delta".equals(JsonUtils.getString(delta, ClaudeJsonKeyEnum.TYPE.key()));
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Number of {@code tool_use} content blocks in an {@code assistant} stream-json line — usually 0 or 1, but Claude
     * can request more than one tool in the same turn (parallel tool calls), so this counts rather than flags. Used by
     * {@link #trackToolCallLifecycle} to hold a Mail interrupt while a call is in flight (#9 / F5). Parses defensively:
     * 0 for anything unparseable, the wrong event type, or with no content array — the failure mode is "an interrupt is
     * held a little longer than strictly necessary", never a crash on a malformed line.
     */
    static int countToolUseStarts(String line) {
        return countContentBlocksOfType(line, "assistant", "tool_use");
    }

    /**
     * Number of {@code tool_result} content blocks in a {@code user} stream-json line — the CLI's own echo of a tool
     * call's result being fed back into the model. This is the normal way {@link #inFlightToolCalls} returns to zero;
     * {@link #isTurnEndLine} is the backstop for a turn that ends without one.
     */
    static int countToolResults(String line) {
        return countContentBlocksOfType(line, "user", "tool_result");
    }

    private static int countContentBlocksOfType(String line, String eventType, String blockType) {
        if (line == null || line.isBlank()) {
            return 0;
        }
        try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            if (!eventType.equals(JsonUtils.getString(obj, ClaudeJsonKeyEnum.TYPE.key()))) {
                return 0;
            }
            JsonObject msg = obj.has(ClaudeJsonKeyEnum.MESSAGE.key()) ? obj.getAsJsonObject(ClaudeJsonKeyEnum.MESSAGE.key()) : null;
            JsonArray content = msg != null && msg.has(ClaudeJsonKeyEnum.CONTENT.key())
                                ? msg.getAsJsonArray(ClaudeJsonKeyEnum.CONTENT.key()) : null;
            if (content == null) {
                return 0;
            }
            int count = 0;
            for (JsonElement el : content) {
                if (el.isJsonObject() && blockType.equals(JsonUtils.getString(el.getAsJsonObject(), ClaudeJsonKeyEnum.TYPE.key()))) {
                    count++;
                }
            }
            return count;
        }
        catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * True iff {@code line} is a {@code result} stream-json event — the whole turn ending. A safety net alongside
     * {@link #countToolResults}: if a turn somehow ends without an explicit tool_result for every tool_use (an error
     * path, a cancelled call), {@link #trackToolCallLifecycle} resets the in-flight count to zero here rather than
     * leaving it stuck positive, which would hold a Mail interrupt forever.
     */
    static boolean isTurnEndLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            return "result".equals(JsonUtils.getString(obj, ClaudeJsonKeyEnum.TYPE.key()));
        }
        catch (RuntimeException e) {
            return false;
        }
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
    // The model the live session was actually launched under, which is not always the model field: a switch
    // requested mid-turn updates the field immediately but is deferred by recycleForModelChange(). Compared in
    // ensureSession so the deferred switch is picked up at the start of the next turn. Without it the guard in
    // recycleForModelChange() would strand the new model — --resume makes the CLI keep the session's original
    // model, so the command-line --model on a resumed session is ignored and the switch never takes effect.
    private String launchedModel;
    /**
     * Launch-time effort level for the next spawn, from {@code ClaudeSessionSettings.effort()} (session) or
     * {@code ClaudePluginSettings.getEffort()} (global default) — set by {@code ClaudeAiImplementation} before the
     * first turn. Compared in {@link #ensureSession} and deferred by {@link #recycleForModelChange} exactly like
     * {@link #launchedModel}.
     */
    private volatile String configuredEffort;
    /**
     * The only effort levels the Claude CLI accepts (design spec §3; also the info bar's {@code EFFORT_OPTIONS}). No
     * live discovery exists for these — unlike Copilot, which validates against the model's supported list, Claude has
     * a fixed five-level set. Anything outside this set is treated as a corrupted or hand-edited value and dropped by
     * {@link #configureEffort} rather than passed to {@code --effort}, which would hard-fail the CLI at spawn.
     */
    private static final Set<String> KNOWN_EFFORT_LEVELS = Set.of("low", "medium", "high", "xhigh", "max");
    private String launchedEffort;
    private int launchCount = 0;

    int cancelWatchdogMillis = 5000;

    /**
     * Count of tool_use blocks seen (stream-json {@code assistant} events) not yet matched by a tool_result
     * ({@code user} event) or turn end ({@code result} event) — see {@link #trackToolCallLifecycle}. Tracked so a Mail
     * interrupt can be HELD while a tool call this plugin is itself servicing over the MCP HTTP endpoint is still in
     * flight, rather than the CLI's {@code control_request(interrupt)} aborting it mid-flight (#9 / F5). Guarded by
     * {@code this}, same as {@link #processing}/{@link #turnInterrupted}.
     */
    private int inFlightToolCalls = 0;

    /**
     * True when a Mail interrupt was requested while {@link #inFlightToolCalls} was &gt; 0 and is waiting to be sent —
     * either by {@link #trackToolCallLifecycle} as soon as the count returns to zero, or by
     * {@link #startMailInterruptSafetyValve} if it never does. Guarded by {@code this}.
     */
    private boolean pendingMailInterrupt = false;

    /**
     * Test seam for {@link #startMailInterruptSafetyValve}'s wait — see {@link #cancelWatchdogMillis}'s identical
     * purpose for Cancel. Production default sourced from {@link ClaudeTimeoutEnum#MAIL_INTERRUPT_HOLD_MILLIS}.
     */
    int mailInterruptSafetyValveMillis = (int) ClaudeTimeoutEnum.MAIL_INTERRUPT_HOLD_MILLIS.millis();

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

    synchronized int getInFlightToolCalls() {
        return inFlightToolCalls;
    }

    synchronized boolean isMailInterruptPending() {
        return pendingMailInterrupt;
    }

    /**
     * Configures the effort level for the next spawn (and only the next spawn — the level is fixed at launch time and a
     * change after that goes through {@link #recycleForModelChange}, which drops the process so {@link #ensureSession}
     * relaunches on the next turn). An empty or null level means "omit {@code --effort}" (Claude's own default). A
     * value that is not one of the five known levels is dropped the same way, with exactly one INFO event — so a
     * corrupted or hand-edited stored value can never break the session at spawn.
     */
    public void configureEffort(String level) {
        if (level == null || level.isBlank()) {
            this.configuredEffort = null;
            return;
        }
        if (!KNOWN_EFFORT_LEVELS.contains(level)) {
            this.configuredEffort = null;
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                                      "Effort level \"" + level + "\" is not a known Claude effort level; using Claude's default"));
            return;
        }
        this.configuredEffort = level;
    }

    int getLaunchCount() {
        return launchCount;
    }

    /**
     * Package-visible test seam (mirrors {@link #getLaunchCount}). The launch itself is untestable in unit tests (the
     * CLI spawn is environment-dependent), but {@link #configureEffort}'s drop-and-INFO behavior for an unknown level
     * is. Sentinel {@code null} means "omit {@code --effort}" — which is exactly what an invalid level must resolve to.
     */
    String getConfiguredEffort() {
        return configuredEffort;
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
            mcpReady = McpServerRegistry.register(reg).get(TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
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
        if (configuredEffort != null && !configuredEffort.isBlank()) {
            // --effort IS honoured on a resumed launch, the opposite of --model (see the --model note above): effort
            // is taken from the current invocation, not the session (claude-code issue #66005). It also preserves the
            // cached prompt prefix — that issue is about sessions silently LOSING their effort on resume, which
            // invalidates the cache (~29% reuse in the reporter's repro). Omitting the flag on resumes would regress
            // both; never "optimise" this line away. Full rationale in the design spec §3.
            args.add("--effort");
            args.add(configuredEffort);
        }
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
            if (launchedProjectDirs.containsAll(currentDirs) && Objects.equals(launchedModel, model) && Objects.equals(launchedEffort, configuredEffort)) {
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
                return server != null && pid != null && server.isFileAllowed(pid, path);
            });
        }
        parser = p;
        recentStderr.clear();
        final String sid = sessionId;
        ClaudePersistentSession launched = launchPersistentSession(cmd, workDir,
                                                                   line -> {
                                                                       // input_json_delta fragments are excluded by default (not just redacted),
                                                                       // behind LOG_INPUT_JSON_DELTA_FRAGMENTS — see that flag's javadoc for the
                                                                       // full trade-off (a fragment cannot be made safe, not that it's
                                                                       // uninteresting; the assembled tool_use block IS visible, fully redacted,
                                                                       // in the following "assistant" event). text_delta fragments (assistant's
                                                                       // own live-typing text) share the same event.delta nesting and are never
                                                                       // excluded — only input_json_delta is, and only while the flag is off.
                                                                       if (PluginSettings.isDebugJson()
                                                                       && (LOG_INPUT_JSON_DELTA_FRAGMENTS || !isInputJsonDeltaFragment(line))) {
                                                                           LOG.log(Level.INFO, "ai json [{0}]: {1}",
                                                                                   new Object[]{sid, McpHookServerUtil.redactAllSecrets(line)});
                                                                       }
                                                                       trackToolCallLifecycle(line);
                                                                       p.parseLine(line);
                                                                   },
                                                                   err -> {
                                                                       // Claude's stream-json protocol runs on stdout, not stderr, so a
                                                                       // tool_use block's secretKey argument should never appear here — but
                                                                       // redacting anyway costs nothing and removes the risk if the CLI ever
                                                                       // echoes malformed/offending input to stderr on a parse failure.
                                                                       if (PluginSettings.isDebugJson()) {
                                                                           LOG.log(Level.WARNING, "claude stderr [{0}]: {1}",
                                                                                   new Object[]{sid, McpHookServerUtil.redactAllSecrets(err)});
                                                                       }
                                                                       addStderr(err);
                                                                   });
        persistentSession = launched;
        launchedProjectDirs = currentDirs;
        launchedModel = model;
        launchedEffort = configuredEffort;
        launchCount++;
        launched.process().onExit().thenRun(() -> handleProcessExit(launched));
        firstMessage = false;
        return persistentSession;
    }

    /**
     * Called when the persistent process exits. Unwedges the current turn and surfaces the exit, but does NOT
     * auto-restart: running stays true so the user's next message relaunches the process via ensureSession (--resume).
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
            inFlightToolCalls = 0;
            pendingMailInterrupt = false;
            suppress = cancelledByUser || turnInterrupted;
        }
        int code = dead.process().exitValue();
        if (!suppress && code != 0) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.EXITED,
                                                      StatusMessageUtil.formatExited("AI", code, new ArrayList<>(recentStderr))));
        }
    }

    /**
     * Updates {@link #inFlightToolCalls} from one raw stream-json line and flushes a HELD Mail interrupt
     * ({@link #pendingMailInterrupt}) as soon as the count returns to zero — before whatever line comes next, which is
     * what keeps the interrupt from landing mid-tool-call (#9 / F5). Called for every line, so the common case (none of
     * the three predicates match) must stay cheap; each predicate parses defensively and returns 0/false rather than
     * throwing.
     */
    private void trackToolCallLifecycle(String line) {
        int starts = countToolUseStarts(line);
        int results = countToolResults(line);
        boolean turnEnd = isTurnEndLine(line);
        if (starts == 0 && results == 0 && !turnEnd) {
            return;
        }
        boolean flush;
        ClaudePersistentSession s;
        synchronized (this) {
            if (turnEnd) {
                // Deliberately clear without sending — do not "fix" this into an always-send.
                // AiSessionInboxBroker.deliverIncomingMessage already ran unconditionally, before this interrupt was
                // ever considered, so the mail's visibility never depended on control_request being sent. And on a
                // `result` line there is no remaining generation left in THIS turn to steer, so sending here could
                // only affect the next turn — no different from leaving it in context for that turn to pick up
                // naturally. Whether the SENDER gets any signal that their message landed mid-turn vs. merely
                // in-inbox is a real gap, but a pre-existing one shared by every Mail interrupt, not something this
                // branch creates — that belongs to #6 / F4 (reply/delivery tracking), not here.
                inFlightToolCalls = 0;
                pendingMailInterrupt = false;
                return;
            }
            inFlightToolCalls = Math.max(0, inFlightToolCalls + starts - results);
            flush = pendingMailInterrupt && inFlightToolCalls == 0;
            if (flush) {
                pendingMailInterrupt = false;
                turnInterrupted = true;
            }
            s = persistentSession;
        }
        if (flush && s != null) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO,
                        "Claude interrupt: Mail flushed — in-flight tool call completed (session={0})", sessionId);
            }
            s.sendRawLine(GSON.toJson(buildInterruptRequest()));
        }
    }

    /**
     * Backstop for a HELD Mail interrupt (#9 / F5): if {@link #inFlightToolCalls} never returns to zero — a
     * lost/malformed tool_result line, or the CLI itself hanging — {@link #trackToolCallLifecycle} would otherwise
     * never flush it, and the interrupt would wait forever. Delivers it anyway after
     * {@link #mailInterruptSafetyValveMillis}, exactly like {@link #startCancelWatchdog}'s identical pattern for
     * Cancel. Guards on {@code persistentSession == s} (the session captured when the hold began) so a watchdog from an
     * earlier, already-resolved hold can never fire against a later, unrelated one.
     */
    private void startMailInterruptSafetyValve(ClaudePersistentSession s) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(mailInterruptSafetyValveMillis);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            boolean flush;
            synchronized (ClaudeAiProcessManager.this) {
                flush = pendingMailInterrupt && persistentSession == s;
                if (flush) {
                    pendingMailInterrupt = false;
                    turnInterrupted = true;
                    inFlightToolCalls = 0;
                }
            }
            if (flush) {
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO,
                            "Claude interrupt: Mail safety valve fired after {0}ms — tool call still in flight, "
                            + "delivering anyway (session={1})",
                            new Object[]{mailInterruptSafetyValveMillis, sessionId});
                }
                s.sendRawLine(GSON.toJson(buildInterruptRequest()));
            }
        }, "ai-mail-interrupt-safety-valve");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    @Override
    public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        if (pendingDiff || !running || processing || awaitingCancelResult) {
            return;
        }
        cancelledByUser = false;
        turnInterrupted = false;
        inFlightToolCalls = 0;
        pendingMailInterrupt = false;

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
        // Deliberately NOT gated on the session being alive. The transport's liveness must never decide whether the
        // user is told: the UI re-enables its input on STOPPED alone, so returning early here — as this method used
        // to for a null session — leaves Stop an inert button and the chat permanently unusable. Codex, OpenCode,
        // Grok and Ollama all gate on `processing` and treat the transport as optional; this now matches them.
        ClaudePersistentSession s;
        switch (type) {
            case Mail -> {
                boolean hold;
                synchronized (this) {
                    s = persistentSession;
                    // Mail genuinely does need a live session — there is nothing to inject into otherwise — and it
                    // has somewhere safe to land: the message stays queued and arrives on the next inbox flush.
                    if (s == null || !processing) {
                        if (PluginSettings.isDebugJson()) {
                            LOG.log(Level.INFO,
                                    "Claude interrupt: Mail IGNORED (session={0}, sessionAlive={1}, turnInFlight={2})"
                                    + " — message will arrive via normal inbox flush",
                                    new Object[]{sessionId, s != null, processing});
                        }
                        return;
                    }
                    // #9 / F5: sending control_request(interrupt) while a tool call is in flight cancels the call
                    // itself — the CLI treats an interrupt as "the user doesn't want to proceed" and aborts
                    // whatever it's waiting on, including a tool call this plugin is servicing over its own MCP
                    // HTTP endpoint. Hold it instead; trackToolCallLifecycle flushes it as soon as the in-flight
                    // count returns to zero, and startMailInterruptSafetyValve is the backstop if that never
                    // happens. A second Mail interrupt arriving while one is already held is a no-op here — one
                    // pending flag and one watchdog cover any number of queued messages, since they all resolve to
                    // the same single control_request.
                    hold = inFlightToolCalls > 0;
                    if (hold) {
                        boolean alreadyPending = pendingMailInterrupt;
                        pendingMailInterrupt = true;
                        if (PluginSettings.isDebugJson()) {
                            LOG.log(Level.INFO,
                                    "Claude interrupt: Mail HELD — tool call in flight (session={0}, inFlight={1})",
                                    new Object[]{sessionId, inFlightToolCalls});
                        }
                        if (!alreadyPending) {
                            startMailInterruptSafetyValve(s);
                        }
                    }
                    else {
                        turnInterrupted = true;
                    }
                }
                if (!hold) {
                    synchronized (this) {
                        if (persistentSession != s) {
                            return;
                        }
                    }
                    s.sendRawLine(GSON.toJson(buildInterruptRequest()));
                }
            }
            case Cancel -> {
                synchronized (this) {
                    s = persistentSession;
                    if (!processing) {
                        if (PluginSettings.isDebugJson()) {
                            LOG.log(Level.INFO, "Claude interrupt: IGNORED, no turn in flight (session={0})", sessionId);
                        }
                        return;
                    }
                    cancelledByUser = true;
                    processing = false;
                    // Only await a result that can actually arrive. The watchdog clears this flag by comparing
                    // against the session it captured, so setting it with no session means the comparison never
                    // matches, the flag is never cleared, and sendPrompt — which gates on it — refuses every
                    // subsequent message. That would trade a stuck turn for a permanently unusable session.
                    awaitingCancelResult = s != null;
                }
                // Stamping the moment the user actually pressed Stop is the only way
                // to measure the wind-down tail afterwards: without it, "it carried
                // on after I stopped it" cannot be told apart from a normal
                // wind-down, and the agent's own log gives no click time to compare
                // against.
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "Claude interrupt: user pressed Stop, cancelling turn (session={0}, connected={1})",
                            new Object[]{sessionId, s != null});
                }
                if (s != null) {
                    s.sendRawLine(GSON.toJson(buildInterruptRequest()));
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.INFO, "Claude interrupt: control_request(interrupt) sent (session={0})", sessionId);
                    }
                }
                // Fired whether or not anything could be sent — see the note at the top of this method.
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, StatusMessageUtil.formatStopped()));
                if (s != null) {
                    startCancelWatchdog(s);
                }
            }
        }
    }

    /**
     * Force-closes the session if the CLI never answers the interrupt. Started only when an interrupt was actually
     * sent: with no session there is no reply to wait for, and the capture-compare below could never match.
     */
    private void startCancelWatchdog(ClaudePersistentSession s) {
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
        inFlightToolCalls = 0;
        pendingMailInterrupt = false;

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
        launchedModel = null;
        launchedEffort = null;
        launchCount = 0;
    }

    /**
     * Drops the CLI session so the next turn relaunches under the newly selected model.
     *
     * <p>
     * Refuses while a turn is in flight. Without this guard the method nulls {@code persistentSession} and kills the
     * process while {@code processing} stays true — and it does so silently, clearing no state and firing no event.
     * {@link #interrupt} checks the session before it checks {@code processing}, so from that moment Stop is a no-op
     * and the chat input never re-enables; the user's only escape is closing the tab. GithubCopilotProcessManager has
     * always carried this guard. Deferring costs nothing: {@link #ensureSession} compares {@code launchedModel} against
     * the current model and relaunches at the start of the next turn, where no turn can be orphaned.
     */
    public synchronized void recycleForModelChange() {
        if (!running || processing) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "Claude recycleForModelChange: DEFERRED to next turn "
                        + "(running={0}, turnInFlight={1}, session={2})",
                        new Object[]{running, processing, sessionId});
            }
            return;
        }
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

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.BackendExecutors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog.BackendDebugLogWriter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog.DebugDirectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog.DebugLogBudget;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.SystemNotificationEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnEndReason;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.TokenEstimator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.OllamaMcpRegistrar;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.BaseUrls;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ClientResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.CompatibilityFlags;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ErrorMapper;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.MappedError;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.OpenAiCompatibleHttpClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.OpenAiCompatibleHttpClient.DebugSink;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.RequestHandle;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.ContextBudget;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.ContextBudgetSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.ResolvedCompactionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.Summariser;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedFacts;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedSnapshot;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedSnapshotSource;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedSnapshotSources;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.PollerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerKind;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.SessionDecisions;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowLadder;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowSourceText;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.events.OpenAiCompatibleContextUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders.ExtraHeader;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SessionSecrets;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.MemoryPromptSection;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.SessionMemoryFolders;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.SessionMemoryNotifier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.SessionMemoryStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools.ToolCallModeResolver;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools.ToolSchemaSanitiser;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.ChatClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.SamplingOptions;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.ToolCallingState;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.ToolRunner;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.TurnContext;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.TurnLoop;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.TurnOutcome;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.HintState;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.OpenAiCompatibleInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpInstructionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.HiddenToolTextFilter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolAccess;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Process manager of the OpenAI Compatible backend (spec §4, §7): each prompt runs one {@link TurnLoop} on a daemon
 * turn thread whose {@code finally} is the turn's single exit — it alone reports the turn's {@link TurnEndReason}.
 * Cancel and {@link #stop()} only claim CANCELLED, cancel the request handle and interrupt the thread; they never wait,
 * so both are safe on the EDT.
 */
public final class OpenAiCompatibleAiProcessManager extends AiProcessManager implements DetectedSnapshotSource {

    private static final Logger LOG = Logger.getLogger(OpenAiCompatibleAiProcessManager.class.getName());

    private static final long STATUS_TICK_MILLIS = 1000L;

    /**
     * The file a session's saved context is written to.
     */
    static final String CONTEXT_FILE_NAME = "context.json";

    @FunctionalInterface
    interface ContextDirResolver {

        Path resolve(String sessionId) throws IOException;
    }

    /**
     * Builds the HTTP client for a start; tests substitute a fake.
     */
    private final Function<OpenAiCompatibleLimits, ChatClient> clientFactory;
    /**
     * Builds the session's secrets holder from (session id, API key set); tests substitute one without the Keyring.
     */
    private final BiFunction<String, Boolean, SessionSecrets> secretsFactory;
    private final OpenAiCompatibleLimits baseLimits;

    /**
     * The model-facing conversation, used by the one turn thread that runs at a time. Replaced (not cleared) on each
     * start, so a turn still unwinding from before a restart keeps its own list.
     */
    private volatile List<ChatMessage> history = new ArrayList<>();
    /**
     * Last safe estimate/report for poller callbacks; history itself belongs to the turn thread.
     */
    private volatile int lastContextUsed;
    private volatile String detectionSettingsSignature = "";
    private volatile Consumer<OpenAiCompatibleSessionSettings> settingsPersister = settings -> {
    };

    private volatile OllamaMcpRegistrar registrar;
    private volatile ChatClient client;
    private volatile SessionSecrets secrets;
    /**
     * Where a session's saved context lives; tests substitute a temporary folder.
     */
    private volatile ContextDirResolver contextDirResolver = SessionMemoryFolders::sessionDir;
    private volatile ExecutorService contextIo = newContextIo();

    private static ExecutorService newContextIo() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "openai-compatible-context-io");
            thread.setDaemon(true);
            return thread;
        });
    }
    /**
     * True once this start has tried to restore the saved context (or found it switched off).
     */
    private volatile boolean contextRestored;
    private volatile boolean contextIgnoredLogged;
    /**
     * The key hint a request result called for (rejected, needed); lifted by the next successful request.
     */
    private volatile String keyResultHint;
    private volatile String keyResultTooltip;
    /**
     * The text of the key hint currently shown, so a late-attached info bar can show it too.
     */
    private volatile String shownKeyHint;
    private volatile boolean detectionHintShown;
    private volatile String shownKeyTooltip;
    private volatile Boolean lastReachable;
    private final List<Runnable> snapshotListeners = new CopyOnWriteArrayList<>();
    private volatile Thread activeTurnThread;
    private volatile RequestHandle activeHandle;
    private volatile TurnOutcome activeOutcome;
    private volatile boolean pendingMailNotice;
    /**
     * True once this session showed the "no tool-round limit" note.
     */
    private volatile boolean uncappedHostedNoted;

    /**
     * The session's native-failure count and sticky schema switch; replaced on each start.
     */
    private volatile ToolCallingState toolCallingState = new ToolCallingState();
    /**
     * Session decisions (spec §3.5): the compatibility flags and the window learned from an overflow error. Replaced on
     * each start.
     */
    private volatile SessionDecisions decisions = new SessionDecisions("");
    private final PollerRegistry pollerRegistry;
    private volatile boolean pollerJoined;
    private volatile String pollerJoinKeyHash = "";
    private volatile boolean stopped;
    /**
     * The session's context management (compaction, overflow, overhead and truncation checks); replaced on each start.
     */
    private volatile TurnContext turnContext = newTurnContext(decisions, TurnContext.HintSink.NONE);
    private volatile OpenAiCompatibleInfoBarExtension infoBar;
    private volatile DetectedFacts lastDetectedFacts;
    /**
     * True while a manual Compact runs; a prompt is refused meanwhile (spec §6.5).
     */
    private volatile boolean compacting;
    private final AtomicBoolean compactQueued = new AtomicBoolean();
    private volatile long compactStartNanos;
    /**
     * Cancels a manual compaction's summary request (Stop).
     */
    private volatile RequestHandle compactHandle;

    static final String COMPACT_REFUSED_DURING_TURN = "Compaction waits until the current reply finishes";
    static final String PROMPT_REFUSED_DURING_COMPACT = "Compaction is running — try again when it finishes.";
    static final String RESET_REFUSED_WHILE_BUSY = "Reset isn't available while a reply or compaction is running";
    static final String NOTHING_TO_COMPACT = "Nothing to compact: the context already fits the window";

    static final String UNCAPPED_HOSTED_MESSAGE = "No tool-round limit is set; set one in Advanced to limit cost.";
    static final String SWITCHED_TO_SCHEMA_MESSAGE
            = "Switched to schema tool calling; the model's earlier context was reset — your chat is kept.";
    /**
     * The context window assumed for the result cap when the session has no override (until detection supplies one).
     */
    static final int ASSUMED_CONTEXT_WINDOW = 8192;
    private volatile String pinnedIdentity;
    private volatile String pinnedBaseline;
    private volatile String pinnedInstructions;
    private volatile OpenAiCompatibleAiSession aiSession;
    private volatile OpenAiCompatibleMcpBridge bridge;
    private volatile ToolRunner toolRunner;
    private volatile TurnLoop activeLoop;
    private volatile int turnCounter;
    private volatile ToolRunner.Invoker invokerForTest;
    private volatile JsonArray toolsForTest;

    public OpenAiCompatibleAiProcessManager(AiProcessEventListener listener) {
        this(listener, limits -> ChatClient.of(new OpenAiCompatibleHttpClient(limits)), SessionSecrets::new,
             OpenAiCompatiblePluginSettings.fromPluginSettings());
        // How long plugin shutdown waits for the debug-log writers to drain (plugin-wide, spec §8.2).
        BackendDebugLogWriter.setShutdownDrainBound(
                Duration.ofSeconds(OpenAiCompatiblePluginSettings.getDebugLogDrainSeconds()));
    }

    /**
     * The debug log's queue, entry, file-size and rolled-file limits, from the plugin settings (spec §8.2).
     */
    @Override
    protected DebugLogBudget debugLogBudget() {
        return DebugLogBudget.of(OpenAiCompatiblePluginSettings.getDebugLogQueueBytes(),
                                 OpenAiCompatiblePluginSettings.getDebugLogQueueEntries(),
                                 OpenAiCompatiblePluginSettings.getDebugLogMaxEntryBytes(),
                                 OpenAiCompatiblePluginSettings.getDebugLogSizeCapBytes(),
                                 OpenAiCompatiblePluginSettings.getDebugLogRolledFiles());
    }

    OpenAiCompatibleAiProcessManager(AiProcessEventListener listener,
                                     Function<OpenAiCompatibleLimits, ChatClient> clientFactory,
                                     BiFunction<String, Boolean, SessionSecrets> secretsFactory,
                                     OpenAiCompatibleLimits baseLimits) {
        super(listener);
        this.clientFactory = clientFactory;
        this.secretsFactory = secretsFactory;
        this.baseLimits = baseLimits;
        this.pollerRegistry = new PollerRegistry(baseLimits);
    }

    /**
     * Starts the session and always ends with one visible status: READY when it is up, FAILED (with the reason) on
     * every early exit. AiTopComponent keeps the tab locked until one of them arrives, and a reopened session with
     * saved history sends no startup turn whose closing READY would unlock it, so a start without its own READY left
     * the tab locked with nothing said.
     */
    @Override
    public synchronized void start(String ignored, String model) {
        stop();
        if (currentSession == null) {
            failStart(StatusMessageUtil.formatSessionNotConfigured());
            return;
        }
        sessionId = currentSession.id();
        this.model = model;
        OllamaMcpRegistrar candidate = new OllamaMcpRegistrar(sessionId, currentSession.aiType());
        boolean registered;
        try {
            registered = registerMcp(candidate);
        }
        catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.log(Level.WARNING, "MCP registration failed for OpenAI Compatible session " + sessionId, ex);
            registered = false;
        }
        if (!registered) {
            failStart(StatusMessageUtil.formatMcpSetupFailed());
            return;
        }
        registrar = candidate;
        try {
            prepareSession(currentSession);
        }
        catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "OpenAI Compatible session " + sessionId + " failed to start", ex);
            stop();
            failStart(StatusMessageUtil.formatStartFailed(ex.getMessage() == null
                                                          ? ex.getClass().getSimpleName() : ex.getMessage()));
            return;
        }
        debugLog(DebugDirectionEnum.EVENT, "start", "");
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY,
                                                  StatusMessageUtil.formatReady(DISPLAY_NAME)));
    }

    static final String DISPLAY_NAME = "OpenAI Compatible";

    private void failStart(String failure) {
        debugLog(DebugDirectionEnum.EVENT, "error", failure);
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, failure));
    }

    /**
     * Registers the session with the shared MCP server, waiting at most two minutes; tests substitute the result.
     */
    private boolean registerMcp(OllamaMcpRegistrar candidate) throws Exception {
        java.util.function.Predicate<OllamaMcpRegistrar> override = mcpRegistrationForTest;
        if (override != null) {
            return override.test(candidate);
        }
        return McpServerRegistry.register(candidate).get(2, TimeUnit.MINUTES);
    }

    private volatile java.util.function.Predicate<OllamaMcpRegistrar> mcpRegistrationForTest;

    /**
     * Replaces the MCP registration's outcome, for tests in this package.
     */
    void setMcpRegistrationForTest(java.util.function.Predicate<OllamaMcpRegistrar> outcome) {
        mcpRegistrationForTest = outcome;
    }

    /**
     * Brings the session up without the MCP server, for tests in this package.
     */
    synchronized void startWithoutMcpForTest(AiSession session) {
        stop();
        currentSession = session;
        sessionId = session.id();
        running = true;
        prepareSession(session);
    }

    /**
     * Replaces the MCP bridge and the offered tools, for tests in this package; call after the session has started.
     */
    void setToolsForTest(ToolRunner.Invoker invoker, JsonArray tools) {
        invokerForTest = invoker;
        toolsForTest = tools;
    }

    private void prepareSession(AiSession session) {
        if (contextIo.isShutdown()) {
            contextIo = newContextIo();
        }
        OpenAiCompatibleAiSession mcpSession = new OpenAiCompatibleAiSession(session, listener);
        OpenAiCompatibleMcpBridge sessionBridge = new OpenAiCompatibleMcpBridge(mcpSession);
        sessionBridge.setSessionCredentials(session.id(), session.secret());
        aiSession = mcpSession;
        bridge = sessionBridge;
        toolRunner = new ToolRunner(this::invokeTool, this::toolExecutor,
                                    call -> listener.onAiProcessEvent(
                                            new ToolUseEvent(call.name(), null, "", null, ToolUseEvent.Kind.OTHER)));
        history = new ArrayList<>();
        lastContextUsed = 0;
        SessionDecisions sessionDecisions = new SessionDecisions(sessionId);
        decisions = sessionDecisions;
        turnContext = newTurnContext(sessionDecisions, hintSink());
        turnContext.applySettings(settingsOf(session));
        turnContext.setWindowSources(sessionDecisions::facts);
        contextRestored = false;
        contextIgnoredLogged = false;
        keyResultHint = null;
        keyResultTooltip = null;
        lastReachable = null;
        compacting = false;
        compactQueued.set(false);
        pendingMailNotice = false;
        uncappedHostedNoted = false;
        toolCallingState = new ToolCallingState();
        OpenAiCompatibleSessionSettings settings = settingsOf(session);
        latestSettings = settings;
        lastDetectedFacts = null;
        boolean keySet = settings != null && settings.effectiveApiKeySet();
        SessionSecrets loaded = secretsFactory.apply(sessionId, keySet);
        List<String> secretHeaders = settings == null ? List.of()
                                     : settings.extraHeaderNames().stream().filter(ExtraHeaders::isSecret).toList();
        secrets = loaded;
        stopped = false;
        checkKeysAfterLoad(settings, loaded, secretHeaders);
        sessionDecisions.generation(detectionGeneration(settings));
        detectionSettingsSignature = detectionSettingsSignature(settings);
        client = observed(clientFactory.apply(limitsFor(settings)));
        joinPoller(session, settings);
        session.setInstructionsLoaded(true);
        running = true;
        DetectedSnapshotSources.register(sessionId, this);
        restoreContext();
    }

    static String normalisedKindName(String name) {
        return name == null ? "" : name.replace("_", "").toUpperCase(Locale.ROOT);
    }

    static boolean isTypeMismatch(ServerTypeEnum configured, ServerKind detected) {
        return configured != null && configured != ServerTypeEnum.AUTO
                && configured != ServerTypeEnum.OTHER
                && detected != null
                && !normalisedKindName(configured.name()).equals(normalisedKindName(detected.name()));
    }

    static String mismatchHintText(ServerTypeEnum configured, ServerKind detected) {
        ServerKind configuredKind = configuredKind(configured);
        String configuredName = configuredKind == null ? configured.name() : configuredKind.displayName();
        if (detected != null && detected != ServerKind.GENERIC) {
            return "Server looks like " + detected.displayName() + " rather than " + configuredName
                    + " — try Auto-detect";
        }
        return "Server doesn't look like " + configuredName + " — try Auto-detect";
    }

    private static ServerKind configuredKind(ServerTypeEnum configured) {
        if (configured == null) {
            return null;
        }
        return switch (configured) {
            case OLLAMA ->
                ServerKind.OLLAMA;
            case LM_STUDIO ->
                ServerKind.LMSTUDIO;
            case LLAMA_CPP ->
                ServerKind.LLAMACPP;
            case VLLM ->
                ServerKind.VLLM;
            default ->
                null;
        };
    }

    /**
     * Applies a saved session configuration to a live backend without restarting its MCP session.
     */
    void setSettingsPersister(Consumer<OpenAiCompatibleSessionSettings> persister) {
        settingsPersister = persister == null ? settings -> {
        } : persister;
    }

    synchronized void onSessionSettingsChanged(OpenAiCompatibleSessionSettings settings) {
        if (!running || currentSession == null || settings == null) {
            return;
        }
        latestSettings = settings;
        this.model = settings.effectiveModel();
        turnContext.applySettings(settings);
        if (!settings.effectiveSaveContextOnClose()) {
            deleteContextFile();
        }
        String nextSignature = detectionSettingsSignature(settings);
        // A session key's value is deliberately absent from settings. Always reload secrets so a Change in Session
        // Configuration reaches an already running session even when no detection setting changed.
        SessionSecrets previous = secrets;
        SessionSecrets loaded = secretsFactory.apply(sessionId, settings.effectiveApiKeySet());
        secrets = loaded;
        checkKeysAfterLoad(settings, loaded,
                           settings.extraHeaderNames().stream().filter(ExtraHeaders::isSecret).toList());
        if (previous != null) {
            previous.close();
        }
        if (nextSignature.equals(detectionSettingsSignature)) {
            keyResultHint = null;
            keyResultTooltip = null;
            refreshKeyHint(loaded, lastHeaderWarnings);
            client = observed(clientFactory.apply(limitsFor(settings)));
            fireSnapshotChanged();
            return;
        }
        detectionSettingsSignature = nextSignature;
        decisions.generation(detectionGeneration(settings));
        decisions.reset();
        toolCallingState = new ToolCallingState();
        keyResultHint = null;
        keyResultTooltip = null;
        client = observed(clientFactory.apply(limitsFor(settings)));
        joinPoller(currentSession, settings);
        fireSnapshotChanged();
    }

    /**
     * Once the Keyring batch has been read, shows what it found: a key set on the session but not on this computer, an
     * unavailable keyring, or a secret header without its value.
     */
    private void checkKeysAfterLoad(OpenAiCompatibleSessionSettings settings, SessionSecrets loaded,
                                    List<String> secretHeaders) {
        loaded.loadAsync(secretHeaders).whenComplete((ignored, error) -> {
            if (secrets != loaded) {
                return;
            }
            if (error == null) {
                keysLoadedFor = loaded;
                rejoinPollerWithKey(loaded);
            }
            List<String> warnings = new ArrayList<>();
            if (settings != null) {
                ExtraHeaders.forRequest(settings.extraHeaderNames(), settings.extraHeaderPlainValues(),
                                        loaded::header, settings.effectiveApiKeySet(), warnings::add);
            }
            refreshKeyHint(loaded, warnings);
        });
    }

    private static String detectionSettingsSignature(OpenAiCompatibleSessionSettings settings) {
        return BaseUrls.root(settings.effectiveBaseUrl()) + "\\n" + settings.effectiveModel() + "\\n"
                + settings.effectiveApiKeySet() + "\\n" + settings.effectiveServerType() + "\\n"
                + settings.extraHeaderNames();
    }

    private String detectionGeneration(OpenAiCompatibleSessionSettings settings) {
        String url = settings == null ? "" : settings.effectiveBaseUrl();
        String modelName = settings == null ? model : settings.effectiveModel();
        SessionSecrets current = secrets;
        return DetectedFacts.generation(BaseUrls.root(url), modelName, current == null ? "" : current.keyHash());
    }

    /**
     * The secrets whose Keyring batch has completed; only then may {@link #joinPoller} read the key without waiting.
     */
    private volatile SessionSecrets keysLoadedFor;
    /**
     * The settings most recently applied to the running session (start, then every settings change), read by the poller
     * callback so it never works from a copy taken when the poller was joined.
     */
    private volatile OpenAiCompatibleSessionSettings latestSettings;
    private volatile BiConsumer<String, char[]> pollerJoinObserverForTest;

    /**
     * Sees each poller join's key hash and (a copy of) the key it carries, for tests in this package.
     */
    void setPollerJoinObserverForTest(BiConsumer<String, char[]> observer) {
        pollerJoinObserverForTest = observer;
    }

    boolean pollerJoinedForTest() {
        return pollerJoined;
    }

    /**
     * The key arrived after the poller was joined without one: join again so the probes carry it. Nothing to do when
     * there is no key, or before the poller was joined (it then picks the key up itself).
     */
    private void rejoinPollerWithKey(SessionSecrets loaded) {
        AiSession session = currentSession;
        OpenAiCompatibleSessionSettings latest = latestSettings;
        OpenAiCompatibleSessionSettings settings = latest != null ? latest : settingsOf(session);
        if (stopped || !pollerJoined || session == null || settings == null) {
            return;
        }
        if (loaded.apiKey(Duration.ZERO).isEmpty()) {
            return;
        }
        joinPoller(session, settings);
    }

    static boolean isDetectedOllama(DetectedFacts facts) {
        return facts != null && facts.kind() == ServerKind.OLLAMA;
    }

    private static void zeroSecret(char[] value) {
        if (value != null) {
            Arrays.fill(value, (char) 0);
        }
    }

    /**
     * Joins the detection poller with the session's API key once the Keyring batch has been read (spec §3.4: the key is
     * sent when set), so keyed and hosted endpoints answer the probes. Before that the poller is joined without a key;
     * {@link #checkKeysAfterLoad} joins again when the key arrives. The key is copied by the registry and never logged.
     */
    private void joinPoller(AiSession session, OpenAiCompatibleSessionSettings settings) {
        joinPoller(session, settings, false);
    }

    private void joinPoller(AiSession session, OpenAiCompatibleSessionSettings settings, boolean retried) {
        String url = settings == null ? "" : settings.effectiveBaseUrl();
        String root = BaseUrls.root(url);
        String modelName = settings == null ? model : settings.effectiveModel();
        ServerTypeEnum type = settings == null ? ServerTypeEnum.AUTO : settings.effectiveServerType();
        SessionSecrets current = secrets;
        char[] key = null;
        String keyHash = "";
        if (current != null && current == keysLoadedFor) {
            // The batch is complete, so this returns at once without touching the Keyring.
            key = current.apiKey(Duration.ZERO).orElse(null);
            keyHash = key == null ? "" : current.keyHash();
        }
        // Snapshots are stamped with this join's key hash, so the decisions must expect the same generation.
        SessionDecisions currentDecisions = decisions;
        if (currentDecisions != null) {
            currentDecisions.generation(DetectedFacts.generation(root, modelName, keyHash));
        }
        boolean keyed = key != null;
        BiConsumer<String, char[]> observer = pollerJoinObserverForTest;
        if (observer != null) {
            observer.accept(keyHash, key == null ? null : key.clone());
        }
        if (stopped) {
            pollerRegistry.leave(session.id());
            zeroSecret(key);
            return;
        }
        pollerJoinKeyHash = keyHash;
        pollerRegistry.join(session.id(), root, keyHash, key, type, modelName,
                            (facts, reachable) -> onPollerReport(facts, reachable, settings));
        zeroSecret(key);
        if (stopped) {
            pollerRegistry.leave(session.id());
            return;
        }
        pollerJoined = true;
        // The key may have loaded while this join was running, when rejoinPollerWithKey saw no joined poller.
        if (!keyed && !retried && current != null && current == keysLoadedFor) {
            char[] late = current.apiKey(Duration.ZERO).orElse(null);
            if (late != null) {
                zeroSecret(late);
                joinPoller(session, settings, true);
            }
        }
    }

    /**
     * A detection result. The session's settings are read now, not when the poller was joined: a change that keeps the
     * same detection signature (a new window override, say) does not join again, so the joined copy goes stale.
     *
     * @param joinedSettings used only when the session has no OpenAI Compatible settings any more
     */
    void onPollerReport(DetectedFacts facts, boolean reachable, OpenAiCompatibleSessionSettings joinedSettings) {
        OpenAiCompatibleSessionSettings latest = latestSettings;
        OpenAiCompatibleSessionSettings live = latest != null ? latest : settingsOf(currentSession);
        OpenAiCompatibleSessionSettings settings = live != null ? live : joinedSettings;
        String url = settings == null ? "" : settings.effectiveBaseUrl();
        String modelName = settings == null ? model : settings.effectiveModel();
        ServerTypeEnum type = settings == null ? ServerTypeEnum.AUTO : settings.effectiveServerType();
        OpenAiCompatibleInfoBarExtension bar = infoBar;
        lastReachable = reachable;
        if (!reachable) {
            if (bar != null) {
                bar.setUnreachable(url);
            }
            fireSnapshotChanged();
            return;
        }
        if (bar != null) {
            bar.clear(HintState.HintKind.UNREACHABLE);
        }
        if (facts != null) {
            lastDetectedFacts = facts;
            decisions.accept(facts);
            if (bar != null) {
                if (!facts.models().isEmpty()) {
                    bar.setAvailableModels(facts.models());
                }
                List<String> levels = facts.capabilities() == null ? null : facts.capabilities().reasoningLevels();
                bar.setAvailableEfforts(levels, settings == null ? null : settings.reasoningEffort());
            }
            emitContextUsage(settings, modelName, null);
            boolean refused = facts.probesUnauthorized();
            if (refused && keyResultHint == null) {
                detectionHintShown = true;
                applyKeyHint(!pollerJoinKeyHash.isBlank() ? KEY_REJECTED_HINT : DETECTION_REFUSED_HINT, "");
            }
            else if (detectionHintShown && keyResultHint == null) {
                detectionHintShown = false;
                applyKeyHint(null, null);
            }
            boolean mismatch = !refused && isTypeMismatch(type, facts.kind());
            if (mismatch) {
                if (bar != null) {
                    bar.setTypeMismatch(type.displayName(), facts.kindName());
                }
            }
            else if (bar != null) {
                bar.clear(HintState.HintKind.TYPE_MISMATCH);
            }
            WindowReading reading = facts.windowReadings().stream().findFirst().orElse(null);
            if (reading != null && reading.tokens() > 0
                    && reading.tokens() < OpenAiCompatiblePluginSettings.getSmallWindowWarning()) {
                if (bar != null) {
                    bar.setSmallWindow(reading.tokens(), facts.kindName());
                }
            }
            else if (bar != null) {
                bar.clear(HintState.HintKind.SMALL_WINDOW);
            }
            if (reading == null && !mismatch && !WindowLadder.hasAnySource(facts)) {
                barSetHint(bar, HintState.HintKind.WINDOW_UNKNOWN,
                           "Window unknown — set the context window in session settings to enable automatic compaction.",
                           "");
            }
            else if (bar != null) {
                bar.clear(HintState.HintKind.WINDOW_UNKNOWN);
            }
        }
        fireSnapshotChanged();
    }

    @Override
    public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        // AiTopComponent locked its input before calling this, so every refusal must close with INFO plus a
        // TurnCompleteEvent or the UI stays locked (same contract as Ollama (local)).
        if (pendingDiff) {
            refuseSendPrompt("A file diff is awaiting review");
            return;
        }
        if (!running || currentSession == null || client == null) {
            refuseSendPrompt("OpenAI Compatible session is not running");
            return;
        }
        if (processing || isWorkInFlight()) {
            refuseSendPrompt("Wait for the model to finish before sending");
            return;
        }
        if (compacting) {
            refuseSendPrompt(PROMPT_REFUSED_DURING_COMPACT);
            return;
        }
        if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
            sessionWorkingDir = workingDir;
        }
        cancelledByUser = false;
        processing = true;
        RequestHandle handle = new RequestHandle();
        TurnOutcome outcome = new TurnOutcome();
        activeHandle = handle;
        activeOutcome = outcome;
        setCompactButtonEnabled(false);
        updateResetEnabled();
        Thread worker = new Thread(() -> runTurn(text, handle, outcome), "openai-compatible-turn-" + sessionId);
        worker.setDaemon(true);
        activeTurnThread = worker;
        worker.start();
    }

    private void refuseSendPrompt(String reason) {
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, reason));
        listener.onAiProcessEvent(new TurnCompleteEvent());
    }

    /**
     * The turn thread. Its {@code finally} is the single exit: whatever happened, exactly one TurnCompleteEvent.
     */
    private void runTurn(String text, RequestHandle handle, TurnOutcome outcome) {
        ScheduledFuture<?> statusTick = null;
        // This turn's flags start from what the session has learned and the server kind; what the turn learns is
        // recorded in the session when it ends.
        SessionDecisions.Combined snapshot = SessionDecisions.combine(decisions.facts(), decisions);
        SessionDecisions.RequestFlags requestFlags = decisions.beginRequestSnapshot(snapshot);
        DetectedFacts detected = snapshot.facts();
        try {
            AiSession session = currentSession;
            OpenAiCompatibleSessionSettings settings = settingsOf(session);
            if (settings == null) {
                outcome.claim(TurnEndReason.SERVER_ERROR,
                              TurnEndReason.SERVER_ERROR.message("The session settings are not OpenAI Compatible."));
                return;
            }
            OpenAiCompatibleLimits limits = limitsFor(settings);
            // The visible tools are taken once per turn (spec §4.1); the bridge refuses one hidden since then.
            OfferedTools offered = offeredTools(session);
            TurnLoop.RequestContext context = requestContext(settings, limits, offered.schemas(), detected);
            debugLog(DebugDirectionEnum.EVENT, "turn", "Turn started (model " + context.model() + ", "
                     + context.apiBase() + ", " + offered.schemas().size() + " tools)");
            ToolCallingModeEnum mode = toolCallingModeFor(settings, context.model(), detected);
            registerMemoryNotifier();
            TurnLoop loop = new TurnLoop(history, systemPrompt(offered), client, requestFlags.flags(),
                                         limits, handle, outcome, context, turnListener(), debugSink(),
                                         this::takeMailNotice, toolRunner, ++turnCounter)
                    .withCounts(countsOf(settings))
                    .withToolCalling(toolCallingState, mode)
                    .withToolResultCap(toolResultCapChars(settings))
                    .withApprovalWait(this::isPendingDiff)
                    .withShowReasoning(settings.effectiveShowReasoning())
                    .withOverflowRetries(OpenAiCompatiblePluginSettings.getOverflowRetries())
                    .withEmptyReplyNudges(OpenAiCompatiblePluginSettings.getEmptyReplyNudges())
                    .withKeepAlive(isDetectedOllama(detected)
                                   ? OpenAiCompatiblePluginSettings.getKeepAlive() : null);
            TurnContext sessionContext = turnContext;
            sessionContext.applySettings(settings);
            sessionContext.setWindowSources(() -> detected);
            sessionContext.beginTurn(contextConfig(settings, context.model(), detected),
                                     summaryRunner(settings, context, limits, offered.hiddenTools(),
                                                   requestFlags.flags()));
            loop.withContext(sessionContext);
            if (detected == null) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, "Waiting for server…"));
            }
            waitForFirstWindow(limits, handle);
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, ""));
            noteUncappedHostedEndpoint(settings);
            activeLoop = loop;
            statusTick = startStatusTick(loop::statusText, loop::statusTooltip);
            loop.run(text);
        }
        catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "OpenAI Compatible turn failed", ex);
            String detail = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            outcome.claim(TurnEndReason.SERVER_ERROR, TurnEndReason.SERVER_ERROR.message(detail));
        }
        finally {
            if (statusTick != null) {
                statusTick.cancel(false);
            }
            requestFlags.finish();
            finishTurn(handle, outcome);
        }
    }

    private void finishTurn(RequestHandle handle, TurnOutcome outcome) {
        if (outcome.reason() == null) {
            outcome.claim(TurnEndReason.SERVER_ERROR,
                          TurnEndReason.SERVER_ERROR.message("The turn ended without a result."));
        }
        TurnEndReason reason = outcome.reason();
        String message = outcome.message();
        boolean cancelled = reason == TurnEndReason.CANCELLED;
        debugLog(DebugDirectionEnum.EVENT, "turn-end", reason + (message == null ? "" : ": " + message));
        // No monitor here: start() holds it while it waits for MCP registration, and the closing events must not wait
        // behind that. A new turn can only begin once processing is false, so the fields are cleared first.
        TurnLoop loop = activeLoop;
        OpenAiCompatibleSessionSettings settings = settingsOf(currentSession);
        Integer reportedUsage = loop == null ? null : loop.reportedUsage();
        lastContextUsed = reportedUsage != null && reportedUsage >= 0 ? reportedUsage : turnContext.estimate(history);
        emitContextUsage(settings, model, lastContextUsed);
        // The history is stable here (no turn runs until processing is cleared below), so it is safe to save.
        persistContextAsync();
        turnContext.setWindowSources(decisions::facts);
        fireSnapshotChanged();
        if (activeHandle == handle) {
            activeHandle = null;
            activeOutcome = null;
            activeTurnThread = null;
            activeLoop = null;
        }
        // Cleared before the closing events so a listener that checks isBusy() sees the turn finished.
        processing = false;
        setCompactButtonEnabled(true);
        // The UI shows no chat line for ANSWERED, so a note on an answered turn ("The model returned no text.")
        // travels as a system notification instead.
        if (reason == TurnEndReason.ANSWERED && message != null && !message.isBlank()) {
            listener.onAiProcessEvent(new SystemNotificationEvent(message));
        }
        listener.onAiProcessEvent(new TurnCompleteEvent(reason, message));
        if (pollerJoined) {
            pollerRegistry.postReply(sessionId);
        }
        if (cancelled) {
            // STOPPED makes the UI reject any diff or question still pending from this turn.
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, "Cancelled"));
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY, ""));
        boolean queueCompaction;
        queueCompaction = compactQueued.getAndSet(false);
        if (queueCompaction) {
            BackendExecutors.probePool().execute(this::compactNow);
        }
        updateResetEnabled();
    }

    private ScheduledFuture<?> startStatusTick(Supplier<String> statusText) {
        return startStatusTick(statusText, () -> null);
    }

    /**
     * Sends the status line once a second, with its longer sentence as the tooltip (null falls back to the label).
     */
    private ScheduledFuture<?> startStatusTick(Supplier<String> statusText, Supplier<String> statusTooltip) {
        try {
            return BackendExecutors.scheduler().scheduleAtFixedRate(
                    () -> listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, statusText.get(), false,
                                                                    statusTooltip.get())),
                    0L, STATUS_TICK_MILLIS, TimeUnit.MILLISECONDS);
        }
        catch (RuntimeException ex) {
            // A status line is a nicety; a refused schedule (plugin shutting down) must not fail the turn.
            return null;
        }
    }

    /**
     * The tools this turn offers and the MCP instruction text that goes with them.
     */
    private record OfferedTools(JsonArray schemas, String instructions, Set<McpToolEnum> hiddenTools,
                                boolean memoryExposed) {

        OfferedTools(JsonArray schemas, String instructions) {
            this(schemas, instructions, Set.of(), false);
        }
    }

    /**
     * The session's visible tools, computed exactly as Ollama (local) does: the effective handlers for the session's
     * settings, their schemas with references to hidden tools removed, and the instructions built from the FULL handler
     * map so hidden tools are filtered out of the text too.
     */
    private OfferedTools offeredTools(AiSession session) {
        JsonArray override = toolsForTest;
        if (override != null) {
            return new OfferedTools(override, "");
        }
        OpenAiCompatibleAiSession mcpSession = aiSession;
        OpenAiCompatibleMcpBridge sessionBridge = bridge;
        if (mcpSession == null || sessionBridge == null || session == null) {
            return new OfferedTools(new JsonArray(), "");
        }
        Map<McpToolEnum, McpToolInterface> allHandlers = mcpSession.getMcpToolHandlers();
        Map<McpToolEnum, McpToolInterface> handlers = McpToolAccess.effectiveTools(session.settings(), allHandlers);
        Set<McpToolEnum> hiddenTools = HiddenToolTextFilter.hiddenFrom(allHandlers, handlers);
        String instructions = McpInstructionRegistry.buildFullInstructions(session.aiType(), allHandlers,
                                                                           session.settings());
        JsonArray schemas = new JsonArray();
        for (JsonElement element : sessionBridge.listToolsForModel(handlers.values(), hiddenTools)) {
            if (element != null && element.isJsonObject()) {
                // Portable definitions (spec §5); the MCP shape (inputSchema) is kept, the request builder reads it.
                schemas.add(ToolSchemaSanitiser.sanitise(element.getAsJsonObject(),
                                                         ToolSchemaSanitiser.Limits.fromPluginSettings()));
            }
        }
        return new OfferedTools(schemas, instructions, hiddenTools, handlers.containsKey(McpToolEnum.MEMORY_SAVE));
    }

    /**
     * Summaries for this turn or compaction: the session's summary model (the chat model unless set), the same
     * endpoint, key, headers and flags, no tools, and the hidden tools filtered out (spec §6.3).
     */
    private TurnContext.SummaryRunner summaryRunner(OpenAiCompatibleSessionSettings settings,
                                                    TurnLoop.RequestContext context, OpenAiCompatibleLimits limits,
                                                    Set<McpToolEnum> hiddenTools, CompatibilityFlags sessionFlags) {
        ChatClient chatClient = client;
        if (chatClient == null) {
            return null;
        }
        String summaryModel = settings.effectiveSummaryModel(context.model());
        Summariser summariser = Summariser.fromPluginSettings(
                chatClient, new ContextBudget(ContextBudgetSettings.from(settings)), limits, debugSink());
        return (messages, previousSummary, window, pinned, handle) -> summariser.summarise(
                new Summariser.SummaryRequest(context.apiBase(), summaryModel, context.apiKey(), context.headers(),
                                              sessionFlags, context.keySituation(), context.samplingOptions(), window, pinned, previousSummary,
                                              messages, hiddenTools, handle == null ? new RequestHandle() : handle,
                                              null));
    }

    private String invokeTool(String toolName, JsonObject arguments) {
        ToolRunner.Invoker override = invokerForTest;
        if (override != null) {
            return override.invoke(toolName, arguments);
        }
        OpenAiCompatibleMcpBridge sessionBridge = bridge;
        if (sessionBridge == null) {
            return "Error: the session is not running.";
        }
        return sessionBridge.invokeTool(toolName, arguments);
    }

    private TurnLoop.RequestContext requestContext(OpenAiCompatibleSessionSettings settings,
                                                   OpenAiCompatibleLimits limits, JsonArray tools,
                                                   DetectedFacts detected) {
        String entered = settings.effectiveBaseUrl();
        List<String> candidates = BaseUrls.apiBaseCandidates(entered);
        String apiBase = detected != null && detected.apiBase() != null && !detected.apiBase().isBlank()
                         ? detected.apiBase()
                         : (candidates.isEmpty() ? BaseUrls.normalise(entered) : candidates.get(0));
        String effectiveModel = model != null && !model.isBlank() ? model : settings.effectiveModel();

        SessionSecrets current = secrets;
        char[] apiKey = null;
        ErrorMapper.KeySituation keySituation = ErrorMapper.KeySituation.NONE;
        if (current != null) {
            Optional<char[]> key = current.apiKey(limits.probeTimeout());
            if (key.isPresent()) {
                apiKey = key.get();
                keySituation = ErrorMapper.KeySituation.SENT;
                registerDebugSecret(new String(apiKey));
            }
            else {
                SessionSecrets.KeyState state = current.keyState();
                if (state == SessionSecrets.KeyState.UNAVAILABLE
                        || state == SessionSecrets.KeyState.MISSING_ON_THIS_COMPUTER) {
                    keySituation = ErrorMapper.KeySituation.NOT_SENT_UNAVAILABLE;
                }
                else {
                    keySituation = ErrorMapper.KeySituation.NO_KEY_CONFIGURED;
                }
            }
        }
        SessionSecrets lookup = current;
        List<String> headerWarnings = new ArrayList<>();
        List<ExtraHeader> headers = ExtraHeaders.forRequest(
                settings.extraHeaderNames(), settings.extraHeaderPlainValues(),
                name -> lookup == null ? Optional.empty() : lookup.header(name),
                settings.effectiveApiKeySet(),
                warning -> {
                    headerWarnings.add(warning);
                    debugLog(DebugDirectionEnum.EVENT, "header", warning);
                });
        refreshKeyHint(current, headerWarnings);
        for (ExtraHeader header : headers) {
            if (ExtraHeaders.isSecret(header.name())) {
                registerDebugSecret(header.value());
            }
        }
        List<String> levels = detected == null || detected.capabilities() == null
                              ? null : detected.capabilities().reasoningLevels();
        ReasoningEffortPolicy.Decision effort = ReasoningEffortPolicy.effective(
                settings.reasoningEffort(), OpenAiCompatiblePluginSettings.getReasoningEffort(), levels);
        if (effort.clearPinned()) {
            String unsupported = settings.reasoningEffort();
            settings.setReasoningEffort(null);
            settingsPersister.accept(settings);
            listener.onAiProcessEvent(new SystemNotificationEvent("Reasoning effort " + unsupported
                    + " isn't supported by " + effectiveModel + "; using the server default."));
        }
        SamplingOptions sampling = new SamplingOptions(settings.effectiveTemperature(), settings.effectiveTopP(),
                                                       settings.effectiveMaxOutputTokens(), effort.levelToSendOrNull());
        return new TurnLoop.RequestContext(apiBase, effectiveModel, tools, apiKey, headers, keySituation, sampling,
                                           mutatingToolNames());
    }

    private Set<String> mutatingToolNames() {
        OpenAiCompatibleAiSession current = aiSession;
        if (current == null) {
            return Set.of();
        }
        return current.getMcpToolHandlers().entrySet().stream()
                .filter(entry -> entry.getValue().isMutating())
                .map(entry -> entry.getKey().toolName())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * The pinned identity, baseline and session instructions, then the Memory section when the memory tools are
     * offered, then the MCP tool instructions. Built when a turn (or a compaction) starts, so a note saved during one
     * turn is in the next turn's prompt.
     */
    private String systemPrompt(OfferedTools offered) {
        StringBuilder prompt = new StringBuilder();
        for (String part : new String[]{pinnedIdentity, pinnedBaseline, pinnedInstructions, memorySection(offered),
            offered.instructions()}) {
            if (part != null && !part.isBlank()) {
                if (prompt.length() > 0) {
                    prompt.append("\n\n");
                }
                prompt.append(part.strip());
            }
        }
        return prompt.toString();
    }

    /**
     * The Memory section for this session's system prompt, or null when the memory tools are not offered to it. The
     * notes are read from the session's own folder, found the way the saved context is.
     */
    private String memorySection(OfferedTools offered) {
        String id = sessionId;
        if (!offered.memoryExposed() || id == null) {
            return null;
        }
        List<SessionMemoryStore.Note> notes;
        try {
            notes = new SessionMemoryStore(SessionMemoryFolders.memoryDir(contextDirResolver.resolve(id))).list();
        }
        catch (IOException | RuntimeException ex) {
            notes = List.of();
        }
        TurnContext estimating = turnContext;
        return MemoryPromptSection.build(
                notes, text -> estimating.estimate(List.of(new ChatMessage(ChatRole.SYSTEM, text, List.of(), null))),
                MemoryPromptSection.INDEX_TOKEN_CAP);
    }

    private volatile SessionMemoryNotifier.Listener memoryListener;
    private volatile String memoryListenerSessionId;

    /**
     * Has the memory tools' saves and deletes noted in this session's backend debug log (names only, never content).
     */
    private void registerMemoryNotifier() {
        String id = sessionId;
        if (id == null || id.equals(memoryListenerSessionId)) {
            return;
        }
        unregisterMemoryNotifier();
        SessionMemoryNotifier.Listener noted = (action, name) -> debugLog(DebugDirectionEnum.EVENT, "memory",
                                                                          action + " " + name);
        SessionMemoryNotifier.register(id, noted);
        memoryListener = noted;
        memoryListenerSessionId = id;
    }

    private void unregisterMemoryNotifier() {
        SessionMemoryNotifier.Listener noted = memoryListener;
        String id = memoryListenerSessionId;
        memoryListener = null;
        memoryListenerSessionId = null;
        if (noted != null) {
            SessionMemoryNotifier.unregister(id, noted);
        }
    }

    private DebugSink debugSink() {
        return new DebugSink() {
            @Override
            public void sent(String json) {
                debugLog(DebugDirectionEnum.SENT, "request", json);
            }

            @Override
            public void received(String line) {
                debugLog(DebugDirectionEnum.RECEIVED, "line", line);
            }

            @Override
            public void event(String text) {
                debugLog(DebugDirectionEnum.EVENT, "client", text);
            }
        };
    }

    private boolean takeMailNotice() {
        if (!pendingMailNotice) {
            return false;
        }
        pendingMailNotice = false;
        return true;
    }

    private static TurnContext newTurnContext(SessionDecisions sessionDecisions, TurnContext.HintSink hints) {
        TokenEstimator estimator = new TokenEstimator(OpenAiCompatiblePluginSettings.getEstimatorCharsPerToken(),
                                                      OpenAiCompatiblePluginSettings.getEstimatorOverhead(),
                                                      OpenAiCompatiblePluginSettings.getEstimatorSmoothing(),
                                                      OpenAiCompatiblePluginSettings.getEstimatorMinRatio(),
                                                      OpenAiCompatiblePluginSettings.getEstimatorMaxRatio());
        return new TurnContext(estimator, sessionDecisions, hints);
    }

    /**
     * This turn's context settings. Until the poller is wired (Plan 5 Task 7) the configured server type stands in for
     * the detected kind.
     */
    static TurnContext.Config contextConfig(OpenAiCompatibleSessionSettings settings, String turnModel) {
        return contextConfig(settings, turnModel, null);
    }

    static TurnContext.Config contextConfig(OpenAiCompatibleSessionSettings settings, String turnModel, DetectedFacts facts) {
        String kind = facts != null && facts.kindName() != null ? facts.kindName() : settings.effectiveServerType().name();
        return new TurnContext.Config(settings.effectiveCompactionStrategy(), settings.effectiveAutoCompact(),
                                      settings.effectivePrune(), settings.effectiveContextWindowOverride(),
                                      OpenAiCompatiblePluginSettings.getAssumedWindow(),
                                      OpenAiCompatiblePluginSettings.getMinimumWindowForTools(),
                                      kind, turnModel, settings.effectiveBaseUrl(),
                                      settings.effectiveSummariseThreshold());
    }

    /**
     * Publishes the resolved context window, never a raw probe reading. A reported usage includes both prompt and
     * completion tokens; when unavailable the model-facing history estimate is used instead.
     */
    private void emitContextUsage(OpenAiCompatibleSessionSettings settings, String turnModel, Integer reportedUsage) {
        if (settings == null) {
            return;
        }
        DetectedFacts facts = decisions.facts();
        TurnContext context = turnContext;
        WindowReading window = context.window(contextConfig(settings, turnModel, facts));
        int used = reportedUsage != null && reportedUsage >= 0 ? reportedUsage : lastContextUsed;
        ServerKind kind = facts == null ? configuredKind(settings.effectiveServerType()) : facts.kind();
        String kindName = kind == null ? ServerKind.GENERIC.displayName() : kind.displayName();
        listener.onAiProcessEvent(new OpenAiCompatibleContextUsageEvent(used, window.tokens(),
                                                                        friendlyWindowSource(window.source()),
                                                                        kindName));
    }

    private static String friendlyWindowSource(WindowReading.Source source) {
        return WindowSourceText.friendly(source);
    }

    private void waitForFirstWindow(OpenAiCompatibleLimits limits, RequestHandle handle) {
        if (handle.isCancelled() || decisions.facts() != null || limits == null) {
            return;
        }
        long deadline = System.nanoTime() + limits.probeTimeout().toNanos();
        while (!handle.isCancelled() && decisions.facts() == null && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20L);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void barSetHint(OpenAiCompatibleInfoBarExtension bar, HintState.HintKind kind, String text, String tooltip) {
        if (bar != null) {
            bar.set(kind, text, tooltip);
        }
    }

    /**
     * Hint changes go to the session's info bar once it is attached.
     */
    private TurnContext.HintSink hintSink() {
        return new TurnContext.HintSink() {
            @Override
            public void set(HintState.HintKind kind, String text, String tooltip) {
                OpenAiCompatibleInfoBarExtension bar = infoBar;
                if (bar != null) {
                    bar.set(kind, text, tooltip);
                }
            }

            @Override
            public void clear(HintState.HintKind kind) {
                OpenAiCompatibleInfoBarExtension bar = infoBar;
                if (bar != null) {
                    bar.clear(kind);
                }
            }

            @Override
            public void summary(String summaryOrNull, boolean trimmedNotSummarised) {
                OpenAiCompatibleInfoBarExtension bar = infoBar;
                if (bar != null) {
                    bar.setSummary(summaryOrNull, trimmedNotSummarised);
                }
            }
        };
    }

    /**
     * Connects the session's info bar: its Compact button runs {@link #compactNow}, and context hints go to it.
     */
    public void attachInfoBar(OpenAiCompatibleInfoBarExtension bar) {
        infoBar = bar;
        if (bar != null) {
            bar.setCompactListener(this::compactNow);
            bar.setResetListener(() -> {
                if (!resetContext()) {
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, RESET_REFUSED_WHILE_BUSY));
                }
            });
            // What was decided before the bar existed (a restored summary, a key problem found at start).
            String restoredSummary = turnContext.summary();
            if (restoredSummary != null) {
                bar.setSummary(restoredSummary, false);
            }
            String keyHint = shownKeyHint;
            if (keyHint != null) {
                bar.set(HintState.HintKind.KEY, keyHint, shownKeyTooltip == null ? "" : shownKeyTooltip);
            }
            DetectedFacts facts = lastDetectedFacts;
            if (facts != null) {
                if (!facts.models().isEmpty()) {
                    bar.setAvailableModels(facts.models());
                }
                List<String> levels = facts.capabilities() == null ? null : facts.capabilities().reasoningLevels();
                OpenAiCompatibleSessionSettings settings = latestSettings;
                bar.setAvailableEfforts(levels, settings == null ? null : settings.reasoningEffort());
            }
        }
    }

    /**
     * Manual Compact (spec §6.2, §6.5): always runs, on the assumed window when none is confirmed; refused while a turn
     * runs. Runs off the calling thread (the button calls it on the EDT) with the status "Compacting N s".
     */
    boolean compactQueuedForTest() {
        return compactQueued.get();
    }

    public void compactNow() {
        synchronized (this) {
            if (!running || currentSession == null) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                                          "OpenAI Compatible session is not running"));
                return;
            }
            if (processing || isWorkInFlight() || compacting) {
                compactQueued.set(true);
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, COMPACT_REFUSED_DURING_TURN));
                updateResetEnabled();
                return;
            }
            compacting = true;
            compactStartNanos = System.nanoTime();
        }
        setCompactButtonEnabled(false);
        updateResetEnabled();
        OpenAiCompatibleInfoBarExtension bar = infoBar;
        if (bar != null) {
            bar.setCompacting(true);
        }
        Thread worker = new Thread(this::runCompaction, "openai-compatible-compact-" + sessionId);
        worker.setDaemon(true);
        worker.start();
    }

    private void runCompaction() {
        ScheduledFuture<?> statusTick = startStatusTick(() -> "Compacting "
                + Math.max(0L, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - compactStartNanos)) + " s");
        SessionDecisions.Combined snapshot = SessionDecisions.combine(decisions.facts(), decisions);
        SessionDecisions.RequestFlags compactFlags = decisions.beginRequestSnapshot(snapshot);
        DetectedFacts detected = snapshot.facts();
        try {
            AiSession session = currentSession;
            OpenAiCompatibleSessionSettings settings = settingsOf(session);
            if (settings == null) {
                return;
            }
            OpenAiCompatibleLimits limits = limitsFor(settings);
            OfferedTools offered = offeredTools(session);
            TurnLoop.RequestContext context = requestContext(settings, limits, offered.schemas(), detected);
            try {
                TurnContext sessionContext = turnContext;
                sessionContext.applySettings(settings);
                sessionContext.setWindowSources(() -> detected);
                sessionContext.beginTurn(contextConfig(settings, context.model(), detected),
                                         summaryRunner(settings, context, limits, offered.hiddenTools(),
                                                       compactFlags.flags()));
                RequestHandle handle = new RequestHandle();
                compactHandle = handle;
                TurnContext.Compaction compaction = sessionContext.compact(
                        history, systemPrompt(offered), true, handle);
                if (!compaction.changed()) {
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, NOTHING_TO_COMPACT));
                }
                for (String notice : compaction.notices()) {
                    listener.onAiProcessEvent(new SystemNotificationEvent(notice));
                }
                lastContextUsed = sessionContext.estimate(history);
                emitContextUsage(settings, context.model(), lastContextUsed);
            }
            finally {
                if (context.apiKey() != null) {
                    Arrays.fill(context.apiKey(), '\0');
                }
            }
        }
        catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "OpenAI Compatible compaction failed", ex);
        }
        finally {
            if (statusTick != null) {
                statusTick.cancel(false);
            }
            compactFlags.finish();
            compactHandle = null;
            compacting = false;
            OpenAiCompatibleInfoBarExtension bar = infoBar;
            if (bar != null) {
                bar.setCompacting(false);
            }
            setCompactButtonEnabled(true);
            updateResetEnabled();
            persistContextAsync();
            fireSnapshotChanged();
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY, ""));
            if (compactQueued.getAndSet(false)) {
                BackendExecutors.probePool().execute(this::compactNow);
            }
            updateResetEnabled();
        }
    }

    /**
     * Greys the Compact button while a turn or a compaction runs; the extension marshals to the EDT.
     */
    private void setCompactButtonEnabled(boolean enabled) {
        OpenAiCompatibleInfoBarExtension bar = infoBar;
        if (bar != null) {
            bar.setCompactEnabled(enabled);
        }
        updateResetEnabled();
    }

    private void updateResetEnabled() {
        OpenAiCompatibleInfoBarExtension bar = infoBar;
        if (bar != null) {
            bar.setResetEnabled(!processing && !compacting && !compactQueued.get());
        }
    }

    boolean isCompacting() {
        return compacting;
    }

    TurnContext turnContextForTest() {
        return turnContext;
    }

    String currentSessionIdForTest() {
        return sessionId;
    }

    SessionDecisions decisionsForTest() {
        return decisions;
    }

    private TurnLoop.Listener turnListener() {
        return new TurnLoop.Listener() {
            @Override
            public void textDelta(String text) {
                listener.onAiProcessEvent(new TextDeltaEvent(text, null));
            }

            @Override
            public void notice(String text) {
                listener.onAiProcessEvent(new SystemNotificationEvent(text));
            }
        };
    }

    /**
     * This turn's tool-calling mode (spec §5). When the native-failure count has just reached the limit, the switch to
     * schema is recorded (sticky), the model-facing context starts afresh (the chat itself is kept) and the user is
     * told.
     */
    private ToolCallingModeEnum toolCallingModeFor(OpenAiCompatibleSessionSettings settings, String turnModel, DetectedFacts detected) {
        ToolCallingState state = toolCallingState;
        ToolCallingModeEnum setting = settings.effectiveToolCallingMode();
        boolean wasSwitched = state.switchedToSchema();
        int limit = settings.effectiveNativeFailuresBeforeSchema(
                OpenAiCompatibleSessionSettings.DEFAULT_NATIVE_FAILURES_BEFORE_SCHEMA);
        Boolean nativeSupported = detected == null || detected.capabilities() == null
                                  ? null : detected.capabilities().tools();
        ToolCallingModeEnum mode = ToolCallModeResolver.mode(setting, nativeSupported, state.nativeFailures(turnModel), limit,
                                                             wasSwitched);
        if (mode == ToolCallingModeEnum.SCHEMA && !wasSwitched && setting != ToolCallingModeEnum.SCHEMA) {
            state.switchToSchema();
            // The fresh context carries the summary, when there is one (spec §5).
            history = new ArrayList<>(turnContext.freshHistory());
            debugLog(DebugDirectionEnum.EVENT, "turn", "Native tool calls failed " + limit
                     + " times; switching to schema tool calling with a fresh context.");
            listener.onAiProcessEvent(new SystemNotificationEvent(SWITCHED_TO_SCHEMA_MESSAGE));
        }
        return mode;
    }

    /**
     * The per-result cap: the tool-result share of the window (the session's override, else an assumed 8 192 tokens
     * until detection supplies one) in characters.
     */
    static int toolResultCapChars(OpenAiCompatibleSessionSettings settings) {
        int override = settings.effectiveContextWindowOverride();
        int window = override > 0 ? override : OpenAiCompatiblePluginSettings.getAssumedWindow();
        return new ContextBudget(ContextBudgetSettings.from(settings))
                .toolResultCapChars(window, OpenAiCompatiblePluginSettings.getEstimatorCharsPerToken());
    }

    static TurnLoop.Counts countsOf(OpenAiCompatibleSessionSettings settings) {
        return new TurnLoop.Counts(
                settings.effectiveMaxToolRounds(OpenAiCompatibleSessionSettings.DEFAULT_MAX_TOOL_ROUNDS),
                settings.effectiveMaxLengthContinuations(OpenAiCompatibleSessionSettings.DEFAULT_MAX_LENGTH_CONTINUATIONS),
                settings.effectiveMaxMalformedRounds(OpenAiCompatibleSessionSettings.DEFAULT_MAX_MALFORMED_ROUNDS),
                settings.effectiveRepetitionWindow(OpenAiCompatibleSessionSettings.DEFAULT_REPETITION_WINDOW),
                settings.effectiveUnproductiveRounds(OpenAiCompatibleSessionSettings.DEFAULT_UNPRODUCTIVE_ROUNDS));
    }

    /**
     * Once per session: a hosted endpoint (one that bills) with no tool-round cap gets a chat note (spec §4.1 item 4).
     */
    private void noteUncappedHostedEndpoint(OpenAiCompatibleSessionSettings settings) {
        if (uncappedHostedNoted) {
            return;
        }
        int cap = settings.effectiveMaxToolRounds(OpenAiCompatibleSessionSettings.DEFAULT_MAX_TOOL_ROUNDS);
        if (cap <= 0 && BaseUrls.isHosted(settings.effectiveBaseUrl(), settings.effectiveApiKeySet())) {
            uncappedHostedNoted = true;
            listener.onAiProcessEvent(new SystemNotificationEvent(UNCAPPED_HOSTED_MESSAGE));
        }
    }

    private OpenAiCompatibleLimits limitsFor(OpenAiCompatibleSessionSettings settings) {
        return settings == null ? baseLimits : settings.applyTo(baseLimits);
    }

    private static OpenAiCompatibleSessionSettings settingsOf(AiSession session) {
        return session != null && session.settings() instanceof OpenAiCompatibleSessionSettings settings
               ? settings : null;
    }

    @Override
    public void updatePinnedContext(String identity, String baseline, String instructions) {
        pinnedIdentity = identity;
        pinnedBaseline = baseline;
        pinnedInstructions = instructions;
    }

    /**
     * Resets the model memory to the start of the session (after special instructions were injected). Refuses while a
     * turn or compaction is running/queued. Keeps pinned/instruction content and server/window
     * detection/calibration/model decisions.
     */
    public boolean resetContext() {
        OpenAiCompatibleSessionSettings settings;
        String currentModel;
        synchronized (this) {
            if (!running || currentSession == null) {
                return false;
            }
            if (processing || isWorkInFlight() || compacting || compactQueued.get()) {
                return false;
            }
            history = new ArrayList<>();
            turnContext.clearSummary();
            turnContext.clearPruneState();
            lastContextUsed = 0;
            settings = latestSettings != null ? latestSettings : settingsOf(currentSession);
            currentModel = model;
        }
        deleteContextFile();
        emitContextUsage(settings, currentModel, 0);
        OpenAiCompatibleInfoBarExtension bar = infoBar;
        if (bar != null) {
            bar.setSummary(null, false);
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                                  "Context reset — the model no longer has the earlier conversation (the chat log above is kept)"));
        fireSnapshotChanged();
        return true;
    }

    /**
     * Not synchronized: {@link #start} holds the monitor while it waits for MCP registration, and Cancel must never
     * wait behind it.
     */
    @Override
    public void interrupt(InterruptTypeEnum type) {
        if (type == InterruptTypeEnum.Mail) {
            // Delivered at the next round boundary of a running turn (TurnLoop), or at the start of the next turn.
            pendingMailNotice = true;
            return;
        }
        if (type == InterruptTypeEnum.Cancel) {
            cancelActiveTurn();
        }
    }

    /**
     * Claims CANCELLED, closes the request and wakes the turn thread; the thread's own {@code finally} reports it.
     */
    private void cancelActiveTurn() {
        if (!processing) {
            return;
        }
        cancelledByUser = true;
        TurnOutcome outcome = activeOutcome;
        if (outcome != null) {
            TurnLoop loop = activeLoop;
            String runningTool = loop == null ? null : loop.runningToolName();
            outcome.claim(TurnEndReason.CANCELLED, runningTool != null
                                                   ? TurnLoop.cancelledDuringToolMessage(runningTool)
                                                   : TurnEndReason.CANCELLED.message());
        }
        RequestHandle handle = activeHandle;
        if (handle != null) {
            handle.cancel();
        }
        Thread turnThread = activeTurnThread;
        if (turnThread != null) {
            turnThread.interrupt();
        }
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenAI Compatible interrupt: user pressed Stop (session={0})", sessionId);
        }
    }

    /**
     * Never waits: cancels the turn, drops the tool executor, closes the debug log and leaves the MCP server.
     */
    @Override
    public void stop() {
        stopped = true;
        if (running && !processing && !compacting) {
            // Idle, so the history is stable. A running turn or compaction saves it itself when it ends.
            persistContextAsync();
        }
        failWorkInFlight("OpenAI Compatible session stopped while work was in progress");
        cancelActiveTurn();
        RequestHandle compaction = compactHandle;
        if (compaction != null) {
            compaction.cancel();
        }
        running = false;
        DetectedSnapshotSources.unregister(sessionId, this);
        unregisterMemoryNotifier();
        if (pollerJoined) {
            pollerRegistry.leave(sessionId);
            pollerJoined = false;
        }
        OllamaMcpRegistrar registered = registrar;
        registrar = null;
        if (registered != null) {
            McpServerRegistry.deregister(registered);
        }
        SessionSecrets loaded = secrets;
        secrets = null;
        if (loaded != null) {
            loaded.close();
        }
        OpenAiCompatibleAiSession mcpSession = aiSession;
        aiSession = null;
        bridge = null;
        if (mcpSession != null) {
            mcpSession.dispose();
        }
        // Interrupts a running tool (an ApplyEdit waiting on its diff returns a denial) and returns at once.
        shutdownToolExecutorAsync();
        closeBackendDebugLog(false);
        shutdownContextIo();
    }

    private void shutdownContextIo() {
        ExecutorService executor = contextIo;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
        catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Brings back the saved model-facing context of an existing session (spec §6.6), unless this start already did.
     */
    @Override
    public void resumeSession(String existingSessionId) {
        if (running) {
            restoreContext();
        }
    }

    // ---- Saved context (spec §6.6) ----
    /**
     * Where the context file is written, for tests in this package.
     */
    void setContextDirResolverForTest(ContextDirResolver resolver) {
        contextDirResolver = resolver;
    }

    private Path contextFile() {
        String id = sessionId;
        if (id == null) {
            return null;
        }
        try {
            return contextDirResolver.resolve(id).resolve(CONTEXT_FILE_NAME);
        }
        catch (IOException | RuntimeException ex) {
            logContextOnce("the session folder is not available (" + ex.getMessage() + ")");
            return null;
        }
    }

    /**
     * What to do with the context file, decided on the thread that owns the history.
     *
     * @param content the file's new content, or null to delete the file
     */
    private record SavePlan(Path file, String content) {

    }

    private SavePlan planSave() {
        Path file = contextFile();
        if (file == null) {
            return null;
        }
        OpenAiCompatibleSessionSettings settings = settingsOf(currentSession);
        List<ChatMessage> snapshot = new ArrayList<>(history);
        if (settings == null || !settings.effectiveSaveContextOnClose() || snapshot.isEmpty()) {
            return new SavePlan(file, null);
        }
        String content = turnContext.exportState(snapshot).toString();
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > baseLimits.transcriptCapBytes()) {
            logContextOnce("the context is larger than the transcript limit, so it was not saved");
            return new SavePlan(file, null);
        }
        return new SavePlan(file, content);
    }

    private void writePlan(SavePlan plan) {
        if (plan == null) {
            return;
        }
        try {
            if (plan.content() == null) {
                Files.deleteIfExists(plan.file());
                return;
            }
            Files.createDirectories(plan.file().getParent());
            Path temporary = plan.file().resolveSibling(plan.file().getFileName() + ".tmp");
            Files.writeString(temporary, plan.content());
            try {
                Files.move(temporary, plan.file(), StandardCopyOption.REPLACE_EXISTING,
                           StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, plan.file(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException | RuntimeException ex) {
            logContextOnce("the context file could not be written (" + ex.getMessage() + ")");
        }
    }

    /**
     * Snapshots and queues the context save; all file I/O runs on the serial context executor.
     */
    private void persistContextAsync() {
        SavePlan plan = planSave();
        writePlanAsync(plan);
    }

    private void deleteContextFile() {
        Path file = contextFile();
        if (file != null) {
            writePlanAsync(new SavePlan(file, null));
        }
    }

    /**
     * Queues a context-file operation on the manager's serial executor. The inline fallback only applies when the
     * executor has already been shut down during final disposal.
     */
    private void writePlanAsync(SavePlan plan) {
        if (plan == null) {
            return;
        }
        try {
            contextIo.execute(() -> writePlan(plan));
        }
        catch (RejectedExecutionException ex) {
            writePlan(plan);
        }
    }

    void awaitContextIo() throws InterruptedException {
        try {
            contextIo.submit(() -> {
            }).get(5, TimeUnit.SECONDS);
        }
        catch (RejectedExecutionException ex) {
            // The queue has already been drained during shutdown.
        }
        catch (InterruptedException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new AssertionError("Context I/O queue did not drain", ex);
        }
    }

    /**
     * Restores the saved context once per start: the history, the summary (the Summary button), the calibration ratio
     * and the prune bookkeeping. A missing file is normal; a file that is corrupt, too large or unreadable is ignored
     * with one note in the log. With the setting off the file is removed instead.
     */
    private void restoreContext() {
        if (contextRestored) {
            return;
        }
        contextRestored = true;
        OpenAiCompatibleSessionSettings settings = settingsOf(currentSession);
        Path file = contextFile();
        if (file == null || settings == null) {
            return;
        }
        if (!settings.effectiveSaveContextOnClose()) {
            writePlanAsync(new SavePlan(file, null));
            return;
        }
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            if (Files.size(file) > baseLimits.transcriptCapBytes()) {
                logContextOnce("the saved context is larger than the transcript limit");
                return;
            }
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            List<ChatMessage> restored = turnContext.importState(json);
            history = new ArrayList<>(restored);
            lastContextUsed = turnContext.estimate(history);
            emitContextUsage(settings, model, lastContextUsed);
        }
        catch (IOException | RuntimeException ex) {
            logContextOnce("the saved context could not be read (" + ex.getMessage() + ")");
        }
    }

    private void logContextOnce(String why) {
        if (!contextIgnoredLogged) {
            contextIgnoredLogged = true;
            LOG.log(Level.INFO, "OpenAI Compatible saved context ignored for session {0}: {1}",
                    new Object[]{sessionId, why});
        }
    }

    // ---- Key hints (spec §7.4 rank 2, §8.6) ----
    static final String KEY_REJECTED_HINT = "API key rejected";
    static final String DETECTION_REFUSED_HINT = "Detection refused (401) — the server needs an API key";
    static final String KEY_NEEDED_HINT = "API key needed";
    static final String KEY_UNAVAILABLE_HINT = "API key unavailable (keyring locked?)";
    static final String KEY_NOT_FOUND_HINT = "API key not found on this computer — enter it again";
    static final String KEY_NOT_SET_HINT = "No API key is set for this session";
    static final String KEY_UNAVAILABLE_TIP
            = "The system keyring was unavailable, so no API key was sent. Unlock it or enter the key again.";
    static final String KEY_NOT_FOUND_TIP
            = "The session says an API key was set, but this computer's keyring has none for it. Enter the key again "
            + "in session settings.";
    static final String HEADER_NEEDS_VALUE_MARKER = "needs its value on this computer";

    /**
     * Wraps the HTTP client so every result is seen here: key hints from authentication failures and the one-time note
     * that a server does not stream.
     */
    private ChatClient observed(ChatClient delegate) {
        if (delegate == null) {
            return null;
        }
        return ChatClient.mapped(delegate, this::onClientResult);
    }

    private ClientResult onClientResult(ClientResult result) {
        if (result == null) {
            return null;
        }
        if (result.nonStreaming() && decisions.recordNonStreaming()) {
            debugLog(DebugDirectionEnum.EVENT, "client", "The server does not stream; each reply arrives as one body.");
            fireSnapshotChanged();
        }
        MappedError error = result.error();
        if (result.end() == ClientResult.End.HTTP_ERROR && error != null) {
            switch (error.kind()) {
                case KEY_REJECTED ->
                    setKeyResultHint(KEY_REJECTED_HINT, error.message());
                case KEY_REQUIRED ->
                    setKeyResultHint(KEY_NEEDED_HINT, error.message());
                case NO_KEY_SENT -> {
                    if (shownKeyHint == null || DETECTION_REFUSED_HINT.equals(shownKeyHint)) {
                        setKeyResultHint(noKeySentHint(), noKeySentTooltip());
                    }
                }
                default -> {
                }
            }
        }
        else if (result.end() == ClientResult.End.COMPLETE && keyResultHint != null) {
            keyResultHint = null;
            keyResultTooltip = null;
            refreshKeyHint(secrets, lastHeaderWarnings);
        }
        return result;
    }

    private volatile List<String> lastHeaderWarnings = List.of();

    private void setKeyResultHint(String text, String tooltip) {
        keyResultHint = text;
        keyResultTooltip = tooltip;
        applyKeyHint(text, tooltip);
    }

    static String noKeySentHintForState(SessionSecrets.KeyState state, boolean effectiveApiKeySet) {
        if (state == SessionSecrets.KeyState.UNAVAILABLE) {
            return KEY_UNAVAILABLE_HINT;
        }
        return effectiveApiKeySet ? KEY_NOT_FOUND_HINT : KEY_NOT_SET_HINT;
    }

    private String noKeySentHint() {
        SessionSecrets current = secrets;
        if (current != null) {
            if (current.keyState() == SessionSecrets.KeyState.UNAVAILABLE) {
                return KEY_UNAVAILABLE_HINT;
            }
            if (current.keyState() == SessionSecrets.KeyState.MISSING_ON_THIS_COMPUTER) {
                return KEY_NOT_FOUND_HINT;
            }
        }
        OpenAiCompatibleSessionSettings settings = latestSettings;
        if (settings != null && settings.effectiveApiKeySet()) {
            SessionSecrets.KeyState state = current == null ? SessionSecrets.KeyState.UNKNOWN_YET : current.keyState();
            if (state == SessionSecrets.KeyState.UNKNOWN_YET) {
                return KEY_UNAVAILABLE_HINT;
            }
            return noKeySentHintForState(state, true);
        }
        return KEY_NOT_SET_HINT;
    }

    private String noKeySentTooltip() {
        return "Enter an API key in session settings.";
    }

    /**
     * Recomputes the key hint: a hint from the last request first, then what the Keyring state says (not found on this
     * computer, unavailable, not saved), then a secret header that still needs its value. Cleared when none applies.
     */
    private void refreshKeyHint(SessionSecrets current, List<String> headerWarnings) {
        lastHeaderWarnings = headerWarnings == null ? List.of() : List.copyOf(headerWarnings);
        String text = keyResultHint;
        String tooltip = keyResultTooltip;
        if (text == null && current != null) {
            switch (current.keyState()) {
                case MISSING_ON_THIS_COMPUTER -> {
                    text = KEY_NOT_FOUND_HINT;
                    tooltip = KEY_NOT_FOUND_TIP;
                }
                case UNAVAILABLE -> {
                    text = KEY_UNAVAILABLE_HINT;
                    tooltip = KEY_UNAVAILABLE_TIP;
                }
                default -> {
                }
            }
        }
        if (text == null) {
            for (String warning : lastHeaderWarnings) {
                if (warning.contains(HEADER_NEEDS_VALUE_MARKER)) {
                    text = warning;
                    tooltip = "A secret header's value is kept in the system keyring and is missing here. Enter it "
                            + "again in session settings; the header is not sent until then.";
                    break;
                }
            }
        }
        applyKeyHint(text, tooltip);
    }

    private void applyKeyHint(String text, String tooltip) {
        shownKeyHint = text;
        shownKeyTooltip = tooltip;
        OpenAiCompatibleInfoBarExtension bar = infoBar;
        if (bar == null) {
            return;
        }
        if (text == null) {
            bar.clear(HintState.HintKind.KEY);
        }
        else {
            bar.set(HintState.HintKind.KEY, text, tooltip == null ? "" : tooltip);
        }
    }

    String keyHintForTest() {
        return shownKeyHint;
    }

    void setKeyResultHintForTest(String text) {
        setKeyResultHint(text, null);
    }

    /**
     * A copy of the model-facing history, for tests in this package.
     */
    List<ChatMessage> historyForTest() {
        return new ArrayList<>(history);
    }

    // ---- Live Detected data (spec §8.4) ----
    @Override
    public DetectedSnapshot snapshot() {
        SessionDecisions current = decisions;
        DetectedFacts facts = current.facts();
        OpenAiCompatibleSessionSettings settings = settingsOf(currentSession);
        TurnContext context = turnContext;
        String turnModel = model != null && !model.isBlank() ? model : settings == null ? "" : settings.effectiveModel();
        TurnContext.Config config = settings == null ? context.config() : contextConfig(settings, turnModel, facts);
        WindowReading window = context.window(config);
        return new DetectedSnapshot(facts, !Boolean.FALSE.equals(lastReachable), current.streaming(),
                                    toolCallingText(settings), compactionText(context.resolvedStrategy(config)),
                                    context.prunedCount(), window);
    }

    private String toolCallingText(OpenAiCompatibleSessionSettings settings) {
        ToolCallingModeEnum mode = settings == null ? ToolCallingModeEnum.AUTO : settings.effectiveToolCallingMode();
        String name = switch (mode) {
            case AUTO ->
                "Auto";
            case NATIVE ->
                "Native";
            case SCHEMA ->
                "Schema";
        };
        if (mode != ToolCallingModeEnum.SCHEMA && toolCallingState.switchedToSchema()) {
            return "Schema (switched from " + name + ")";
        }
        return name;
    }

    private static String compactionText(ResolvedCompactionEnum resolved) {
        return switch (resolved) {
            case SUMMARISE ->
                "Summarise";
            case DROP ->
                "Drop";
            case NONE ->
                "None";
        };
    }

    @Override
    public void addListener(Runnable snapshotListener) {
        if (snapshotListener != null) {
            snapshotListeners.add(snapshotListener);
        }
    }

    @Override
    public void removeListener(Runnable snapshotListener) {
        snapshotListeners.remove(snapshotListener);
    }

    /**
     * Tells the Detected panels something changed; a failing listener never affects the session.
     */
    private void fireSnapshotChanged() {
        for (Runnable snapshotListener : snapshotListeners) {
            try {
                snapshotListener.run();
            }
            catch (RuntimeException ex) {
                LOG.log(Level.FINE, "A Detected snapshot listener failed", ex);
            }
        }
    }

    @Override
    public boolean isMcpActive() {
        return registrar != null;
    }
}

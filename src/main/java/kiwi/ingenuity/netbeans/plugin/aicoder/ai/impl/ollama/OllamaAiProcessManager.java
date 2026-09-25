package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRequest;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ExtractedToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.HttpAiClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.OpenAiCompatibleClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.OpenAiHttpStatusException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.OpenAiJsonKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.OpenAiToolCallParseException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.SchemaToolCalls;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ToolCallExtractor;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.AbstractChatContextBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextBrokerSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextTriggerEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextTrimStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.OllamaChatContextBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.PinSlotEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.session.OllamaAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.OpenAiClientSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpInstructionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.RawJsonArgumentScanner;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.ContextPersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

public class OllamaAiProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(OllamaAiProcessManager.class.getName());

    /**
     * Stand-in tool name for a call the model botched badly enough that we never learned which tool it meant.
     * The error still has to reach the model as a tool result, and a tool result is only accepted alongside
     * an assistant tool call carrying the same id, so we synthesise the pair. The name is deliberately not a
     * real tool: nothing is executed, and it must never collide with one, or a later turn could read the
     * transcript as evidence that tool actually ran.
     */
    private static final String MALFORMED_TOOL_CALL_NAME = "unknown_tool";

    /**
     * Prefix for the id correlating the synthetic assistant call above with its error result.
     */
    private static final String MALFORMED_TOOL_CALL_ID_PREFIX = "call_malformed_";

    static final String END_TURN_TOOL_NAME = "EndTurn";

    /**
     * How many consecutive server-side tool-call parse failures to absorb before giving up and failing the
     * turn like a transport error. A malformed call is recoverable — the model gets the parse error back and
     * can repair it — but a model that cannot produce valid JSON twice in a row will not manage it on the
     * third attempt either, and without a bound it simply spins until the iteration cap.
     */
    private static final int MAX_MALFORMED_TOOL_CALL_ROUNDS = 2;

    // The tool loop's hard iteration bound lives in PluginSettings.getOllamaMaxToolIterations()
    // (ai.ollama.maxToolIterations, default 500) so tests can lower it. It is the only unconditional
    // guarantee that a turn ends: EndTurn is the normal exit, ai.ollama.maxNarrationTurns bounds
    // narration (reply after reply with no tool call, whatever it says) and
    // ai.ollama.maxUnproductiveRounds catches an exact loop (repeated text or already-seen tool
    // results), but none of them binds a model making endless productive calls.
    //
    // Removing this bound on 2026-09-25 produced a run that accumulated 226 million ChatMessage objects
    // and exhausted an 8 GB heap in under six minutes; two such JVMs took 17 GB of a 31 GB machine.
    // A JUnit @Timeout does NOT protect against it — the default thread mode reports the timeout without
    // interrupting the loop, so it keeps allocating after the test has already failed.
    /**
     * True for text that is an empty JSON object or array — "{}" or "[]", with or without a code fence.
     * Anything with actual content is left alone: a user can legitimately ask for JSON and must still receive
     * it.
     */
    static boolean isEmptyJson(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String stripped = text.strip();
        if (stripped.startsWith("```")) {
            int close = stripped.lastIndexOf("```");
            stripped = (close > 2 ? stripped.substring(3, close) : stripped.substring(3)).strip();
            int nl = stripped.indexOf('\n');
            if (nl >= 0 && !stripped.startsWith("{") && !stripped.startsWith("[")) {
                stripped = stripped.substring(nl + 1).strip();
            }
        }
        try {
            JsonElement parsed = JsonParser.parseString(stripped);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject().size() == 0;
            }
            return parsed.isJsonArray() && parsed.getAsJsonArray().isEmpty();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Appends the synthetic assistant tool call + error result pair that reports a model mistake the server
     * could not turn into a real tool call. The name is a stand-in that never collides with a real tool, so a
     * later turn can never read the transcript as evidence the tool ran; {@code malformedToolCallCount} keeps
     * the ids unique for the life of the session, because a repeated id would make the pairing ambiguous
     * across committed turns and stricter endpoints reject duplicates outright.
     */
    private void appendMalformedToolCallRecovery(AbstractChatContextBroker localBroker, String toolCallError) {
        String malformedCallId = MALFORMED_TOOL_CALL_ID_PREFIX + malformedToolCallCount.incrementAndGet();
        localBroker.append(new ChatMessage(ChatRole.ASSISTANT, null,
                List.of(new ChatToolCall(malformedCallId, MALFORMED_TOOL_CALL_NAME, "{}")), null));
        localBroker.append(new ChatMessage(ChatRole.TOOL, toolCallError, List.of(), malformedCallId));
    }

    /**
     * A hand-edited preference must not stop a session starting.
     */
    private static ContextTriggerEnum parseTrigger(String raw) {
        try {
            return ContextTriggerEnum.valueOf(raw);
        } catch (RuntimeException ex) {
            return ContextTriggerEnum.ESTIMATED_TOKENS;
        }
    }

    private static ContextTrimStrategyEnum parseStrategy(String raw) {
        try {
            return ContextTrimStrategyEnum.valueOf(raw);
        } catch (RuntimeException ex) {
            return ContextTrimStrategyEnum.DROP_MARKED;
        }
    }

    private static String describe(Throwable ex) {
        String msg = ex.getMessage();
        return msg != null && !msg.isBlank() ? msg : ex.getClass().getSimpleName();
    }

    /**
     * ContextBrokerSettings has no equals(): it is a plain mutable settings bag, not a value type, so
     * field-by-field comparison lives here instead. Two null-safety branches aside, this is the same
     * six-field comparison AbstractChatContextBroker.updateSettings() uses to decide whether to log —
     * duplicated rather than shared, since the two live in different packages for different purposes (one
     * gates a debug log line, this one resets a user-facing warning).
     */
    private static boolean settingsDiffer(ContextBrokerSettings a, ContextBrokerSettings b) {
        if (a == null || b == null) {
            return a != b;
        }
        return a.tokenThreshold() != b.tokenThreshold()
                || a.trimTargetPercent() != b.trimTargetPercent()
                || a.maxMessages() != b.maxMessages()
                || a.persistOnClose() != b.persistOnClose()
                || a.strategy() != b.strategy()
                || a.trigger() != b.trigger();
    }
    /**
     * Makes each recovery id unique for the life of the session.
     * <p>
     * A fixed id would repeat: once a turn recovers and commits, its synthetic pair stays in the transcript,
     * so a second malformed call in a later turn would put two assistant calls and two results with the same
     * id into the same request. Tool results are matched to calls by id, so duplicates make the pairing
     * ambiguous and stricter endpoints reject them outright. The per-turn loop counter is no use here - it
     * restarts every turn.
     */
    private final AtomicInteger malformedToolCallCount = new AtomicInteger();

    private volatile OllamaMcpRegistrar registrar;
    private volatile HttpAiClient httpClient;
    volatile OllamaAiSession ollamaSession;
    volatile OllamaMcpBridge bridge;
    volatile AbstractChatContextBroker broker;
    volatile Thread activeTurnThread;
    /**
     * Turn generation: monotonically incremented once per turn started and once per cancel/stop. A turn
     * worker keeps the value it was started with as its epoch and compares {@code turnGeneration} against it
     * in its loop and in its streaming callback — so once a thread is stale it can never re-enter, even if a
     * later turn resets {@code cancelledByUser}.
     */
    private volatile long turnGeneration;
    /**
     * The schema/native tool-calling mode the broker's current history was built under, or null before the
     * first turn. Ollama's Mistral-family templates cannot follow a mid-conversation switch between the two
     * protocols, so runTurn resets the accumulated history when this differs from the mode the next turn
     * would build under (the system-role pins survive). Reset in start() because the broker is recreated
     * there.
     */
    private volatile Boolean historySchemaMode;
    /**
     * Set by {@code interrupt(Mail)} and cleared by the tool-loop iteration that consumes it by appending
     * {@link InterruptTypeEnum#MAIL_NOTIFICATION_TEXT} to the broker. Volatile because the interrupt call
     * comes from a different thread than the turn worker. Deliberately left set if a turn ends before any
     * iteration runs: the notice then arrives at the start of the next turn instead of being lost — a stale
     * thread can never steal it because injection is guarded by {@code turnGeneration == turnEpoch}.
     */
    private volatile boolean pendingMailNotice;
    private volatile ContextBrokerSettings lastResolvedSettings;
    private volatile boolean contextDiscoveryInitial;
    private volatile boolean pinnedOverBudgetWarned;
    /**
     * Set once a live 4xx tells us this server rejects {@code reasoning_effort} on the OpenAI-compatible
     * endpoint — see the spec's Ollama section. Sticky for the life of the session: never retried, never
     * re-sent, after the first rejection.
     */
    private volatile boolean reasoningEffortDisabledForSession;
    /**
     * Notified (no argument — the caller already knows it only fires for the session-sourced case) when
     * {@link #applyThinkingCapabilityValidation} clears an unsupported SESSION-sourced value, so the owning
     * {@code OllamaAiImplementation} can also clear the PERSISTED session setting — otherwise only the live
     * settings read is affected for this turn, and the next turn (or session restart) re-reads the same stale
     * value and fires the INFO again. Never invoked for a global-sourced value — rule 3a leaves that alone
     * entirely. Deliberately a plain callback rather than plumbing an {@code AiSessionHost} reference into
     * this process-manager layer, which has no business knowing about session-settings persistence otherwise
     * — mirrors {@code GrokAiProcessManager}/{@code GithubCopilotProcessManager}'s identical callback.
     */
    private volatile Runnable onReasoningEffortCleared;

    public OllamaAiProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    /**
     * See {@link #onReasoningEffortCleared}'s javadoc.
     */
    public void setOnReasoningEffortCleared(Runnable callback) {
        this.onReasoningEffortCleared = callback;
    }

    @Override
    public synchronized void start(String ignored, String model) {
        stop();
        contextDiscoveryInitial = true;
        historySchemaMode = null;
        if (currentSession == null) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatSessionNotConfigured()));
            return;
        }
        if (!validateStart()) {
            return;
        }
        this.model = model;
        this.sessionId = currentSession.id();
        OllamaMcpRegistrar reg = new OllamaMcpRegistrar(sessionId, currentSession.aiType());
        boolean mcpReady;
        try {
            mcpReady = registerMcp(reg);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "MCP registration failed for Ollama session " + sessionId, ex);
            mcpReady = false;
        }
        if (!mcpReady) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatMcpSetupFailed()));
            return;
        }
        registrar = reg;
        ollamaSession = new OllamaAiSession(currentSession, listener);
        bridge = createBridge(ollamaSession);
        bridge.setSessionCredentials(currentSession.id(), currentSession.secret());
        ContextBrokerSettings settingsForBroker = resolveBrokerSettings();
        lastResolvedSettings = settingsForBroker;
        broker = createContextBroker(currentSession.id(), settingsForBroker);
        if (broker instanceof OllamaChatContextBroker ollamaBroker) {
            ollamaBroker.setContextLimitListener(limitValue -> {
                if (!processing && contextDiscoveryInitial) {
                    listener.onAiProcessEvent(new OllamaTokenUsageEvent(
                            broker.estimatedTokenTotal(),
                            contextWindowForEvent(broker, settingsForBroker.tokenThreshold())));
                }
            });
            ollamaBroker.startContextDiscovery(
                    resolveEffectiveBaseUrl(effectiveSessionSettings()),
                    resolveEffectiveModel(effectiveSessionSettings()));
        }
        if (broker != null
                && settingsForBroker.trigger() == ContextTriggerEnum.REPORTED_TOKENS) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Context trigger falls back to estimated tokens until this endpoint reports usage"));
        }
        if (broker != null) {
            // Built unconditionally, not only under the SUMMARISE strategy: the
            // Compact button can call it on demand from any strategy, and a
            // null summariser would make that button silently do nothing for
            // almost every user running the default DROP_MARKED strategy.
            OllamaSessionSettings settingsForSummariser = effectiveSessionSettings();
            broker.setSummariser(new OllamaContextSummariser(createHttpAiClient(),
                    resolveEffectiveBaseUrl(settingsForSummariser),
                    resolveApiKey(settingsForSummariser),
                    resolveEffectiveModel(settingsForSummariser)));
        }
        if (broker != null && settingsForBroker.persistOnClose()) {
            JsonObject saved = createContextPersistenceManager().load(currentSession.id());
            if (saved != null) {
                broker.restoreFromJson(saved);
                // Settings may have tightened while the session was closed; the
                // first request after a restore must not go out over budget.
                broker.trimIfNeeded();
                listener.onAiProcessEvent(new OllamaTokenUsageEvent(
                        broker.estimatedTokenTotal(), contextWindowForEvent(broker, settingsForBroker.tokenThreshold())));
            }
        }
        currentSession.setInstructionsLoaded(true);
        running = true;
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY,
                StatusMessageUtil.formatReady(displayName())));
    }

    @Override
    public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        if (pendingDiff || !running || processing || currentSession == null || bridge == null) {
            return;
        }
        cancelledByUser = false;
        contextDiscoveryInitial = false;
        processing = true;
        if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
            sessionWorkingDir = workingDir;
        }
        long turnEpoch = ++turnGeneration;
        Thread worker = new Thread(() -> runTurn(text, turnEpoch), "ollama-turn-" + sessionId);
        worker.setDaemon(true);
        activeTurnThread = worker;
        worker.start();
    }

    /**
     * Builds the contents of the TOOLS pin. MCP instructions are retained in both modes; schema mode appends
     * the rendered workaround protocol.
     */
    static String instructionsWithToolProtocol(String instructions, List<JsonObject> tools, boolean schemaMode) {
        if (!schemaMode) {
            return instructions
                    + "\n\n## MCP Tool List\nThe available tools are supplied separately in this request.\n\n"
                    + "EndTurn with its message argument is REQUIRED to finish the turn, which returns control to the user so they can prompt again; put the final answer in its message argument. Text alone never ends the turn, however final it sounds. A real tool call continues "
                    + "the turn; any accompanying message is shown to the user. A reply with no tool call is narration and "
                    + "also continues the turn.\n";
        }
        return instructions
                + "\n\n## MCP Tool List\n"
                + "These are the tools you can call to help you complete the user's task, with their parameters "
                + "(those in [square brackets] are optional; use each name exactly as written):\n\n"
                + SchemaToolCalls.renderToolList(tools)
                + "\n## End of tool list\n\n"
                + "Reply as JSON, one tool call at a time.\n\n"
                + "To call a tool: put its name in tool_name and its arguments in tool_arguments. The tool will run, its "
                + "tool result will come back to you, and your turn continues — you can then call another tool the same "
                + "way, for as many tools as the task needs.\n\n"
                + "You may also write a short note in message while calling a tool. It is shown to the user and your turn "
                + "continues.\n\n"
                + "To say something without calling a tool: leave tool_name empty and write in message. That is narration "
                + "— it is shown to the user and your turn continues.\n\n"
                + "To finish: set tool_name to EndTurn and put your final answer in its message argument. "
                + "EndTurn is how you end your turn — nothing else you send will end it.";
    }

    private OllamaSessionSettings effectiveSessionSettings() {
        return currentSession.settings() instanceof OllamaSessionSettings os
                ? os : new OllamaSessionSettings();
    }

    private String resolveEffectiveModel(OllamaSessionSettings settings) {
        return model != null && !model.isBlank()
                ? model
                : settings.model() != null && !settings.model().isBlank()
                ? settings.model()
                : defaultModel();
    }

    private String resolveEffectiveBaseUrl(OllamaSessionSettings settings) {
        return settings.baseUrl() != null && !settings.baseUrl().isBlank()
                ? settings.baseUrl()
                : defaultBaseUrl();
    }

    /**
     * The resolved reasoning-effort value together with whether it came from the session's own setting (true)
     * or was inherited from the global default (false) — the distinction matters: only a session-pinned value
     * may ever be cleared, persisted-cleared and reported with an INFO event; a global-sourced value is only
     * ever silently omitted, never cleared, never reported.
     */
    record EffectiveReasoningEffort(String value, boolean fromSession) {

    }

    /**
     * Session value wins over the global default; value is null once the local server has rejected the field
     * for this session (see {@link #reasoningEffortDisabledForSession}), so a later iteration or turn never
     * resends it. Package-private for direct unit testing, mirroring {@code buildReasoningEffortArgs}'s
     * equivalent on Grok.
     */
    EffectiveReasoningEffort resolveEffectiveReasoningEffort(OllamaSessionSettings settings) {
        if (reasoningEffortDisabledForSession) {
            return new EffectiveReasoningEffort(null, false);
        }
        String sessionEffort = settings.reasoningEffort();
        if (sessionEffort != null && !sessionEffort.isBlank()) {
            return new EffectiveReasoningEffort(sessionEffort, true);
        }
        String global = OllamaPluginSettings.getReasoningEffort();
        return new EffectiveReasoningEffort((global != null && !global.isBlank()) ? global : null, false);
    }

    /**
     * Applies the session-vs-global rule using live capability discovery
     * ({@link OllamaModelDiscovery#modelSupportsThinking}, populated from {@code GET /api/tags}). Returns the
     * value to actually send for this request (may be null).
     * <p>
     * Three cases:
     * <ul>
     * <li>discovery has positively confirmed {@code model} supports thinking (or {@code effective} is already
     * null) — return the value unchanged;</li>
     * <li>discovery has positively confirmed {@code model} does NOT support thinking — apply rule 3a: a
     * SESSION-sourced value is cleared (via {@link #onReasoningEffortCleared}) and reported with exactly one
     * INFO event; a GLOBAL-sourced value is silently omitted for this request only (FINE log, global
     * untouched, nothing written into the session) — return null either way;</li>
     * <li>discovery has not reported on {@code model} at all yet ({@link OllamaModelDiscovery#isModelKnown}
     * is false) — send the value optimistically rather than clearing a user's stored value on incomplete
     * information; the 4xx retry in {@link #chatWithReasoningEffortRetry} is the backstop if the model turns
     * out unable to think after all.</li>
     * </ul>
     * Package-private for direct unit testing, mirroring {@code buildReasoningEffortArgs}'s equivalent on
     * Grok.
     */
    String applyThinkingCapabilityValidation(EffectiveReasoningEffort effective, String baseUrl, String model) {
        if (effective.value() == null) {
            return null;
        }
        if (!OllamaModelDiscovery.isModelKnown(baseUrl, model)
                || OllamaModelDiscovery.modelSupportsThinking(baseUrl, model)) {
            return effective.value();
        }
        if (effective.fromSession()) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Thinking \"" + effective.value() + "\" is not supported by model \"" + model + "\"; clearing it"));
            Runnable cb = onReasoningEffortCleared;
            if (cb != null) {
                cb.run();
            }
        } else {
            // the global default belongs to the user and to every other session/backend — one
            // session's model not supporting it says nothing about the rest, so it is never cleared or written
            // anywhere. The combo already shows the "not set" entry for a model that can't take it, so the UI
            // communicates this without a warning the user can't dismiss.
            LOG.log(Level.FINE, "Thinking \"{0}\" (global default) is not supported by model \"{1}\"; omitting it for "
                    + "this request without touching the global default", new Object[]{effective.value(), model});
        }
        return null;
    }

    /**
     * Wraps a chat call with defensive retry logic: if the request carried {@code reasoning_effort} and the
     * server answered with a 4xx, retry once without the field, tell the user once, and remember not to send
     * it again for the rest of this session. Any other failure (non-4xx status, no reasoning_effort in the
     * request, network/stream error) propagates unchanged.
     */
    private ChatResult chatWithReasoningEffortRetry(HttpAiClient client, ChatRequest request,
            java.util.function.Consumer<String> onTextDelta) throws IOException {
        if (reasoningEffortDisabledForSession && request.reasoningEffort() != null) {
            // Belt and braces: a caller-side mistake (e.g. a stale resolveEffectiveReasoningEffort snapshot taken
            // before an earlier retry in the same turn set the flag) must not put the field back on the wire once
            // the session has already been told the server rejects it — strip it here rather than relying on a
            // second 4xx to catch it, which would also mean a second INFO event.
            request = new ChatRequest(request.baseUrl(), request.apiKey(), request.model(),
                    request.messages(), request.toolSchemas(), request.responseFormat(), null);
        }
        try {
            return client.chat(request, onTextDelta);
        } catch (OpenAiHttpStatusException ex) {
            if (request.reasoningEffort() == null || ex.statusCode() < 400 || ex.statusCode() >= 500) {
                throw ex;
            }
            reasoningEffortDisabledForSession = true;
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "The local server rejected " + OpenAiJsonKeyEnum.REASONING_EFFORT.key()
                    + "; retrying without it"));
            ChatRequest retryRequest = new ChatRequest(request.baseUrl(), request.apiKey(), request.model(),
                    request.messages(), request.toolSchemas(), request.responseFormat(), null);
            return client.chat(retryRequest, onTextDelta);
        }
    }

    private void runTurn(String text, long turnEpoch) {
        // The finally block needs to know whether THIS turn consumed a mail notice and whether it ended by
        // committing. Declared outside the try on purpose: the finally references them, and a local declared
        // inside a try is not in scope in its catch/finally. A notice consumed at the loop top is appended to
        // the broker and the flag is cleared; if the turn then rolls back the message is discarded while the
        // flag is already false — the nudge would be lost. The finally re-arms it, but only for a consumed
        // notice on a turn that did NOT commit.
        boolean mailNoticeConsumedThisTurn = false;
        boolean turnCommitted = false;
        try {
            OllamaSessionSettings settings = effectiveSessionSettings();
            String effectiveModel = resolveEffectiveModel(settings);
            String effectiveBaseUrl = resolveEffectiveBaseUrl(settings);

            Map<McpToolEnum, McpToolInterface> handlers = buildToolHandlers(ollamaSession);
            String instructions = McpInstructionRegistry.buildFullInstructions(
                    currentSession.aiType(), handlers);
            JsonArray toolSchemas = bridge.listToolsForModel(handlers.values());
            List<JsonObject> tools = new ArrayList<>();
            Set<String> knownToolNames = new LinkedHashSet<>();
            for (int i = 0; i < toolSchemas.size(); i++) {
                JsonObject tool = toolSchemas.get(i).getAsJsonObject();
                tools.add(tool);
                // These are OUR tool schemas, built by McpToolSchemas with
                // ToolSchemaKeyEnum — not anything Ollama sent. Reading them
                // back through an Ollama-vocabulary constant would mean a
                // rename of ToolSchemaKeyEnum.NAME still compiles, this test
                // goes false for every tool, and Ollama silently loses tool
                // calling entirely.
                if (tool.has(ToolSchemaKeyEnum.NAME.key())) {
                    knownToolNames.add(tool.get(ToolSchemaKeyEnum.NAME.key()).getAsString());
                }
            }

            JsonObject endTurnTool = SchemaToolCalls.endTurnToolSchema();
            tools.add(endTurnTool);
            // EndTurn must ALSO be a known name, not just an offered tool. Both SchemaToolCalls.parse and
            // ToolCallExtractor drop any call whose name is not in this set, so without this line the
            // model's EndTurn is silently discarded, endTurnCall below is always null, the turn never
            // ends, and the raw envelope is emitted to the user as narration. The whole contract was
            // dead code until an independent review found it — no test covered the end-to-end path.
            knownToolNames.add(END_TURN_TOOL_NAME);

            // Populating the tools array makes this backend call something on
            // every turn regardless of the request, so under TOOL_CALLS_VIA_SCHEMA
            // the tools are described in the prompt and the reply is constrained
            // by a schema instead. See SchemaToolCalls.
            boolean schemaMode = settings.useNativeToolCalling() != null
                    ? !settings.useNativeToolCalling()
                    : currentSession.aiType().getMcpOptions()
                            .contains(McpInstructionOptionEnum.TOOL_CALLS_VIA_SCHEMA);
            JsonObject responseFormat = schemaMode ? SchemaToolCalls.responseFormat(knownToolNames) : null;
            List<JsonObject> requestTools = schemaMode ? List.of() : tools;
            instructions = instructionsWithToolProtocol(instructions, tools, schemaMode);

            AbstractChatContextBroker localBroker = broker;
            if (localBroker == null) {
                return;
            }
            // Native tool calling cannot tolerate an assistant message that carries BOTH prose and tool_calls
            // (devstral:24b stops calling tools when one reaches it); tell the broker this turn's protocol so
            // its appendAssistant merge refuses to fold into that shape.
            localBroker.setNativeToolCalling(!schemaMode);
            // Mode-flip reset. The broker's history lives across turns, and every entry serialises whichever
            // tool-calling protocol it was generated under — schema envelopes, or native tool_calls bodies.
            // devstral and the other Mistral-family templates cannot switch protocols mid-conversation: history
            // built one way and a new turn requesting the other hands the model a broken transcript, and it
            // stops calling tools entirely. Rather than mix protocols, discard the accumulated dialogue and
            // continue with the new mode from a clean slate — the pins (instructions, tool list) are keyed
            // separately, so they survive the reset.
            if (historySchemaMode != null && historySchemaMode != schemaMode) {
                localBroker.clearHistory();
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "Tool-calling mode changed mid-session; the conversation history was reset (pins kept)."));
            }
            historySchemaMode = schemaMode;
            // Refreshed once per turn, at the boundary, never mid-trim or
            // inside the tool loop below: a strategy or threshold changing
            // underneath a half-completed trim would be very hard to reason
            // about. This is what lets a preference change (e.g. Token
            // threshold) take effect on the next request instead of requiring
            // a session restart.
            ContextBrokerSettings refreshedSettings = resolveBrokerSettings();
            if (settingsDiffer(lastResolvedSettings, refreshedSettings)) {
                pinnedOverBudgetWarned = false;
            }
            lastResolvedSettings = refreshedSettings;
            localBroker.updateSettings(refreshedSettings);
            localBroker.upsertPin(PinSlotEnum.TOOLS, instructions);
            localBroker.beginTurn();
            localBroker.append(new ChatMessage(ChatRole.USER, text, List.of(), null));

            HttpAiClient client = httpClient;
            if (client == null) {
                client = createHttpAiClient();
                httpClient = client;
            }
            // A tool result like "Description updated." tells a model nothing about
            // whether the work is finished, so a weaker one can re-issue the same
            // call indefinitely. Track what has already run this turn: a repeat is
            // answered with a correction instead of being executed again, and two
            // consecutive all-repeat rounds end the turn rather than grinding out
            // the full iteration cap.
            Set<String> executedCalls = new LinkedHashSet<>();
            // Identical arguments are not the only way to make no progress: the
            // model varied the description on every UpdateSessionDescription call
            // and got "Description updated." back each time. A tool result already
            // seen this turn means the call told the model nothing new.
            Set<String> seenResults = new LinkedHashSet<>();
            // Two separate bounds, per the narration-split pass. Counter A: rounds that added no NEW
            // information — a prose reply identical to the previous one, or a tool round whose results
            // were all already seen. Low bound, because the exact loop means the model is stuck. Counter
            // B: narration — a reply with no tool call at all never signals completion under the EndTurn
            // contract, whatever it says. A round that produced new information resets both.
            int unproductiveRounds = 0;
            int narrationRounds = 0;
            String previousAssistantText = null;
            int malformedToolCallRounds = 0;
            boolean nudgeSentOnPreviousRequest = false;

            // Read once per turn so a preference change mid-turn cannot move the bound underneath us.
            int maxToolIterations = PluginSettings.getOllamaMaxToolIterations();
            int maxUnproductiveRounds = PluginSettings.getOllamaMaxUnproductiveRounds();
            int maxNarrationTurns = PluginSettings.getOllamaMaxNarrationTurns();
            for (int iteration = 0; iteration < maxToolIterations && turnGeneration == turnEpoch; iteration++) {
                // Mail notice delivery. interrupt(Mail) only ever arms the flag — this is the single point
                // where queued mail notices are injected into the turn. The epoch guard stops a stale thread
                // from consuming a notice queued for a newer turn, and the notice is new information (not
                // narration, not an unproductive round), so both counters reset here rather than being
                // tripped by it. Consumption is remembered so a turn that later ROLLS BACK can re-arm the
                // flag (see the finally): rollback discards the appended notice, and the flag alone cannot
                // tell a delivered-and-committed notice from one that vanished with the turn.
                if (pendingMailNotice && turnGeneration == turnEpoch) {
                    pendingMailNotice = false;
                    mailNoticeConsumedThisTurn = true;
                    localBroker.append(new ChatMessage(ChatRole.USER, InterruptTypeEnum.MAIL_NOTIFICATION_TEXT, List.of(), null));
                    unproductiveRounds = 0;
                    narrationRounds = 0;
                }
                String apiKey = resolveApiKey(settings);
                String iterationReasoningEffort = applyThinkingCapabilityValidation(
                        resolveEffectiveReasoningEffort(settings), effectiveBaseUrl, effectiveModel);
                localBroker.trimIfNeeded();
                if (!pinnedOverBudgetWarned && localBroker.isPinnedOverBudget()) {
                    pinnedOverBudgetWarned = true;
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                            "Context threshold is too low for the pinned instructions and tool list — history is not being trimmed. Raise the Token threshold in the session's Context History settings."));
                }
                int estimatedForRequest = localBroker.estimatedTokenTotal();
                List<ChatMessage> wireMessages = new ArrayList<>(localBroker.snapshot());
                nudgeSentOnPreviousRequest = false;
                if (!schemaMode && !wireMessages.isEmpty()) {
                    ChatMessage lastMessage = wireMessages.get(wireMessages.size() - 1);
                    if (lastMessage.role() == ChatRole.ASSISTANT
                            && lastMessage.toolCalls().isEmpty()
                            && lastMessage.content() != null
                            && !lastMessage.content().isBlank()) {
                        // Mistral-family templates treat a final assistant message as an unfinished continuation.
                        // Keep the nudge on the wire only: it must not accumulate in broker history or persistence.
                        nudgeSentOnPreviousRequest = true;
                        wireMessages.add(new ChatMessage(ChatRole.USER,
                                "Continue. Call the tool you need now, or call EndTurn if you have finished.",
                                List.of(), null));
                    }
                }
                ChatRequest request = new ChatRequest(effectiveBaseUrl, apiKey, effectiveModel,
                        List.copyOf(wireMessages), List.copyOf(requestTools), responseFormat,
                        iterationReasoningEffort);
                StringBuilder buf = new StringBuilder();
                boolean[] decided = {false};
                boolean[] streaming = {false};
                ChatResult result;
                try {
                    result = chatWithReasoningEffortRetry(client, request, delta -> {
                        if (turnGeneration != turnEpoch) {
                            return;
                        }
                        buf.append(delta);
                        if (schemaMode) {
                            // Every reply is a JSON envelope; the message field is
                            // emitted once the turn resolves.
                            return;
                        }
                        if (!decided[0]) {
                            String lead = buf.toString().stripLeading();
                            if (lead.isEmpty()) {
                                return;
                            }
                            char c = lead.charAt(0);
                            boolean looksJson = (c == '{' || c == '[' || lead.startsWith("```"));
                            decided[0] = true;
                            streaming[0] = !looksJson;
                            if (streaming[0]) {
                                listener.onAiProcessEvent(new TextDeltaEvent(buf.toString(), null));
                            }
                            return;
                        }
                        if (streaming[0]) {
                            listener.onAiProcessEvent(new TextDeltaEvent(delta, null));
                        }
                    });
                } catch (OpenAiToolCallParseException parseEx) {
                    // The server rejected the WHOLE request because the model's tool call
                    // was not valid JSON — a recoverable model mistake, not a transport
                    // failure. No parseable response ever reached us, so the malformed call
                    // exists only in the server's error body; feed the parse error back as a
                    // tool result (exactly like the schema-mode recovery below) so the model
                    // can repair the call on the next iteration instead of the turn dying
                    // with nothing.
                    String toolCallErrorHint = "Error: the server rejected your tool call before it could run"
                            + " — its arguments were not valid JSON (" + parseEx.parseError() + ")."
                            + " Fix the arguments and call the tool again, or reply in plain text.";
                    appendMalformedToolCallRecovery(localBroker, toolCallErrorHint);
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.INFO, "Ollama server rejected a malformed tool call; the parse error was fed "
                                + "back to the model as a tool result: {0}", toolCallErrorHint);
                    }
                    // Malformed calls are recoverable; the model receives the error and may correct it.
                    // But a model that cannot emit valid JSON twice running will not manage it on the
                    // third attempt, so bound the retries and fail exactly as a genuine transport error
                    // would — which is how this 500 behaved before the recovery path existed.
                    if (++malformedToolCallRounds >= MAX_MALFORMED_TOOL_CALL_ROUNDS) {
                        throw parseEx;
                    }
                    continue;
                }
                localBroker.recordUsage(estimatedForRequest, result.promptTokens());
                listener.onAiProcessEvent(new OllamaTokenUsageEvent(
                        localBroker.estimatedTokenTotal(), contextWindowForEvent(localBroker, lastResolvedSettings.tokenThreshold())));
                List<ExtractedToolCall> calls;
                String assistantText;
                String toolCallError = null;
                boolean schemaShaped = false;
                if (schemaMode) {
                    // The answer is the schema's message field; the raw content is
                    // a JSON envelope the user must never see.
                    SchemaToolCalls.Reply reply = SchemaToolCalls.parse(result, knownToolNames);
                    calls = reply.calls();
                    assistantText = reply.message();
                    toolCallError = reply.toolCallError();
                    schemaShaped = reply.spokeProtocol();
                } else {
                    calls = ToolCallExtractor.extract(result, knownToolNames);
                    assistantText = result.assistantText();
                }
                if (calls.isEmpty() && toolCallError != null) {
                    // The model tried to call a tool and got the envelope wrong.
                    // Answer it the way a failing tool would, so it can correct
                    // itself on the next turn instead of the attempt vanishing.
                    // The correction itself stays between us and the model - the
                    // user is only told if we give up on it below, because a turn
                    // that ends with no answer and no explanation looks like a bug.
                    appendMalformedToolCallRecovery(localBroker, toolCallError);
                    // Same bound as the server-side parse failures, and for the same reason: a model
                    // that cannot get the envelope right twice running will not manage it on the third.
                    // Without this the counter never advanced on THIS path, so a schema-shaped malformed
                    // reply looped all the way to the iteration cap — the declared bound did not govern
                    // the path it claimed to.
                    if (++malformedToolCallRounds >= MAX_MALFORMED_TOOL_CALL_ROUNDS) {
                        if (!cancelledByUser) {
                            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                    "The model kept sending malformed tool calls instead of an answer"));
                            listener.onAiProcessEvent(new TurnCompleteEvent());
                        }
                        return;
                    }
                    continue;
                }
                ExtractedToolCall endTurnCall = calls.stream()
                        .filter(call -> END_TURN_TOOL_NAME.equals(call.name()))
                        .findFirst().orElse(null);
                if (endTurnCall != null) {
                    String finalText = assistantText;
                    try {
                        JsonObject arguments = JsonParser.parseString(endTurnCall.argumentsJson()).getAsJsonObject();
                        if (arguments.has(OpenAiJsonKeyEnum.MESSAGE.key())
                                && !arguments.get(OpenAiJsonKeyEnum.MESSAGE.key()).isJsonNull()
                                && !arguments.get(OpenAiJsonKeyEnum.MESSAGE.key()).getAsString().isBlank()) {
                            finalText = arguments.get(OpenAiJsonKeyEnum.MESSAGE.key()).getAsString();
                        }
                    } catch (RuntimeException ex) {
                        // A malformed synthetic completion request falls back to the schema envelope's message.
                    }
                    if (finalText != null && !finalText.isBlank()) {
                        if (schemaMode || !streaming[0]) {
                            listener.onAiProcessEvent(new TextDeltaEvent(finalText, null));
                        }
                        localBroker.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, finalText, List.of(), null));
                    }
                    // A Stop that lands on the model's final EndTurn must not commit it: every other exit
                    // suppresses the completion on cancel, and a TurnCompleteEvent after Stop contradicts
                    // the user. The finally's rollback below removes the appended answer.
                    if (cancelledByUser) {
                        return;
                    }
                    localBroker.commitTurn();
                    turnCommitted = true;
                    listener.onAiProcessEvent(new TurnCompleteEvent());
                    return;
                }
                calls = calls.stream()
                        .filter(call -> !END_TURN_TOOL_NAME.equals(call.name()))
                        .toList();
                if (calls.isEmpty()) {
                    // A model that has run out of ideas emits "{}" rather than an answer. Streaming was
                    // suppressed because it opened like a tool call, and showing the user a bare "{}" as
                    // the reply is worse than telling them nothing came back. Guard restored after the
                    // EndTurn rewrite dropped it — emptyJsonObjectIsNotPresentedAsTheAnswer caught it.
                    if (!streaming[0] && isEmptyJson(assistantText)) {
                        if (!cancelledByUser) {
                            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                    "The model returned an empty response instead of an answer"));
                            listener.onAiProcessEvent(new TurnCompleteEvent());
                        }
                        return;
                    }
                    if (assistantText != null && !assistantText.isBlank()) {
                        if (schemaMode || !streaming[0]) {
                            listener.onAiProcessEvent(new TextDeltaEvent(assistantText, null));
                        }
                        localBroker.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, assistantText,
                                List.of(), null));
                    }
                    boolean repeatedAssistantText = assistantText != null
                            && assistantText.equals(previousAssistantText);
                    if (!schemaMode && nudgeSentOnPreviousRequest
                            && assistantText != null && !assistantText.isBlank()) {
                        if (cancelledByUser) {
                            return;
                        }
                        localBroker.commitTurn();
                        turnCommitted = true;
                        listener.onAiProcessEvent(new TurnCompleteEvent());
                        return;
                    }
                    previousAssistantText = assistantText;
                    if (repeatedAssistantText && ++unproductiveRounds >= maxUnproductiveRounds) {
                        // Counter A, prose half: the reply is exactly what the model already said last
                        // round — the "no new information" signal. A cancel racing this bound must not
                        // produce a TurnCompleteEvent — the user already stopped the turn and a
                        // completion would contradict them.
                        if (cancelledByUser) {
                            return;
                        }
                        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                "Stopped: the model kept repeating itself without making progress"));
                        localBroker.commitTurn();
                        turnCommitted = true;
                        listener.onAiProcessEvent(new TurnCompleteEvent());
                        return;
                    }
                    if (!repeatedAssistantText) {
                        unproductiveRounds = 0;
                    }
                    if (++narrationRounds >= maxNarrationTurns) {
                        // A cancel racing this bound must not produce a TurnCompleteEvent — the user
                        // already stopped the turn and a completion would contradict them.
                        if (cancelledByUser) {
                            return;
                        }
                        // Say so. Without this a stalled turn is indistinguishable from a finished one:
                        // the narration the user was reading as a fragment is simply followed by a normal
                        // completion. The empty-JSON and unproductive-round exits both explain themselves;
                        // this one must too.
                        //
                        // Deliberately NOT answerWithoutTools here. In this path the model has declined to
                        // call tools across consecutive rounds, and taking the tools away to demand prose
                        // is the exact situation that produced fabricated answers from devstral:24b. The
                        // unproductive-round exit can ask, because there the model did real tool work to
                        // summarise; here there is nothing to summarise.
                        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                "Stopped: the model answered without signalling it had finished"));
                        localBroker.commitTurn();
                        turnCommitted = true;
                        listener.onAiProcessEvent(new TurnCompleteEvent());
                        return;
                    }
                    continue;
                }
                /* count this unproductive tool round after collecting its results */
                // Narration alongside a tool call is only safe to show when the model SPOKE THE
                // PROTOCOL, because then message is a field distinct from the call. When the call was
                // scraped out of free text by ToolCallExtractor, assistantText IS the call's raw JSON —
                // emitting it would put {"name":"GetPluginVersion",...} in front of the user.
                if (schemaShaped && assistantText != null && !assistantText.isBlank()) {
                    listener.onAiProcessEvent(new TextDeltaEvent(assistantText, null));
                }
                List<ChatToolCall> assistantToolCalls = new ArrayList<>();
                List<String> toolResults = new ArrayList<>();
                boolean madeProgress = false;
                for (int callIndex = 0; callIndex < calls.size(); callIndex++) {
                    if (cancelledByUser) {
                        break;
                    }
                    ExtractedToolCall call = calls.get(callIndex);
                    // callIndex alone repeats "call_0" on every iteration, and a turn that produces tool calls on
                    // two iterations ends up with two ASSISTANT calls and two results sharing one id — the
                    // results are matched to calls by id, so the pairing becomes ambiguous and stricter endpoints
                    // reject the duplicate outright. Iteration-qualified ids stay unique for the whole turn.
                    String callId = "call_" + iteration + "_" + callIndex;
                    assistantToolCalls.add(new ChatToolCall(callId, call.name(), call.argumentsJson()));
                    if (!executedCalls.add(call.name() + '(' + call.argumentsJson() + ')')) {
                        // Re-running it would repeat any side effect for no new
                        // information, so answer the model instead of the tool.
                        toolResults.add("Error: you already called " + call.name()
                                + " with these exact arguments during this turn and it succeeded. "
                                + "Calling it again changes nothing. Reply to the user in plain text now.");
                        continue;
                    }
                    listener.onAiProcessEvent(new ToolUseEvent(call.name(), null, "", null,
                            ToolUseEvent.Kind.OTHER));
                    Map<String, Integer> allDups = new java.util.HashMap<>(call.duplicateCounts() == null ? Map.of() : call.duplicateCounts());
                    allDups.putAll(RawJsonArgumentScanner.duplicateTopLevelKeys(call.argumentsJson()));
                    String toolResult;
                    if (!allDups.isEmpty()) {
                        toolResult = McpToolInvoker.duplicateParametersMessage(call.name(), allDups);
                    } else {
                        JsonObject args;
                        try {
                            args = JsonParser.parseString(call.argumentsJson()).getAsJsonObject();
                        } catch (RuntimeException ex) {
                            args = new JsonObject();
                        }
                        toolResult = bridge.invokeTool(call.name(), args);
                    }
                    toolResults.add(toolResult);
                    if (seenResults.add(call.name() + " => " + toolResult)) {
                        madeProgress = true;
                    }
                }
                // Native tool calling: an assistant message must be text OR tool calls, never both (Mistral
                // TemplateConfig.forbids_assistant_content_with_tools). The call itself is the message then —
                // commit it alone, with blank content: the narration is DROPPED, never split into a separate
                // preceding assistant entry (that breaks devstral's strict alternation). What was said was
                // already streamed to the user as TextDeltaEvents. Schema mode keeps the narration, because
                // there it is a distinct protocol field the model asked for.
                String toolRoundText = schemaMode || assistantToolCalls.isEmpty() ? assistantText : null;
                localBroker.appendAssistant(new ChatMessage(ChatRole.ASSISTANT, toolRoundText,
                        List.copyOf(assistantToolCalls), null));
                for (int callIndex = 0; callIndex < assistantToolCalls.size(); callIndex++) {
                    ChatToolCall toolCall = assistantToolCalls.get(callIndex);
                    localBroker.append(new ChatMessage(ChatRole.TOOL, toolResults.get(callIndex),
                            List.of(), toolCall.id()));
                }
                previousAssistantText = assistantText;
                if (madeProgress) {
                    // A productive round is NEW information: it neither repeats the text nor spins on
                    // already-seen results, so it resets both counters — the model is demonstrably still
                    // working and signalling that work through tool calls.
                    unproductiveRounds = 0;
                    narrationRounds = 0;
                } else if (++unproductiveRounds >= maxUnproductiveRounds) {
                    // Counter A, tool half: every result this round was already seen this turn, so the
                    // call told the model nothing new. A cancelled turn is not a stalled one: the user
                    // already knows why it stopped, and firing a fallback request or a TurnCompleteEvent
                    // here would contradict the Cancel they just pressed. Leave silently, exactly as the
                    // old barren-rounds exit did.
                    if (cancelledByUser) {
                        return;
                    }
                    // The model is calling tools but learning nothing new. Ending here would leave the
                    // user with only a status line, so ask once more with no tools offered — it can
                    // then only answer in prose. This is the behaviour the old barren-rounds exit had.
                    if (answerWithoutTools(client, effectiveBaseUrl, resolveApiKey(settings), effectiveModel,
                            applyThinkingCapabilityValidation(resolveEffectiveReasoningEffort(settings),
                                    effectiveBaseUrl, effectiveModel),
                            localBroker)) {
                        localBroker.commitTurn();
                        turnCommitted = true;
                    } else if (!cancelledByUser) {
                        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                "Stopped: the model kept repeating the same tool call without making progress"));
                    }
                    listener.onAiProcessEvent(new TurnCompleteEvent());
                    return;
                }
            }
            // Reached only when the hard cap stopped a runaway — every normal exit above returns.
            // Without this the turn would fall through to the finally block, roll back, and leave the
            // user with no reply and no explanation.
            if (!cancelledByUser) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "Stopped after " + maxToolIterations + " tool iterations"));
                if (answerWithoutTools(client, effectiveBaseUrl, resolveApiKey(settings), effectiveModel,
                        applyThinkingCapabilityValidation(resolveEffectiveReasoningEffort(settings),
                                effectiveBaseUrl, effectiveModel),
                        localBroker)) {
                    localBroker.commitTurn();
                    turnCommitted = true;
                }
                listener.onAiProcessEvent(new TurnCompleteEvent());
            }
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Ollama send failed", ex);
            if (!cancelledByUser) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        StatusMessageUtil.formatSendFailed(describe(ex))));
            }
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "Ollama send failed", ex);
            if (!cancelledByUser) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        StatusMessageUtil.formatSendFailed(describe(ex))));
            }
        } finally {
            // ONLY the thread that owns this turn may tear it down.
            //
            // interrupt() and stop() now leave `processing` set until THIS thread's finally runs, so the
            // sendPrompt gate cannot re-open while the turn is still unwinding (for an HTTP call in flight
            // that is the rest of the timeout, not microseconds). The guard still earns its keep as
            // belt-and-braces: a thread that ran this body unconditionally would
            //   - call rollbackTurn(), which rolls back whatever group is CURRENTLY open — a later turn's —
            //     and deletes the user message that turn just appended, orphaning everything that follows it
            //   - set processing=false and activeTurnThread=null, so a turn that somehow started underneath
            //     it loses its owner reference and the next Stop finds no thread and is silently lost
            //
            // Comparing against the published owner reference costs nothing and needs no lock. The epoch
            // guard stops a stale thread's LOOP; this stops a stale thread's TEARDOWN.
            if (Thread.currentThread() == activeTurnThread) {
                AbstractChatContextBroker brokerSnap = broker;
                if (brokerSnap != null) {
                    brokerSnap.rollbackTurn();
                    // The rolled-back turn discards the mail notice its loop-top injected, and the flag was
                    // cleared the moment it was injected — so without this the nudge would silently vanish.
                    // Re-arm for the next turn; idempotent if new mail armed the flag again meanwhile. Never
                    // on a committed turn, where the notice was committed with the turn and must not re-arm.
                    if (!turnCommitted && mailNoticeConsumedThisTurn) {
                        pendingMailNotice = true;
                    }
                }
                // Order matters: publish the null OWNER reference before re-opening the gate, so no sendPrompt
                // can observe processing==false and then have this thread overwrite the reference it just set.
                activeTurnThread = null;
                processing = false;
            }
        }
    }

    /**
     * Last resort when the tool loop ends without an answer: ask once more with an empty tool list, so the
     * model has nothing to call and must reply in prose. Without this the user is left with only a status
     * line.
     *
     * @return true if a non-empty answer was produced and emitted
     */
    private boolean answerWithoutTools(HttpAiClient client, String baseUrl, String apiKey,
            String model, String reasoningEffort, AbstractChatContextBroker localBroker) throws IOException {
        List<ChatMessage> prompt = new ArrayList<>(localBroker.snapshot());
        prompt.add(new ChatMessage(ChatRole.USER,
                "Stop calling tools. Answer my original message directly, in plain text.",
                List.of(), null));
        StringBuilder buf = new StringBuilder();
        ChatResult result = chatWithReasoningEffortRetry(client,
                new ChatRequest(baseUrl, apiKey, model, List.copyOf(prompt), List.of(), null, reasoningEffort),
                delta -> {
                    if (!cancelledByUser) {
                        buf.append(delta);
                    }
                });
        String text = buf.length() > 0 ? buf.toString() : result.assistantText();
        if (cancelledByUser || text == null || text.isBlank() || isEmptyJson(text)) {
            return false;
        }
        listener.onAiProcessEvent(new TextDeltaEvent(text, null));
        localBroker.append(new ChatMessage(ChatRole.ASSISTANT, text, List.of(), null));
        return true;
    }

    boolean registerMcp(OllamaMcpRegistrar reg) throws Exception {
        return McpServerRegistry.register(reg).get(2, TimeUnit.MINUTES);
    }

    HttpAiClient createHttpAiClient() {
        return new OpenAiCompatibleClient();
    }

    OllamaMcpBridge createBridge(OllamaAiSession session) {
        return new OllamaMcpBridge(session);
    }

    private static int contextWindowForEvent(AbstractChatContextBroker broker, int fallback) {
        int discovered = broker == null ? 0 : broker.contextLimitForDisplay();
        return discovered > 0 ? discovered : fallback;
    }

    AbstractChatContextBroker createContextBroker(String sessionId, ContextBrokerSettings settings) {
        return new OllamaChatContextBroker(sessionId, settings);
    }

    /**
     * The context file lives beside history.json in the same per-session directory, so it shares
     * SessionPersistenceManager's base directory rather than inventing a new location.
     */
    ContextPersistenceManager createContextPersistenceManager() {
        return new ContextPersistenceManager(SessionPersistenceManager.defaultBaseDir());
    }

    /**
     * Three-level fallback per setting: session value if set, else the global default, else the hardcoded
     * default baked into ContextBrokerSettings. Session values are nullable precisely so "unset" is
     * distinguishable from "set to zero/false".
     */
    ContextBrokerSettings resolveBrokerSettings() {
        ContextBrokerSettings s = ContextBrokerSettings.defaults();
        OpenAiClientSessionSettings cfg
                = currentSession != null
                && currentSession.settings() instanceof OpenAiClientSessionSettings o
                ? o : null;

        s.setTrigger(cfg != null && cfg.contextTrimTrigger() != null
                ? cfg.contextTrimTrigger()
                : parseTrigger(PluginSettings.getContextTrimTrigger()));
        s.setStrategy(cfg != null && cfg.contextTrimStrategy() != null
                ? cfg.contextTrimStrategy()
                : parseStrategy(PluginSettings.getContextTrimStrategy()));
        s.setTokenThreshold(cfg != null && cfg.contextTokenThreshold() != null
                ? cfg.contextTokenThreshold()
                : PluginSettings.getContextTokenThreshold());
        s.setTrimTargetPercent(cfg != null && cfg.contextTrimTargetPercent() != null
                ? cfg.contextTrimTargetPercent()
                : PluginSettings.getContextTrimTargetPercent());
        s.setMaxMessages(cfg != null && cfg.contextMaxMessages() != null
                ? cfg.contextMaxMessages()
                : PluginSettings.getContextMaxMessages());
        s.setPersistOnClose(cfg instanceof OllamaSessionSettings ollama
                ? ollama.effectiveContextPersistOnClose()
                : cfg != null && cfg.contextPersistOnClose() != null
                ? cfg.contextPersistOnClose()
                : PluginSettings.isContextPersistOnClose());
        return s;
    }

    ContextBrokerSettings brokerSettingsForTest() {
        return lastResolvedSettings;
    }

    Map<McpToolEnum, McpToolInterface> buildToolHandlers(OllamaAiSession session) {
        return session.getMcpToolHandlers();
    }

    protected String displayName() {
        return "Ollama (Local)";
    }

    protected String defaultModel() {
        return OllamaPluginSettings.getModel();
    }

    protected String defaultBaseUrl() {
        return OllamaPluginSettings.getBaseUrl();
    }

    protected String resolveApiKey(OllamaSessionSettings settings) {
        return null;
    }

    protected boolean validateStart() {
        return true;
    }

    @Override
    public synchronized void interrupt(InterruptTypeEnum type) {
        if (type == InterruptTypeEnum.Cancel) {
            // Stamping the moment the user actually pressed Stop is the only way to
            // measure the wind-down tail afterwards: without it, "it carried on
            // after I stopped it" cannot be told apart from a normal wind-down, and
            // the agent's own log gives no click time to compare against. Ollama has
            // no processing-gated early return here (unlike the other AI types) —
            // turnInFlight/threadAlive are logged so "Stop did nothing" and "Stop
            // acted" can still be told apart afterwards.
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "Ollama interrupt: user pressed Stop (session={0}, turnInFlight={1}, threadAlive={2})",
                        new Object[]{sessionId, processing, activeTurnThread != null});
            }
            cancelledByUser = true;
            // Bump the turn generation so an in-flight loop and its streaming callback terminate at their
            // next guard, which compare against the epoch the turn was STARTED with. processing stays set:
            // only the thread that owns the turn may clear it, in its finally — if it were cleared here the
            // sendPrompt gate would re-open while this thread is still unwinding and a second turn could
            // start underneath it.
            turnGeneration++;
            Thread turnThread = activeTurnThread;
            if (turnThread != null) {
                turnThread.interrupt();
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "Ollama interrupt: turn thread interrupted (session={0})", sessionId);
                }
            }
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED,
                    StatusMessageUtil.formatStopped()));
        } else if (type == InterruptTypeEnum.Mail) {
            // A Mail interrupt carries no payload — it only nudges the running turn to check the inbox.
            // Arm a flag: the tool-loop's next iteration appends MAIL_NOTIFICATION_TEXT as a USER message
            // and the assistant fetches the mail itself with GetAiMessages. If the turn ends first the
            // flag stays armed, so the notice is delivered at the start of the next turn instead of being
            // lost.
            pendingMailNotice = true;
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "Ollama interrupt: Mail notice QUEUED for delivery at the next tool-loop iteration (session={0})", sessionId);
            }
        } else if (PluginSettings.isDebugJson()) {
            // Defensive fallback: Cancel and Mail are the only interrupt types today, so an unknown one
            // must not silently no-op.
            LOG.log(Level.INFO, "Ollama interrupt: unknown interrupt type ({0}) (session={1})",
                    new Object[]{type, sessionId});
        }
    }

    @Override
    public synchronized void stop() {
        // Logged before the state is torn down, so the record says what was
        // actually in flight at the moment of the stop rather than the
        // cleared-out aftermath.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "Ollama stop: shutting session down (session={0}, turnInFlight={1}, threadAlive={2})",
                    new Object[]{sessionId, processing, activeTurnThread != null});
        }
        cancelledByUser = true;
        turnGeneration++;
        running = false;
        processing = false;
        pinnedOverBudgetWarned = false;
        reasoningEffortDisabledForSession = false;
        Thread turnThread = activeTurnThread;
        activeTurnThread = null;
        if (turnThread != null) {
            turnThread.interrupt();
        }
        if (ollamaSession != null) {
            ollamaSession.dispose();
        }
        ollamaSession = null;
        bridge = null;
        AbstractChatContextBroker brokerSnap = broker;
        if (brokerSnap instanceof OllamaChatContextBroker ollamaBroker) {
            ollamaBroker.close();
        }
        if (brokerSnap != null) {
            // Rollback before save: saving first would write a half-finished
            // turn to disk, and it would come back as a ghost user message
            // with no reply — a lone USER entry has no orphaned tool results,
            // so the group-integrity check on load would not catch it.
            brokerSnap.rollbackTurn();
            if (lastResolvedSettings != null && lastResolvedSettings.persistOnClose()
                    && sessionId != null) {
                try {
                    createContextPersistenceManager().save(sessionId, brokerSnap.toJson());
                } catch (IOException ex) {
                    LOG.log(Level.WARNING, "Could not persist context", ex);
                }
            }
        }
        broker = null;
        httpClient = null;
        if (registrar != null) {
            McpServerRegistry.deregister(registrar);
        }
        registrar = null;
        sessionId = null;
        sessionWorkingDir = null;
        pendingDiff = false;
        sessionConfigDir = null;
    }

    @Override
    public void resumeSession(String existingSessionId) {
        if (existingSessionId != null && !existingSessionId.isBlank()) {
            sessionId = existingSessionId;
        }
    }

    @Override
    public void updatePinnedContext(String identity, String baseline, String instructions) {
        AbstractChatContextBroker localBroker = broker;
        if (localBroker == null) {
            return;
        }
        localBroker.upsertPin(PinSlotEnum.IDENTITY, identity);
        localBroker.upsertPin(PinSlotEnum.BASELINE, baseline);
        localBroker.upsertPin(PinSlotEnum.INSTRUCTIONS, instructions);
    }

    /**
     * Wipes the model's memory of the conversation. The chat panel keeps showing the full transcript, so
     * without an inline notice a user who scrolls up and references an earlier exchange gets a baffled reply
     * — before this feature the visible log and the model's memory always agreed, so this divergence needs
     * calling out.
     */
    public void clearContext() {
        AbstractChatContextBroker b = broker;
        if (b == null) {
            return;
        }
        b.clearHistory();
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                "Context cleared — the model no longer has the earlier conversation"));
        ContextBrokerSettings resolved = lastResolvedSettings;
        if (resolved != null) {
            listener.onAiProcessEvent(new OllamaTokenUsageEvent(
                    b.estimatedTokenTotal(), contextWindowForEvent(b, resolved.tokenThreshold())));
        }
    }

    /**
     * Trims older context down to the low-water mark right now, ignoring the threshold. Summarises the
     * evicted span when a summariser is available (it is, unconditionally, once start() has run), falling
     * back to a drop marker otherwise.
     *
     * Runs on a background thread: summarising makes a network call that can take seconds, and this is
     * invoked straight from the info bar's Compact button, on the EDT. Completion is reported the same way a
     * turn reports usage — an OllamaTokenUsageEvent — so the info bar's existing handling of that event also
     * clears the gauge's indeterminate state.
     */
    public void compactContext() {
        AbstractChatContextBroker b = broker;
        if (b == null) {
            return;
        }
        Thread worker = new Thread(() -> {
            int evicted = b.compactNow();
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    evicted == 0
                            ? "Nothing to compact"
                            : "Compacted — " + evicted + " earlier exchange(s) summarised"));
            ContextBrokerSettings resolved = lastResolvedSettings;
            if (resolved != null) {
                listener.onAiProcessEvent(new OllamaTokenUsageEvent(
                        b.estimatedTokenTotal(), resolved.tokenThreshold()));
            }
        }, "ollama-compact-" + sessionId);
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Polled by the info bar to keep the gauge's indeterminate state honest.
     */
    public boolean isSummarising() {
        AbstractChatContextBroker b = broker;
        return b != null && b.isSummarising();
    }

    @Override
    public boolean isMcpActive() {
        return registrar != null;
    }
}

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

    static final int MAX_TOOL_ITERATIONS = 25;

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
    private volatile ContextBrokerSettings lastResolvedSettings;
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
                        broker.estimatedTokenTotal(), settingsForBroker.tokenThreshold()));
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
        processing = true;
        if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
            sessionWorkingDir = workingDir;
        }
        Thread worker = new Thread(() -> runTurn(text), "ollama-turn-" + sessionId);
        worker.setDaemon(true);
        activeTurnThread = worker;
        worker.start();
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

    private void runTurn(String text) {
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

            // Populating the tools array makes this backend call something on
            // every turn regardless of the request, so under TOOL_CALLS_VIA_SCHEMA
            // the tools are described in the prompt and the reply is constrained
            // by a schema instead. See SchemaToolCalls.
            boolean schemaMode = currentSession.aiType().getMcpOptions()
                    .contains(McpInstructionOptionEnum.TOOL_CALLS_VIA_SCHEMA);
            JsonObject responseFormat = schemaMode ? SchemaToolCalls.responseFormat(knownToolNames) : null;
            List<JsonObject> requestTools = schemaMode ? List.of() : tools;
            if (schemaMode) {
                instructions = instructions
                        + "\n\n## Calling a tool\n"
                        + "These are the tools you can call, with their parameters"
                        + " (those in [square brackets] are optional; use each name"
                        + " exactly as written):\n"
                        + SchemaToolCalls.renderToolList(tools)
                        + "\nReply as JSON. To call one tool, set tool_name and tool_arguments"
                        + " and leave message empty. To answer the user, put your reply in"
                        + " message and set tool_name to \"\". Never do both.";
            }

            AbstractChatContextBroker localBroker = broker;
            if (localBroker == null) {
                return;
            }
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
            int barrenRounds = 0;
            for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS && !cancelledByUser; iteration++) {
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
                ChatRequest request = new ChatRequest(effectiveBaseUrl, apiKey, effectiveModel,
                        localBroker.snapshot(), List.copyOf(requestTools), responseFormat,
                        iterationReasoningEffort);
                StringBuilder buf = new StringBuilder();
                boolean[] decided = {false};
                boolean[] streaming = {false};
                ChatResult result;
                try {
                    result = chatWithReasoningEffortRetry(client, request, delta -> {
                        if (cancelledByUser) {
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
                    barrenRounds++;
                    if (barrenRounds >= 2) {
                        // A model that keeps emitting unparseable tool calls must not spin
                        // forever. Bound the retries and fail exactly as a genuine transport
                        // error would today — the same way this 500 failed before the
                        // recovery existed.
                        throw parseEx;
                    }
                    continue;
                }
                localBroker.recordUsage(estimatedForRequest, result.promptTokens());
                listener.onAiProcessEvent(new OllamaTokenUsageEvent(
                        localBroker.estimatedTokenTotal(), lastResolvedSettings.tokenThreshold()));
                List<ExtractedToolCall> calls;
                String assistantText;
                String toolCallError = null;
                if (schemaMode) {
                    // The answer is the schema's message field; the raw content is
                    // a JSON envelope the user must never see.
                    SchemaToolCalls.Reply reply = SchemaToolCalls.parse(result, knownToolNames);
                    calls = reply.calls();
                    assistantText = reply.message();
                    toolCallError = reply.toolCallError();
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
                    barrenRounds++;
                    if (barrenRounds >= 2) {
                        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                "The model kept sending malformed tool calls instead of an answer"));
                        listener.onAiProcessEvent(new TurnCompleteEvent());
                        return;
                    }
                    continue;
                }
                if (calls.isEmpty()) {
                    String finalText = assistantText;
                    if (!streaming[0] && isEmptyJson(finalText)) {
                        // A model that has run out of ideas emits "{}" rather than
                        // an answer. Streaming was suppressed because it opened
                        // like a tool call, and showing the user a bare "{}" as the
                        // reply is worse than telling them nothing came back.
                        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                "The model returned an empty response instead of an answer"));
                        listener.onAiProcessEvent(new TurnCompleteEvent());
                        return;
                    }
                    if (!streaming[0] && finalText != null && !finalText.isBlank()) {
                        listener.onAiProcessEvent(new TextDeltaEvent(finalText, null));
                    }
                    if (finalText != null && !finalText.isBlank()) {
                        localBroker.append(new ChatMessage(ChatRole.ASSISTANT, finalText,
                                List.of(), null));
                    }
                    localBroker.commitTurn();
                    listener.onAiProcessEvent(new TurnCompleteEvent());
                    return;
                }
                List<ChatToolCall> assistantToolCalls = new ArrayList<>();
                List<String> toolResults = new ArrayList<>();
                boolean madeProgress = false;
                for (int callIndex = 0; callIndex < calls.size(); callIndex++) {
                    if (cancelledByUser) {
                        break;
                    }
                    ExtractedToolCall call = calls.get(callIndex);
                    String callId = "call_" + callIndex;
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
                localBroker.append(new ChatMessage(ChatRole.ASSISTANT, null,
                        List.copyOf(assistantToolCalls), null));
                for (int callIndex = 0; callIndex < assistantToolCalls.size(); callIndex++) {
                    ChatToolCall toolCall = assistantToolCalls.get(callIndex);
                    localBroker.append(new ChatMessage(ChatRole.TOOL, toolResults.get(callIndex),
                            List.of(), toolCall.id()));
                }
                barrenRounds = madeProgress ? 0 : barrenRounds + 1;
                if (barrenRounds >= 2 && !cancelledByUser) {
                    // Ending here would leave the user with no reply at all, so
                    // ask once more with no tools offered — the model can only
                    // answer in prose.
                    if (!answerWithoutTools(client, effectiveBaseUrl, apiKey, effectiveModel,
                            applyThinkingCapabilityValidation(resolveEffectiveReasoningEffort(settings),
                                    effectiveBaseUrl, effectiveModel),
                            localBroker)) {
                        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                "Stopped: the model kept repeating the same tool call without making progress"));
                    } else {
                        localBroker.commitTurn();
                    }
                    listener.onAiProcessEvent(new TurnCompleteEvent());
                    return;
                }
            }
            if (!cancelledByUser) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "Stopped after " + MAX_TOOL_ITERATIONS + " tool iterations"));
                if (answerWithoutTools(client, effectiveBaseUrl, resolveApiKey(settings),
                        effectiveModel, applyThinkingCapabilityValidation(resolveEffectiveReasoningEffort(settings),
                                effectiveBaseUrl, effectiveModel),
                        localBroker)) {
                    localBroker.commitTurn();
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
            AbstractChatContextBroker brokerSnap = broker;
            if (brokerSnap != null) {
                brokerSnap.rollbackTurn();
            }
            processing = false;
            activeTurnThread = null;
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
        s.setPersistOnClose(cfg != null && cfg.contextPersistOnClose() != null
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
    public void interrupt(InterruptTypeEnum type) {
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
            processing = false;
            Thread turnThread = activeTurnThread;
            if (turnThread != null) {
                turnThread.interrupt();
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "Ollama interrupt: turn thread interrupted (session={0})", sessionId);
                }
            }
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED,
                    StatusMessageUtil.formatStopped()));
        } else if (PluginSettings.isDebugJson()) {
            // Legitimate no-op, not a bug: a single blocking OpenAI-compatible HTTP
            // request has no channel to inject a mid-turn notice into. Logged anyway —
            // a silent no-op is what let Codex's equivalent Mail drop go unnoticed for
            // so long.
            LOG.log(Level.INFO, "Ollama interrupt: Mail IGNORED, no channel to inject into (session={0})", sessionId);
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
                    b.estimatedTokenTotal(), resolved.tokenThreshold()));
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

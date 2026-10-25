package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnEndReason;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ToolCallingModeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ChatRequestSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ClientResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.CompatibilityFlags;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ErrorMapper;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.MappedError;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.OpenAiCompatibleHttpClient.DebugSink;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.RequestHandle;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ThinkTagStripper;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders.ExtraHeader;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools.ArgumentCoercion;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools.SchemaToolCalling;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools.ToolCallModeResolver;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools.ToolJsonRescue;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools.ToolResultCap;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;

/**
 * One turn of the OpenAI Compatible backend (spec §4): send, read the reply, and stop as soon as a reply carries no
 * tool call. There is no {@code EndTurn} tool and no narration nudge. Every way out of {@link #run} claims exactly one
 * {@link TurnEndReason} on the shared {@link TurnOutcome}; a Cancel claims it from another thread first, and the loop's
 * own later claim then loses.
 *
 * <p>
 * Tool calls run one at a time through the session's {@link ToolRunner}; their results are appended after the assistant
 * message that asked for them and the loop sends the next request. A reply with tool calls never ends the turn by
 * itself. The P2a limits bound the loop (spec §4 items 2, 4, 5, 8): {@code length} continuations, malformed tool-call
 * rounds, the optional tool-round cap (one last request without tools), and repetition / unproductive rounds.
 *
 * <p>
 * P2b (spec §4 items 2, 7, §5): arguments are coerced to the tool's schema before the call, results are capped as they
 * are appended, tool JSON in prose is rescued, and native tool calling falls back to schema mode — from the next turn
 * after repeated unrescued failures (counted in the session's {@link ToolCallingState}), or at once when the server
 * rejects tools outright. {@code <think>} text and reasoning fields are collected for the debug log and never sent
 * back.
 *
 * <p>
 * Runs on the session's turn thread and blocks it while a request or a tool is in flight. Cancel completes the request
 * future through the {@link RequestHandle} and interrupts this thread, so either wait always ends; a tool that is still
 * running is abandoned, and the next turn waits for it (bounded) before it starts.
 */
public final class TurnLoop {

    /**
     * Receives what the user sees while the turn runs.
     */
    public interface Listener {

        void textDelta(String text);

        /**
         * A one-off chat notice (a mode switch); not part of the answer.
         */
        default void notice(String text) {
        }
    }

    /**
     * Everything one request of this turn needs besides the messages. {@code tools} may be null when the turn offers no
     * tools.
     */
    public record RequestContext(String apiBase, String model, JsonArray tools, char[] apiKey,
                                 List<ExtraHeader> headers, ErrorMapper.KeySituation keySituation,
                                 SamplingOptions samplingOptions, Set<String> mutatingTools) {

        public RequestContext(String apiBase, String model, JsonArray tools, char[] apiKey,
                              List<ExtraHeader> headers, ErrorMapper.KeySituation keySituation) {
            this(apiBase, model, tools, apiKey, headers, keySituation, SamplingOptions.none(), Set.of());
        }

        public RequestContext(String apiBase, String model, JsonArray tools, char[] apiKey,
                              List<ExtraHeader> headers, ErrorMapper.KeySituation keySituation,
                              SamplingOptions samplingOptions) {
            this(apiBase, model, tools, apiKey, headers, keySituation, samplingOptions, Set.of());
        }

        public RequestContext {
            samplingOptions = samplingOptions == null ? SamplingOptions.none() : samplingOptions;
            mutatingTools = mutatingTools == null ? Set.of() : Set.copyOf(mutatingTools);
        }
    }

    /**
     * The per-session loop limits (spec §8.2). {@code maxToolRounds} 0 means unlimited.
     */
    public record Counts(int maxToolRounds, int maxLengthContinuations, int maxMalformedRounds,
                         int repetitionWindow, int unproductiveRounds) {

        public static Counts defaults() {
            return new Counts(OpenAiCompatibleSessionSettings.DEFAULT_MAX_TOOL_ROUNDS,
                              OpenAiCompatibleSessionSettings.DEFAULT_MAX_LENGTH_CONTINUATIONS,
                              OpenAiCompatibleSessionSettings.DEFAULT_MAX_MALFORMED_ROUNDS,
                              OpenAiCompatibleSessionSettings.DEFAULT_REPETITION_WINDOW,
                              OpenAiCompatibleSessionSettings.DEFAULT_UNPRODUCTIVE_ROUNDS);
        }
    }

    static final String EARLY_END_MESSAGE
            = "The server ended the reply early (partial text kept). Check the server log and try again.";
    static final String NO_TOOLS_MESSAGE = "The model asked for a tool, but no tools are available in this session.";
    static final String EMPTY_REPLY_NUDGE = "Please give your answer.";
    static final String EMPTY_REPLY_MESSAGE = "The model returned no text.";
    static final String LENGTH_TOOL_RESULT = "Your reply was cut off by the length limit — continue, in smaller pieces.";
    static final String CONTINUE_PROMPT = "Continue.";
    static final String STEP_LIMIT_PROMPT = "You have reached the tool-round limit for this turn. Reply now with your "
            + "answer in plain text; do not call any tools.";
    static final String STEP_LIMIT_REJECTED_NOTE = " The final request without tools was rejected by the server, so "
            + "there is no summary.";
    static final String SERVER_PARSE_ERROR_MARKER = "error parsing tool call";
    static final String MALFORMED_CALL_NAME = "unknown_tool";
    /**
     * The chat notice when the server rejects tools outright and the turn is resent in schema mode.
     */
    public static final String SWITCHED_IMMEDIATELY_MESSAGE
            = "This model doesn't support native tool calls; switched to schema tool calling.";
    static final String SCHEMA_PROTOCOL = "## Tool calling\n"
            + "Reply with exactly one JSON object and nothing else: "
            + "{\"message\": \"your text for the user\", \"tool_name\": \"one tool name, or an empty string\", "
            + "\"tool_arguments\": {}}. To call a tool, set tool_name and tool_arguments; its result arrives in the "
            + "next message. To answer, set tool_name to \"\" and put the whole answer in message.\n\n"
            + "Available tools:\n";
    static final String SCHEMA_RESULT_PREFIX = "Result of ";

    /**
     * The chat message when a Cancel lands while {@code toolName} runs.
     */
    public static String cancelledDuringToolMessage(String toolName) {
        return "Cancelled. " + toolName + " was still running; its result was discarded.";
    }

    /**
     * The refusal when a previous turn's abandoned tool is still running after the wait limit.
     */
    public static String previousToolStillRunningMessage(String toolName) {
        return "The previous " + toolName + " is still running — try again when it finishes.";
    }

    /**
     * The tool result fed back when the server could not parse the model's tool call.
     */
    static String serverParseErrorFeedback(String serverMessage) {
        return "Your tool call could not be parsed by the server (" + serverMessage + "). Send one valid tool call "
                + "with JSON arguments, or answer in plain text.";
    }

    private enum Phase {
        WAITING,
        GENERATING,
        RETRYING,
        TOOL,
        WAITING_FOR_PREVIOUS_TOOL,
        COMPACTING,
        THINKING
    }

    /**
     * The status label while a tool waits for the user's Accept/Reject (spec §7.4).
     */
    static final String APPROVAL_STATUS = "Approval…";
    /**
     * Each reasoning line shown in the chat starts with this (a quote block), so it reads apart from the answer.
     */
    static final String REASONING_LINE_PREFIX = "> ";

    private final List<ChatMessage> history;
    private final String systemPrompt;
    private final ChatClient client;
    private final CompatibilityFlags flags;
    private final OpenAiCompatibleLimits limits;
    private final RequestHandle handle;
    private final TurnOutcome outcome;
    private final RequestContext context;
    private final Listener listener;
    private final DebugSink debug;
    private final BooleanSupplier takeMail;
    private final ToolRunner tools;
    private final int turnNo;

    private Counts counts = Counts.defaults();
    private ToolCallingState toolState = new ToolCallingState();
    private ToolCallingModeEnum mode = ToolCallingModeEnum.NATIVE;
    private int toolResultCapChars;
    private TurnContext contextManager;
    private volatile BooleanSupplier awaitingApproval = () -> false;
    private boolean showReasoning;
    /**
     * How many empty replies get a nudge before the turn ends ANSWERED with the no-text message (spec §8.2).
     */
    private int maxEmptyReplyNudges = 1;
    /**
     * How many context-overflow compact-and-retry passes a turn may make (spec §8.2).
     */
    private int maxOverflowRetries = 1;
    /**
     * The Ollama {@code keep_alive} value; null when not sent (spec §8.2).
     */
    private String keepAlive;

    private volatile Phase phase = Phase.WAITING;
    private volatile long phaseStartNanos = System.nanoTime();
    private volatile String retryText = "";
    private volatile String phaseTool = "";
    private volatile int round;
    private boolean fullRequestLogged;
    private int lastLoggedMessageCount;

    // ---- Per-turn loop state (turn thread only) ----
    /**
     * Messages sent after the history in the next request only (a nudge, a continuation prompt), never stored.
     */
    private final List<ChatMessage> wireTail = new ArrayList<>();
    private final StringBuilder continuedProse = new StringBuilder();
    private final Set<String> seenResults = new HashSet<>();
    private final StringBuilder reasoning = new StringBuilder();
    private Map<String, JsonObject> inputSchemas = Map.of();
    private Set<String> previousInvalidCalls = Set.of();
    private RepetitionTracker repetition;
    private int nudgesUsed;
    private boolean finalRequest;
    private boolean immediateSwitchUsed;
    private int overflowRetriesUsed;
    private boolean compactBeforeNext;
    private int lastEstimate;
    private Integer lastPromptTokens;
    private Integer lastCompletionTokens;
    private int toolRounds;
    private int lengthContinuations;
    private int malformedRounds;

    // ---- Per-request streaming state ----
    private ThinkTagStripper streamStripper = new ThinkTagStripper();
    private final StringBuilder streamedText = new StringBuilder();
    private boolean schemaStreamDecided;
    private boolean schemaStreamBuffered;
    // ---- Reasoning echo to the chat (turn thread only) ----
    private boolean chatTextEmitted;
    private boolean reasoningBlockOpen;
    private boolean reasoningAtLineStart;
    /**
     * True once a reasoning-field delta streamed in this request, so the end-of-reply fallback does not repeat it.
     */
    private boolean reasoningFieldStreamed;

    /**
     * @param history the session's model-facing conversation; the turn appends the user's prompt and the replies to it
     * @param systemPrompt sent first in every request (not stored in {@code history}); may be blank
     * @param takeMail returns true once per queued mail notice and clears it (spec §4.1 item 1)
     * @param tools the session's tool runner; null when the session offers no tools
     * @param turnNo this turn's number in the session, used for synthesised tool-call ids
     */
    public TurnLoop(List<ChatMessage> history, String systemPrompt, ChatClient client, CompatibilityFlags flags,
                    OpenAiCompatibleLimits limits, RequestHandle handle, TurnOutcome outcome, RequestContext context,
                    Listener listener, DebugSink debug, BooleanSupplier takeMail, ToolRunner tools, int turnNo) {
        this.tools = tools;
        this.turnNo = turnNo;
        this.history = history;
        this.systemPrompt = systemPrompt;
        this.client = client;
        this.flags = flags;
        this.limits = limits;
        this.handle = handle;
        this.outcome = outcome;
        this.context = context;
        this.listener = listener;
        this.debug = debug == null ? DebugSink.NONE : debug;
        this.takeMail = takeMail == null ? () -> false : takeMail;
    }

    /**
     * Sets the loop limits for this turn (call before {@link #run}); the defaults are spec §8.2's.
     */
    public TurnLoop withCounts(Counts newCounts) {
        this.counts = newCounts == null ? Counts.defaults() : newCounts;
        return this;
    }

    /**
     * Sets the session's tool-calling state and the mode this turn starts in (NATIVE or SCHEMA; AUTO counts as NATIVE).
     */
    public TurnLoop withToolCalling(ToolCallingState state, ToolCallingModeEnum startMode) {
        this.toolState = state == null ? new ToolCallingState() : state;
        this.mode = startMode == ToolCallingModeEnum.SCHEMA ? ToolCallingModeEnum.SCHEMA : ToolCallingModeEnum.NATIVE;
        return this;
    }

    /**
     * Caps every tool result appended to the history at {@code maxChars} characters ({@link ToolResultCap}); 0 or less
     * means no cap.
     */
    public TurnLoop withToolResultCap(int maxChars) {
        this.toolResultCapChars = maxChars;
        return this;
    }

    /**
     * Sets the session's context management (compaction before each request, the overflow retry, the overhead check and
     * the truncation guard); null leaves the history alone.
     */
    public TurnLoop withContext(TurnContext turnContext) {
        this.contextManager = turnContext;
        return this;
    }

    /**
     * Tells the status line when a running tool is waiting for the user's Accept/Reject (a pending diff or question):
     * it then reads "Approval…" instead of "&lt;Tool&gt; N s". Polled by the status tick, from any thread.
     */
    public TurnLoop withApprovalWait(BooleanSupplier waitingForUser) {
        this.awaitingApproval = waitingForUser == null ? () -> false : waitingForUser;
        return this;
    }

    /**
     * Shows the model's reasoning ({@code <think>} text live, reasoning fields when the reply ends) in the chat as a
     * quote block. Off by default. Display only: reasoning never reaches the model-facing history.
     */
    public TurnLoop withShowReasoning(boolean show) {
        this.showReasoning = show;
        return this;
    }

    /**
     * Sets how many empty replies are nudged before the turn ends with the no-text message. The default is one (spec
     * §8.2); 0 ends at the first empty reply.
     */
    public TurnLoop withEmptyReplyNudges(int count) {
        this.maxEmptyReplyNudges = Math.max(0, count);
        return this;
    }

    /**
     * Sets how many context-overflow compact-and-retry passes a turn may make. The default is one (spec §8.2); 0 means
     * the first overflow ends the turn.
     */
    public TurnLoop withOverflowRetries(int count) {
        this.maxOverflowRetries = Math.max(0, count);
        return this;
    }

    /**
     * Sets the Ollama {@code keep_alive} value sent with every request. Null or blank means it is not sent (spec §8.2).
     */
    public TurnLoop withKeepAlive(String value) {
        this.keepAlive = value == null || value.isBlank() ? null : value.strip();
        return this;
    }

    /**
     * The mode the turn is in now; SCHEMA after an immediate switch.
     */
    public ToolCallingModeEnum mode() {
        return mode;
    }

    /**
     * Reasoning collected this turn ({@code <think>} text and reasoning fields); never sent back to the model.
     */
    public String reasoning() {
        return reasoning.toString();
    }

    /**
     * Runs the turn to its end and returns the outcome, which always holds a reason when this returns.
     */
    public TurnOutcome run(String userText) {
        if (!waitForPreviousTool()) {
            debug.event("Turn ended before it started: " + outcome.reason());
            return outcome;
        }
        repetition = new RepetitionTracker(Math.max(2, counts.repetitionWindow()),
                                           Math.max(1, counts.unproductiveRounds()));
        inputSchemas = inputSchemasOf(context.tools());
        history.add(new ChatMessage(ChatRole.USER, userText, List.of(), null));
        if (!overheadFits()) {
            debug.event("Turn ended before sending: " + outcome.reason());
            return outcome;
        }
        while (outcome.reason() == null) {
            if (handle.isCancelled() || Thread.currentThread().isInterrupted()) {
                claim(TurnEndReason.CANCELLED, TurnEndReason.CANCELLED.message());
                break;
            }
            round++;
            if (takeMail.getAsBoolean()) {
                history.add(new ChatMessage(ChatRole.USER, InterruptTypeEnum.MAIL_NOTIFICATION_TEXT, List.of(), null));
            }
            boolean last = finalRequest;
            compactIfNeeded(compactBeforeNext);
            compactBeforeNext = false;
            List<ChatMessage> wire = wireMessages();
            wireTail.clear();
            lastEstimate = contextManager == null ? 0 : contextManager.estimate(wire);
            boolean offerNativeTools = !last && mode == ToolCallingModeEnum.NATIVE;
            ClientResult result = sendAndWait(wire, offerNativeTools ? context.tools() : null, schemaResponseFormat(last));
            if (result == null) {
                break;
            }
            if (result.end() == ClientResult.End.COMPLETE) {
                onUsage(result.reply());
            }
            if (last) {
                onFinalResult(result);
            }
            else {
                onResult(result);
            }
        }
        if (outcome.reason() == null) {
            // Defensive: every path above claims; this keeps the "exactly one reason" contract even if one is missed.
            claim(TurnEndReason.SERVER_ERROR, TurnEndReason.SERVER_ERROR.message(EARLY_END_MESSAGE));
        }
        if (reasoning.length() > 0) {
            debug.event("Reasoning this turn: " + reasoning.length() + " characters (not sent back).");
        }
        debug.event("Turn ended: " + outcome.reason());
        return outcome;
    }

    /**
     * Handles one completed request (any request but the step-limit's last one).
     */
    private void onResult(ClientResult result) {
        AssembledReply reply = result.reply();
        switch (result.end()) {
            case CANCELLED ->
                claim(TurnEndReason.CANCELLED, TurnEndReason.CANCELLED.message());
            case TIMEOUT -> {
                keepPartialText(reply);
                claim(TurnEndReason.TIMEOUT, TurnEndReason.TIMEOUT.message(timeoutMessage(result.timeoutKind())));
            }
            case SIZE_LIMIT ->
                claim(TurnEndReason.SIZE_LIMIT,
                      TurnEndReason.SIZE_LIMIT.message(Math.max(1L, limits.transcriptCapBytes() >> 20)));
            case HTTP_ERROR, ERROR -> {
                if (canSwitchImmediately(result.error())) {
                    switchToSchemaNow();
                    return;
                }
                if (result.error() != null && result.error().kind() == MappedError.Kind.CONTEXT_OVERFLOW) {
                    onContextOverflow(result.error());
                    return;
                }
                if (isServerParseError(result.error())) {
                    if (mode == ToolCallingModeEnum.NATIVE) {
                        toolState.recordNativeFailure(context.model());
                    }
                    onMalformedFromServer(result.error());
                    return;
                }
                keepPartialText(reply);
                claim(TurnEndReason.SERVER_ERROR, TurnEndReason.SERVER_ERROR.message(errorText(result.error())));
            }
            default ->
                handleComplete(reply);
        }
    }

    /**
     * Before the first request: the fixed overhead (system prompt + tool definitions) must leave room in the window
     * (spec §4 item 7); otherwise the turn ends CONTEXT_OVERFLOW without sending.
     */
    private boolean overheadFits() {
        if (contextManager == null) {
            return true;
        }
        boolean nativeTools = mode == ToolCallingModeEnum.NATIVE && context.tools() != null;
        int overhead = contextManager.overheadTokens(effectiveSystemPrompt(),
                                                     nativeTools ? context.tools().toString() : "");
        // The tool definitions alone, for the hints (rank 3 "Tools use ~N of W", rank 8 tools share); in schema mode
        // they travel in the system prompt, so the same definitions are counted either way.
        int toolTokens = context.tools() == null || inputSchemas.isEmpty()
                         ? 0 : contextManager.overheadTokens("", context.tools().toString());
        String problem = contextManager.overheadProblem(overhead, toolTokens, !inputSchemas.isEmpty());
        if (problem == null) {
            return true;
        }
        claim(TurnEndReason.CONTEXT_OVERFLOW, TurnEndReason.CONTEXT_OVERFLOW.message(problem));
        return false;
    }

    /**
     * The compaction trigger before each request (spec §6.2); {@code force} after a usage report asked for it.
     *
     * @return true when the history changed
     */
    private boolean compactIfNeeded(boolean force) {
        if (contextManager == null) {
            return false;
        }
        enterPhase(Phase.COMPACTING);
        TurnContext.Compaction compaction;
        try {
            compaction = contextManager.compact(history, effectiveSystemPrompt(), force, handle);
        }
        finally {
            enterPhase(Phase.WAITING);
        }
        for (String notice : compaction.notices()) {
            listener.notice(notice);
        }
        if (compaction.changed()) {
            debug.event("Context compacted before the request.");
        }
        return compaction.changed();
    }

    private void onUsage(AssembledReply reply) {
        if (reply == null) {
            return;
        }
        lastPromptTokens = reply.promptTokens();
        lastCompletionTokens = reply.completionTokens();
        if (contextManager == null) {
            return;
        }
        TurnContext.UsageVerdict verdict = contextManager.onUsage(reply.promptTokens(), lastEstimate);
        if (verdict.compactNext()) {
            compactBeforeNext = true;
        }
        if (verdict.message() != null) {
            listener.notice(verdict.message());
        }
    }

    /**
     * The final server-reported usage for this turn, or {@code null} when either usage component was absent.
     */
    public Integer reportedUsage() {
        if (lastPromptTokens == null || lastCompletionTokens == null) {
            return null;
        }
        return Math.max(0, lastPromptTokens) + Math.max(0, lastCompletionTokens);
    }

    /**
     * The server says the prompt is over its window: learn a stated limit, compact and retry once per turn; again, or
     * when nothing could be compacted, CONTEXT_OVERFLOW (spec §4 item 6).
     */
    private void onContextOverflow(MappedError error) {
        if (contextManager == null) {
            claim(TurnEndReason.CONTEXT_OVERFLOW, TurnEndReason.CONTEXT_OVERFLOW.message(errorText(error)));
            return;
        }
        contextManager.learnWindow(error.statedContextLimit());
        if (contextManager.compactionOff()) {
            claim(TurnEndReason.CONTEXT_OVERFLOW,
                  TurnEndReason.CONTEXT_OVERFLOW.message(TurnContext.NONE_OVERFLOW_MESSAGE));
            return;
        }
        if (overflowRetriesUsed < maxOverflowRetries) {
            overflowRetriesUsed++;
            if (compactIfNeeded(true)) {
                debug.event("Context overflow; compacted and retrying once.");
                return;
            }
        }
        claim(TurnEndReason.CONTEXT_OVERFLOW, TurnEndReason.CONTEXT_OVERFLOW.message(errorText(error)));
    }

    /**
     * Tools rejected outright before anything happened in the turn: switch to schema mode at once and resend (spec §5);
     * not counted as a native failure, at most once per turn.
     */
    private boolean canSwitchImmediately(MappedError error) {
        return error != null && ToolCallModeResolver.immediateSwitch(error.kind())
                && mode == ToolCallingModeEnum.NATIVE && !immediateSwitchUsed && !inputSchemas.isEmpty()
                && toolRounds == 0 && malformedRounds == 0 && lengthContinuations == 0 && nudgesUsed == 0;
    }

    private void switchToSchemaNow() {
        immediateSwitchUsed = true;
        mode = ToolCallingModeEnum.SCHEMA;
        toolState.switchToSchema();
        debug.event("The server rejected native tools; resending in schema mode.");
        listener.notice(SWITCHED_IMMEDIATELY_MESSAGE);
    }

    private void handleComplete(AssembledReply reply) {
        String text = visibleText(reply);
        if (mode == ToolCallingModeEnum.SCHEMA) {
            handleSchemaReply(reply, text);
            return;
        }
        boolean cutOff = isCutOff(reply);
        if (!reply.toolCalls().isEmpty()) {
            if (cutOff) {
                failCallsBackForLength(reply.toolCalls(), text);
            }
            else {
                runTools(reply.toolCalls(), text, false, true);
            }
            return;
        }
        if (cutOff) {
            continueProse(text);
            return;
        }
        if (!isStopped(reply)) {
            keepPartialText(continuedProse + text);
            claim(TurnEndReason.SERVER_ERROR, TurnEndReason.SERVER_ERROR.message(EARLY_END_MESSAGE));
            return;
        }
        String joined = continuedProse + text;
        List<ToolJsonRescue.RescuedCall> rescued = tools == null || inputSchemas.isEmpty()
                                                   ? List.of() : ToolJsonRescue.rescue(joined, inputSchemas.keySet());
        if (!rescued.isEmpty()) {
            debug.event("Rescued " + rescued.size() + " tool call(s) written as text.");
            continuedProse.setLength(0);
            runTools(toCalls(rescued), joined, false, false);
            return;
        }
        finishProse(joined);
    }

    /**
     * Schema mode: the reply is one JSON object (message / tool_name / tool_arguments), or prose the rescue reads.
     */
    private void handleSchemaReply(AssembledReply reply, String text) {
        if (isCutOff(reply)) {
            continueProse(text);
            return;
        }
        String joined = continuedProse + text;
        if (!isStopped(reply)) {
            keepPartialText(joined);
            claim(TurnEndReason.SERVER_ERROR, TurnEndReason.SERVER_ERROR.message(EARLY_END_MESSAGE));
            return;
        }
        continuedProse.setLength(0);
        SchemaToolCalling.Reply parsed = SchemaToolCalling.parse(joined, reply.toolCalls(), inputSchemas.keySet());
        String message = parsed.spokeProtocol() ? nullToEmpty(parsed.message()) : joined;
        if (schemaStreamBuffered && !message.isBlank()) {
            emitText(message);
        }
        if (parsed.toolCallError() != null) {
            history.add(new ChatMessage(ChatRole.ASSISTANT, joined, List.of(), null));
            history.add(new ChatMessage(ChatRole.USER, parsed.toolCallError(), List.of(), null));
            malformedRounds++;
            debug.event("Schema reply could not be used; fed back (malformed round " + malformedRounds + ").");
            if (malformedRounds >= counts.maxMalformedRounds()) {
                claim(TurnEndReason.MALFORMED, TurnEndReason.MALFORMED.message(malformedRounds));
            }
            return;
        }
        if (!parsed.calls().isEmpty()) {
            runTools(toCalls(parsed.calls()), joined, true, false);
            return;
        }
        finishProse(message);
    }

    /**
     * A reply with no tool call: the answer, or one nudge when it is blank.
     */
    private void finishProse(String text) {
        if (text.isBlank()) {
            if (nudgesUsed < maxEmptyReplyNudges) {
                nudgesUsed++;
                debug.event("Empty reply; asking once for an answer.");
                wireTail.add(new ChatMessage(ChatRole.USER, EMPTY_REPLY_NUDGE, List.of(), null));
                return;
            }
            claim(TurnEndReason.ANSWERED, EMPTY_REPLY_MESSAGE);
            return;
        }
        continuedProse.setLength(0);
        history.add(new ChatMessage(ChatRole.ASSISTANT, text, List.of(), null));
        claim(TurnEndReason.ANSWERED, null);
    }

    private static boolean isCutOff(AssembledReply reply) {
        return "length".equalsIgnoreCase(reply.finishReason());
    }

    /**
     * A missing finish_reason counts as "stop" only when the stream ended cleanly ([DONE] or a final chunk).
     */
    private static boolean isStopped(AssembledReply reply) {
        return reply.finishReason() != null || reply.endedCleanly();
    }

    /**
     * A prose reply cut off by the length limit: keep what came, ask for the rest ("Continue."), and join the pieces —
     * at most {@code maxLengthContinuations} times, then LENGTH.
     */
    private void continueProse(String text) {
        continuedProse.append(text);
        if (lengthContinuations >= counts.maxLengthContinuations()) {
            keepPartialText(continuedProse.toString());
            claim(TurnEndReason.LENGTH, TurnEndReason.LENGTH.message(lengthContinuations));
            return;
        }
        lengthContinuations++;
        debug.event("Reply cut off by the length limit; continuation " + lengthContinuations + ".");
        wireTail.add(new ChatMessage(ChatRole.ASSISTANT, continuedProse.toString(), List.of(), null));
        wireTail.add(new ChatMessage(ChatRole.USER, CONTINUE_PROMPT, List.of(), null));
    }

    /**
     * Tool calls in a reply cut off by the length limit are not run (their arguments may be incomplete); each is failed
     * back asking for smaller pieces. Counts as a length continuation.
     */
    private void failCallsBackForLength(List<ChatToolCall> rawCalls, String text) {
        List<ChatToolCall> calls = ToolRunner.withIds(rawCalls, turnNo, round);
        history.add(new ChatMessage(ChatRole.ASSISTANT, text.isBlank() ? null : text, calls, null));
        for (ChatToolCall call : calls) {
            history.add(new ChatMessage(ChatRole.TOOL, LENGTH_TOOL_RESULT, List.of(), call.id()));
        }
        if (lengthContinuations >= counts.maxLengthContinuations()) {
            claim(TurnEndReason.LENGTH, TurnEndReason.LENGTH.message(lengthContinuations));
            return;
        }
        lengthContinuations++;
        debug.event("Tool calls cut off by the length limit; failed back (continuation " + lengthContinuations + ").");
    }

    /**
     * The server could not parse the model's tool call: feed the error back as a synthetic call and its result so the
     * model can repair it, up to {@code maxMalformedRounds} rounds, then MALFORMED.
     */
    private void onMalformedFromServer(MappedError error) {
        malformedRounds++;
        if (malformedRounds >= counts.maxMalformedRounds()) {
            claim(TurnEndReason.MALFORMED, TurnEndReason.MALFORMED.message(malformedRounds));
            return;
        }
        String id = "call_malformed_" + turnNo + "_" + round;
        history.add(new ChatMessage(ChatRole.ASSISTANT, null,
                                    List.of(new ChatToolCall(id, MALFORMED_CALL_NAME, "{}")), null));
        history.add(new ChatMessage(ChatRole.TOOL, serverParseErrorFeedback(errorText(error)), List.of(), id));
        debug.event("The server could not parse a tool call; fed back (malformed round " + malformedRounds + ").");
    }

    /**
     * The step-limit's last request (sent without tools): its prose, if any, is the answer, and the turn ends
     * STEP_LIMIT whatever happens — said so when the server rejected the request.
     */
    private void onFinalResult(ClientResult result) {
        String message = TurnEndReason.STEP_LIMIT.message(toolRounds);
        switch (result.end()) {
            case CANCELLED ->
                claim(TurnEndReason.CANCELLED, TurnEndReason.CANCELLED.message());
            case COMPLETE -> {
                String text = visibleText(result.reply());
                if (mode == ToolCallingModeEnum.SCHEMA) {
                    SchemaToolCalling.Reply parsed = SchemaToolCalling.parse(text, List.of(), Set.of());
                    text = parsed.spokeProtocol() ? nullToEmpty(parsed.message()) : text;
                    if (schemaStreamBuffered && !text.isBlank()) {
                        emitText(text);
                    }
                }
                keepPartialText(text);
                claim(TurnEndReason.STEP_LIMIT, message);
            }
            default -> {
                keepPartialText(result.reply());
                claim(TurnEndReason.STEP_LIMIT, message + STEP_LIMIT_REJECTED_NOTE);
            }
        }
    }

    /**
     * Runs the calls and appends the assistant message, then one result per call in the same order: native mode stores
     * the calls and TOOL messages, schema mode the reply text and a user message per result. Arguments are coerced to
     * the tool's schema first and results are capped as they are appended. A Cancel while a tool runs ends the turn at
     * once: the running call is recorded with the "outcome is unknown" note, calls after it are recorded as not run,
     * and completed calls keep their results. Afterwards the malformed, repetition and round-cap limits are applied.
     *
     * @param schema true for schema mode
     * @param structured true when the calls came from the reply's native tool_calls (not rescued from text); only those
     * count towards the native-failure count
     */
    private void runTools(List<ChatToolCall> rawCalls, String assistantText, boolean schema, boolean structured) {
        if (tools == null) {
            keepPartialText(assistantText);
            claim(TurnEndReason.SERVER_ERROR, TurnEndReason.SERVER_ERROR.message(NO_TOOLS_MESSAGE));
            return;
        }
        continuedProse.setLength(0);
        List<ChatToolCall> calls = ToolRunner.withIds(rawCalls, turnNo, round).stream().map(this::coerced).toList();
        if (schema) {
            history.add(new ChatMessage(ChatRole.ASSISTANT, assistantText, List.of(), null));
        }
        else {
            history.add(new ChatMessage(ChatRole.ASSISTANT, assistantText.isBlank() ? null : assistantText, calls,
                                        null));
        }
        ToolRunner.RunResult run;
        try {
            run = tools.run(calls, call -> enterToolPhase(call.name()));
        }
        finally {
            if (phase == Phase.TOOL) {
                enterPhase(Phase.WAITING);
            }
        }
        toolRounds++;
        boolean anyNewResult = false;
        String lastToolName = null;
        Set<String> invalidCalls = new HashSet<>();
        for (ToolRunner.ToolOutcome toolOutcome : run.outcomes()) {
            ChatToolCall call = toolOutcome.call();
            String result = toolOutcome.result();
            if (toolOutcome.invalidArguments()) {
                invalidCalls.add(call.name());
            }
            else if (toolOutcome.completed()) {
                lastToolName = call.name();
                JsonObject arguments = parseArgumentsLeniently(call.argumentsJson());
                boolean mutating = context.mutatingTools().contains(call.name()) && !isToolFailure(result);
                String resultKey = RepetitionTracker.callKey(call.name(), arguments) + "\n" + result;
                if (mutating) {
                    // A successful mutation changes the world; prior observations are stale and this round is progress.
                    anyNewResult = true;
                    repetition.clear();
                }
                else {
                    if (seenResults.add(resultKey)) {
                        anyNewResult = true;
                    }
                    if (repetition.record(call.name(), arguments, result)
                            == RepetitionTracker.Verdict.REPEATED) {
                        result = repetition.repeatedMessage(call.name());
                    }
                }
                if (toolResultCapChars > 0) {
                    result = ToolResultCap.cap(call.name(), result, toolResultCapChars);
                }
            }
            if (schema) {
                history.add(new ChatMessage(ChatRole.USER, SCHEMA_RESULT_PREFIX + call.name() + ":\n" + result,
                                            List.of(), null));
            }
            else {
                history.add(new ChatMessage(ChatRole.TOOL, result, List.of(), call.id()));
            }
            debug.event("Tool " + call.name() + (toolOutcome.completed() ? " finished" : " not finished"));
        }
        if (run.cancelled() || handle.isCancelled()) {
            String message = run.cancelledTool() != null
                             ? cancelledDuringToolMessage(run.cancelledTool())
                             : TurnEndReason.CANCELLED.message();
            claim(TurnEndReason.CANCELLED, message);
            return;
        }
        if (structured && mode == ToolCallingModeEnum.NATIVE) {
            if (invalidCalls.isEmpty()) {
                toolState.recordNativeWorking(context.model());
            }
            else {
                toolState.recordNativeFailure(context.model());
            }
        }
        applyRoundLimits(invalidCalls, anyNewResult, lastToolName);
    }

    /**
     * The call with its arguments coerced to the tool's input schema; unchanged when they do not parse (the runner then
     * reports them as invalid) or the tool has no schema.
     */
    private ChatToolCall coerced(ChatToolCall call) {
        JsonObject schema = inputSchemas.get(call.name());
        if (schema == null) {
            return call;
        }
        JsonObject arguments;
        try {
            String json = call.argumentsJson();
            JsonElement parsed = json == null || json.isBlank() ? new JsonObject() : JsonParser.parseString(json);
            if (parsed == null || !parsed.isJsonObject()) {
                return call;
            }
            arguments = parsed.getAsJsonObject();
        }
        catch (RuntimeException ex) {
            return call;
        }
        return new ChatToolCall(call.id(), call.name(), ArgumentCoercion.coerce(arguments, schema).toString());
    }

    private static List<ChatToolCall> toCalls(List<ToolJsonRescue.RescuedCall> rescued) {
        return rescued.stream().map(call -> new ChatToolCall(null, call.name(), call.argumentsJson())).toList();
    }

    /**
     * After a tool round: the same call failing validation in two consecutive rounds is a malformed round; a round that
     * only repeated earlier results is unproductive; and the round cap triggers the last request without tools.
     */
    private void applyRoundLimits(Set<String> invalidCalls, boolean anyNewResult, String lastToolName) {
        boolean repeatedInvalid = invalidCalls.stream().anyMatch(previousInvalidCalls::contains);
        previousInvalidCalls = invalidCalls;
        if (repeatedInvalid) {
            malformedRounds++;
            if (malformedRounds >= counts.maxMalformedRounds()) {
                claim(TurnEndReason.MALFORMED, TurnEndReason.MALFORMED.message(malformedRounds));
                return;
            }
        }
        if (lastToolName != null && repetition.endRound(anyNewResult)) {
            claim(TurnEndReason.REPETITION, TurnEndReason.REPETITION.message(lastToolName));
            return;
        }
        if (counts.maxToolRounds() > 0 && toolRounds >= counts.maxToolRounds()) {
            finalRequest = true;
            debug.event("Tool-round limit (" + counts.maxToolRounds() + ") reached; asking for a final answer.");
            wireTail.add(new ChatMessage(ChatRole.USER, STEP_LIMIT_PROMPT, List.of(), null));
        }
    }

    private static boolean isServerParseError(MappedError error) {
        return error != null && error.message().toLowerCase(Locale.ROOT).contains(SERVER_PARSE_ERROR_MARKER);
    }

    private static boolean isToolFailure(String result) {
        return result != null && (result.startsWith("Error:") || result.startsWith("Invalid arguments:"));
    }

    private static JsonObject parseArgumentsLeniently(String json) {
        if (json == null || json.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        }
        catch (RuntimeException ex) {
            return new JsonObject();
        }
    }

    /**
     * Tool name → input schema, from the offered tool definitions (MCP {@code inputSchema}, or {@code parameters}).
     */
    private static Map<String, JsonObject> inputSchemasOf(JsonArray toolSchemas) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        if (toolSchemas == null) {
            return out;
        }
        for (JsonElement element : toolSchemas) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject tool = element.getAsJsonObject();
            if (tool.has("function") && tool.get("function").isJsonObject()) {
                tool = tool.getAsJsonObject("function");
            }
            if (!tool.has("name") || !tool.get("name").isJsonPrimitive()) {
                continue;
            }
            JsonObject schema = new JsonObject();
            for (String key : List.of("inputSchema", "parameters", "input_schema")) {
                if (tool.has(key) && tool.get(key).isJsonObject()) {
                    schema = tool.getAsJsonObject(key);
                    break;
                }
            }
            out.put(tool.get("name").getAsString(), schema);
        }
        return out;
    }

    /**
     * The reply's text with {@code <think>} sections removed; they and the reply's reasoning fields go to
     * {@link #reasoning()} and are never stored in the history.
     */
    private String visibleText(AssembledReply reply) {
        if (reply == null) {
            return "";
        }
        if (!reply.reasoning().isEmpty()) {
            reasoning.append(reply.reasoning());
        }
        ThinkTagStripper stripper = new ThinkTagStripper();
        String visible = stripper.feed(reply.text()) + stripper.flush();
        reasoning.append(stripper.reasoning());
        return visible;
    }

    /**
     * Before the turn starts: if a tool a cancelled turn abandoned is still running, wait for it (bounded and
     * cancellable) so two tools of one session never run at once.
     *
     * @return true when the turn may start; false when the outcome has been claimed (refused or cancelled)
     */
    private boolean waitForPreviousTool() {
        if (tools == null || !tools.isBusy()) {
            return true;
        }
        String previous = tools.busyToolName();
        String name = previous == null ? "tool" : previous;
        phaseTool = name;
        enterPhase(Phase.WAITING_FOR_PREVIOUS_TOOL);
        debug.event("Waiting for the previous " + name + " to finish.");
        boolean idle;
        try {
            idle = tools.awaitIdle(limits.abandonedToolWait());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            claim(TurnEndReason.CANCELLED, TurnEndReason.CANCELLED.message());
            return false;
        }
        enterPhase(Phase.WAITING);
        if (outcome.reason() != null) {
            return false;
        }
        if (!idle) {
            claim(TurnEndReason.SERVER_ERROR,
                  TurnEndReason.SERVER_ERROR.message(previousToolStillRunningMessage(name)));
            return false;
        }
        return true;
    }

    private void enterToolPhase(String toolName) {
        phaseTool = toolName;
        enterPhase(Phase.TOOL);
    }

    /**
     * The tool this turn is waiting on right now, or null; the manager uses it for the Cancel message.
     */
    public String runningToolName() {
        return phase == Phase.TOOL ? phaseTool : null;
    }

    /**
     * Schema mode constrains the reply with {@code response_format} (the request builder drops it when the server has
     * refused it); never on the step-limit's last request, which must not call a tool.
     */
    private JsonObject schemaResponseFormat(boolean lastRequest) {
        if (mode != ToolCallingModeEnum.SCHEMA || lastRequest || inputSchemas.isEmpty()) {
            return null;
        }
        return SchemaToolCalling.responseFormat(inputSchemas.keySet());
    }

    /**
     * Sends one request and waits for it on this thread.
     *
     * @param requestTools the native tools to offer, or null for none (schema mode, the step-limit's last request)
     * @param responseFormat the schema-mode {@code response_format}, or null
     *
     * @return the result, or null when the wait ended without one (the reason is then already claimed)
     */
    private ClientResult sendAndWait(List<ChatMessage> wire, JsonArray requestTools, JsonObject responseFormat) {
        SamplingOptions sampling = context.samplingOptions();
        String reasoningEffort = flags.supportsReasoningEffort() ? sampling.reasoningEffort() : null;
        ChatRequestSpec spec = new ChatRequestSpec(context.apiBase(), context.model(), wire, requestTools,
                                                   sampling.temperature(), sampling.topP(), sampling.maxOutputTokens(),
                                                   reasoningEffort, responseFormat, keepAlive);
        enterPhase(Phase.WAITING);
        streamStripper = new ThinkTagStripper();
        reasoningFieldStreamed = false;
        streamedText.setLength(0);
        schemaStreamDecided = false;
        schemaStreamBuffered = false;
        CompletableFuture<ClientResult> future;
        try {
            future = client.send(spec, flags, context.apiKey(), context.headers(), limits, handle,
                                 this::onTextDelta, this::onReasoningDelta, new TurnDebugSink(wire.size()),
                                 context.keySituation());
        }
        catch (RuntimeException ex) {
            claim(TurnEndReason.SERVER_ERROR, TurnEndReason.SERVER_ERROR.message(describe(ex)));
            return null;
        }
        try {
            ClientResult result = future.get();
            int reasoningBefore = streamStripper.reasoning().length();
            String tail = streamStripper.flush();
            echoReasoning(streamStripper.reasoning().substring(reasoningBefore));
            showVisible(tail);
            if (!reasoningFieldStreamed && result != null && result.reply() != null) {
                // A client that does not stream reasoning fields delivers them with the finished reply.
                echoReasoning(result.reply().reasoning());
            }
            closeReasoningBlock();
            if (mode == ToolCallingModeEnum.SCHEMA && !schemaStreamDecided) {
                // Nothing but blanks arrived: whatever the parse finds is shown after it.
                schemaStreamBuffered = true;
            }
            return result;
        }
        catch (InterruptedException ex) {
            // Cancel interrupts the turn thread; keep the flag for anything further up and close the request.
            Thread.currentThread().interrupt();
            handle.cancel();
            claim(TurnEndReason.CANCELLED, TurnEndReason.CANCELLED.message());
            return null;
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            claim(TurnEndReason.SERVER_ERROR, TurnEndReason.SERVER_ERROR.message(describe(cause)));
            return null;
        }
    }

    private void onTextDelta(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int reasoningBefore = streamStripper.reasoning().length();
        String visible = streamStripper.feed(text);
        String newReasoning = streamStripper.reasoning().substring(reasoningBefore);
        if (!newReasoning.isEmpty()) {
            if (phase != Phase.THINKING) {
                enterPhase(Phase.THINKING);
            }
            echoReasoning(newReasoning);
        }
        if (!visible.isEmpty() || newReasoning.isEmpty()) {
            if (phase != Phase.GENERATING) {
                enterPhase(Phase.GENERATING);
            }
        }
        if (!visible.isEmpty()) {
            closeReasoningBlock();
            showVisible(visible);
        }
    }

    /**
     * A streamed reasoning-field delta ({@code reasoning_content} and the like): the status reads "Thinking N s" and,
     * when Show reasoning is on, the text joins the quote block live.
     */
    private void onReasoningDelta(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        reasoningFieldStreamed = true;
        if (phase != Phase.THINKING) {
            enterPhase(Phase.THINKING);
        }
        echoReasoning(text);
    }

    /**
     * Every chat text of this turn goes out here, so the reasoning block knows whether to start on a new paragraph.
     */
    private void emitText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        chatTextEmitted = true;
        listener.textDelta(text);
    }

    /**
     * When Show reasoning is on, writes {@code text} to the chat as a quote block ("&gt; " on every line), opening the
     * block on a new paragraph. Never stored in the history and never sent to the model.
     */
    private void echoReasoning(String text) {
        if (!showReasoning || text == null || text.isEmpty()) {
            return;
        }
        StringBuilder out = new StringBuilder();
        String body = text;
        if (!reasoningBlockOpen) {
            body = body.stripLeading();
            if (body.isEmpty()) {
                return;
            }
            if (chatTextEmitted) {
                out.append("\n\n");
            }
            reasoningBlockOpen = true;
            reasoningAtLineStart = true;
        }
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (reasoningAtLineStart) {
                out.append(REASONING_LINE_PREFIX);
                reasoningAtLineStart = false;
            }
            out.append(c);
            if (c == '\n') {
                reasoningAtLineStart = true;
            }
        }
        emitText(out.toString());
    }

    /**
     * Ends an open reasoning block with a blank line, so the answer that follows starts its own paragraph.
     */
    private void closeReasoningBlock() {
        if (!reasoningBlockOpen) {
            return;
        }
        reasoningBlockOpen = false;
        emitText(reasoningAtLineStart ? "\n" : "\n\n");
        reasoningAtLineStart = false;
    }

    /**
     * Forwards visible streamed text. In schema mode a reply that starts as a JSON object is held back (the parsed
     * message is shown when it ends); one that starts as prose streams as usual.
     */
    private void showVisible(String visible) {
        if (visible == null || visible.isEmpty()) {
            return;
        }
        if (mode != ToolCallingModeEnum.SCHEMA) {
            emitText(visible);
            return;
        }
        if (schemaStreamDecided) {
            if (!schemaStreamBuffered) {
                emitText(visible);
            }
            return;
        }
        streamedText.append(visible);
        String start = streamedText.toString().stripLeading();
        if (start.isEmpty()) {
            return;
        }
        schemaStreamDecided = true;
        schemaStreamBuffered = start.startsWith("{") || start.startsWith("`");
        if (!schemaStreamBuffered) {
            emitText(streamedText.toString());
        }
    }

    /**
     * The system prompt for the next request: in schema mode it also carries the reply protocol and the tool list.
     */
    private String effectiveSystemPrompt() {
        String base = systemPrompt == null ? "" : systemPrompt;
        if (mode != ToolCallingModeEnum.SCHEMA || inputSchemas.isEmpty() || context.tools() == null) {
            return base;
        }
        List<JsonObject> schemas = new ArrayList<>();
        for (JsonElement element : context.tools()) {
            if (element != null && element.isJsonObject()) {
                schemas.add(element.getAsJsonObject());
            }
        }
        String protocol = SCHEMA_PROTOCOL + SchemaToolCalling.renderToolList(schemas);
        return base.isBlank() ? protocol : base + "\n\n" + protocol;
    }

    /**
     * System prompt + history + the wire-only tail, with adjacent user messages joined so roles alternate (spec §4.2).
     */
    private List<ChatMessage> wireMessages() {
        List<ChatMessage> source = new ArrayList<>(history);
        source.addAll(wireTail);
        List<ChatMessage> wire = new ArrayList<>();
        String prompt = effectiveSystemPrompt();
        if (!prompt.isBlank()) {
            wire.add(new ChatMessage(ChatRole.SYSTEM, prompt, List.of(), null));
        }
        for (ChatMessage message : source) {
            int last = wire.size() - 1;
            if (last >= 0 && message.role() == ChatRole.USER && wire.get(last).role() == ChatRole.USER
                    && wire.get(last).toolCalls().isEmpty()) {
                ChatMessage previous = wire.get(last);
                wire.set(last, new ChatMessage(ChatRole.USER, join(previous.content(), message.content()),
                                               List.of(), null));
            }
            else {
                wire.add(message);
            }
        }
        return wire;
    }

    private static String join(String first, String second) {
        String a = first == null ? "" : first;
        String b = second == null ? "" : second;
        if (a.isBlank()) {
            return b;
        }
        if (b.isBlank()) {
            return a;
        }
        return a + "\n\n" + b;
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /**
     * Partial text the user already saw stays in the model's context too, so the next turn agrees with the chat.
     */
    private void keepPartialText(AssembledReply reply) {
        if (reply != null) {
            keepPartialText(continuedProse + visibleText(reply));
        }
    }

    private void keepPartialText(String text) {
        if (text != null && !text.isBlank()) {
            history.add(new ChatMessage(ChatRole.ASSISTANT, text, List.of(), null));
        }
    }

    private void claim(TurnEndReason reason, String message) {
        outcome.claim(reason, message);
    }

    private String timeoutMessage(ClientResult.TimeoutKind kind) {
        if (kind == ClientResult.TimeoutKind.TOTAL) {
            return "The reply took longer than the total limit (" + Math.max(1L, limits.totalCap().toMinutes())
                    + " min). Raise the Total request cap in Advanced, or ask for a shorter answer.";
        }
        Duration waited = kind == ClientResult.TimeoutKind.IDLE ? limits.idleTimeout() : limits.firstByteTimeout();
        return "No data from the server for " + waited.toSeconds() + " s. If the model is large or runs on CPU, raise "
                + "the First-byte timeout in session settings → Advanced; otherwise check the server is still running.";
    }

    private static String errorText(MappedError error) {
        if (error == null || error.message().isBlank()) {
            return EARLY_END_MESSAGE;
        }
        return error.message();
    }

    private static String describe(Throwable failure) {
        String detail = failure.getMessage();
        return detail == null || detail.isBlank() ? failure.getClass().getSimpleName() : detail;
    }

    private void enterPhase(Phase next) {
        phase = next;
        phaseStartNanos = System.nanoTime();
    }

    /**
     * The live status line: "Waiting N s", "Generating N s", "Thinking N s", "Retry 1/1", "&lt;Tool&gt; N s",
     * "Approval…" (a tool waiting for the user's Accept/Reject), "Compacting N s" or "Waiting for previous tool
     * (&lt;tool&gt;, N s)…", prefixed "R2 · " from the second round.
     */
    public String statusText() {
        Phase current = phase;
        long seconds = phaseSeconds();
        String text = switch (current) {
            case RETRYING ->
                retryText;
            case TOOL ->
                isAwaitingApproval() ? APPROVAL_STATUS : phaseTool + " " + seconds + " s";
            case WAITING_FOR_PREVIOUS_TOOL ->
                "Waiting for previous tool (" + phaseTool + ", " + seconds + " s)…";
            case GENERATING ->
                "Generating " + seconds + " s";
            case THINKING ->
                "Thinking " + seconds + " s";
            case COMPACTING ->
                "Compacting " + seconds + " s";
            default ->
                "Waiting " + seconds + " s";
        };
        int r = round;
        return r >= 2 ? "R" + r + " · " + text : text;
    }

    /**
     * The status line's tooltip: the same state as {@link #statusText()} as a full sentence (spec §7.4).
     */
    public String statusTooltip() {
        Phase current = phase;
        long seconds = phaseSeconds();
        String sentence = switch (current) {
            case RETRYING ->
                "The request failed for a transient reason and is being retried (" + retryText + ").";
            case TOOL ->
                isAwaitingApproval()
                ? phaseTool + " is waiting for you to accept or reject it."
                : "Running the tool " + phaseTool + " (" + seconds + " s so far).";
            case WAITING_FOR_PREVIOUS_TOOL ->
                "Waiting for " + phaseTool + ", which a cancelled turn left running, to finish (" + seconds
                + " s so far).";
            case GENERATING ->
                "The model is writing its reply (" + seconds + " s so far).";
            case THINKING ->
                "The model is reasoning before it answers (" + seconds + " s so far).";
            case COMPACTING ->
                "Shortening the model's context so it fits the window (" + seconds + " s so far).";
            default ->
                "Waiting for the server to start its reply (" + seconds + " s so far).";
        };
        int r = round;
        return r >= 2 ? "Round " + r + " of this turn. " + sentence : sentence;
    }

    private long phaseSeconds() {
        return Math.max(0L, (System.nanoTime() - phaseStartNanos) / 1_000_000_000L);
    }

    private boolean isAwaitingApproval() {
        try {
            return awaitingApproval.getAsBoolean();
        }
        catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Adds the status bookkeeping to the debug traffic, and logs the full request once per turn, then only the count of
     * messages each later request adds (spec §8.3).
     */
    private final class TurnDebugSink implements DebugSink {

        private final int messageCount;

        TurnDebugSink(int messageCount) {
            this.messageCount = messageCount;
        }

        @Override
        public void sent(String json) {
            if (!fullRequestLogged) {
                fullRequestLogged = true;
                debug.sent(json);
            }
            else {
                debug.sent("round " + round + ": " + messageCount + " messages (+"
                        + Math.max(0, messageCount - lastLoggedMessageCount) + " new)");
            }
            lastLoggedMessageCount = messageCount;
        }

        @Override
        public void received(String line) {
            if (phase == Phase.WAITING || phase == Phase.RETRYING) {
                enterPhase(Phase.GENERATING);
            }
            debug.received(line);
        }

        @Override
        public void event(String text) {
            if (text != null && text.startsWith("Retry ")) {
                int end = text.indexOf(' ', "Retry ".length());
                retryText = end > 0 ? text.substring(0, end) : text;
                enterPhase(Phase.RETRYING);
            }
            debug.event(text);
        }
    }
}

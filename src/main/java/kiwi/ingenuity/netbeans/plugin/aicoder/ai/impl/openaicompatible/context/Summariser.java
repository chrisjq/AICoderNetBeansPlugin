package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import com.google.gson.JsonArray;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ChatRequestSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ClientResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.CompatibilityFlags;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ErrorMapper;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.OpenAiCompatibleHttpClient.DebugSink;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.RequestHandle;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders.ExtraHeader;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.ChatClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.SamplingOptions;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.HiddenToolTextFilter;

/**
 * Summarises a span of earlier conversation so a compaction can replace the dropped turns with one kept summary (spec
 * §6.3). The transcript is chunked to fit the summary model's window, each chunk is folded into the running summary
 * with update mode, and no tools are sent.
 *
 * <p>
 * A reply that ends because of the length limit ({@code finish_reason == "length"}) or that is missing a required
 * heading is retried once with a repair nudge. If that reply also fails, the summariser reports failure so the caller
 * falls back to Drop. The returned summary is filtered for hidden tools one more time.
 *
 * <p>
 * The call is synchronous and must not run on the EDT; it blocks on the HTTP client's future and returns when the
 * summary (or a failure) is ready. Cancellation goes through the shared {@link RequestHandle}: a cancelled handle ends
 * the in-flight request and stops any further chunks.
 */
public final class Summariser {

    /**
     * Rough characters per token, matching {@code TokenEstimator}'s default, used to turn a token budget into the char
     * budget the transcript is chunked against.
     */
    private static final double CHARS_PER_TOKEN = 3.5d;

    /**
     * Never hand the serialiser a budget so small it cannot hold one transcript entry.
     */
    private static final int MIN_CHUNK_CHARS = 512;

    private final ChatClient client;
    private final ContextBudget budget;
    private final OpenAiCompatibleLimits limits;
    private final SummaryTemplate template = new SummaryTemplate();
    private final int repairNudges;
    private final int toolResultMaxChars;
    private final DebugSink debug;
    private String failureReason = "summary request failed";

    public Summariser(ChatClient client, ContextBudget budget, OpenAiCompatibleLimits limits) {
        this(client, budget, limits, 1);
    }

    public Summariser(ChatClient client, ContextBudget budget, OpenAiCompatibleLimits limits, int repairNudges) {
        this(client, budget, limits, repairNudges, TranscriptSerialiser.TOOL_RESULT_MAX_CHARS);
    }

    /**
     * @param toolResultMaxChars longest tool result kept in the transcript; zero or less means the default
     */
    public Summariser(ChatClient client, ContextBudget budget, OpenAiCompatibleLimits limits, int repairNudges,
                      int toolResultMaxChars) {
        this(client, budget, limits, repairNudges, toolResultMaxChars, DebugSink.NONE);
    }

    public Summariser(ChatClient client, ContextBudget budget, OpenAiCompatibleLimits limits, int repairNudges,
                      int toolResultMaxChars, DebugSink debug) {
        this.client = client;
        this.budget = budget == null ? new ContextBudget(ContextBudgetSettings.defaults()) : budget;
        this.limits = limits == null ? OpenAiCompatibleLimits.defaults() : limits;
        this.repairNudges = Math.max(0, repairNudges);
        this.toolResultMaxChars = toolResultMaxChars <= 0 ? TranscriptSerialiser.TOOL_RESULT_MAX_CHARS : toolResultMaxChars;
        this.debug = debug == null ? DebugSink.NONE : debug;
    }

    /**
     * A summariser whose repair-nudge count is the plugin's configured summary repair-nudge setting.
     */
    public static Summariser fromPluginSettings(ChatClient client, ContextBudget budget, OpenAiCompatibleLimits limits) {
        return fromPluginSettings(client, budget, limits, DebugSink.NONE);
    }

    public static Summariser fromPluginSettings(ChatClient client, ContextBudget budget, OpenAiCompatibleLimits limits,
                                                DebugSink debug) {
        return new Summariser(client, budget, limits, OpenAiCompatiblePluginSettings.getSummaryRepairNudges(),
                              OpenAiCompatiblePluginSettings.getSummaryToolResultChars(), debug);
    }

    /**
     * @param hiddenTools tools whose mentions are stripped from the transcript and the returned summary
     * @param status receives progress lines such as {@code "Compacting 3 s"}; may be null
     */
    public record SummaryRequest(String apiBase, String model, char[] apiKey, List<ExtraHeader> headers,
                                 CompatibilityFlags flags, ErrorMapper.KeySituation keySituation,
                                 SamplingOptions samplingOptions, int window, String pinned, String previousSummary,
                                 List<ChatMessage> messages, Set<McpToolEnum> hiddenTools, RequestHandle handle,
                                 StatusSink status) {

        public SummaryRequest(String apiBase, String model, char[] apiKey, List<ExtraHeader> headers,
                              CompatibilityFlags flags, ErrorMapper.KeySituation keySituation,
                              int window, String pinned, String previousSummary, List<ChatMessage> messages,
                              Set<McpToolEnum> hiddenTools, RequestHandle handle, StatusSink status) {
            this(apiBase, model, apiKey, headers, flags, keySituation, SamplingOptions.none(), window, pinned,
                 previousSummary, messages, hiddenTools, handle, status);
        }

        public SummaryRequest {
            samplingOptions = samplingOptions == null ? SamplingOptions.none() : samplingOptions;
        }
    }

    /**
     * Receives progress text while a summary is being written.
     */
    public interface StatusSink {

        void status(String text);
    }

    /**
     * @param success whether a valid summary was produced
     * @param summary the summary (filtered), or null on failure
     */
    public record Result(boolean success, String summary, String failureReason) {

        public Result(boolean success, String summary) {
            this(success, summary, null);
        }

        static Result failed(String reason) {
            return new Result(false, null, reason);
        }

        static Result ok(String summary) {
            return new Result(true, summary, null);
        }
    }

    public Result summarise(SummaryRequest request) {
        failureReason = "summary request failed";
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return Result.failed(failureReason);
        }
        if (request.handle() != null && request.handle().isCancelled()) {
            return Result.failed(failureReason);
        }
        long startedNanos = System.nanoTime();
        TranscriptSerialiser serialiser = new TranscriptSerialiser(request.hiddenTools(), toolResultMaxChars);
        int charBudget = charBudget(request);
        List<String> chunks = serialiser.chunks(request.messages(), charBudget);
        if (chunks.isEmpty()) {
            return Result.failed(failureReason);
        }

        String running = request.previousSummary();
        for (String chunk : chunks) {
            if (request.handle() != null && request.handle().isCancelled()) {
                return Result.failed(failureReason);
            }
            String summary = summariseChunk(request, chunk, running);
            if (summary == null) {
                return Result.failed(failureReason);
            }
            running = summary;
            reportStatus(request.status(), startedNanos);
        }
        String filtered = filter(request.hiddenTools(), running);
        if (filtered == null || !template.isValid(filtered)) {
            return Result.failed(failureReason);
        }
        return Result.ok(filtered);
    }

    /**
     * One chunk: prompt (fresh or update), send, and up to {@code repairNudges} repair attempts. Returns the valid
     * summary, or null when every attempt failed.
     */
    private String summariseChunk(SummaryRequest request, String chunk, String previousSummary) {
        List<ChatMessage> conversation = new java.util.ArrayList<>();
        String prompt = previousSummary == null || previousSummary.isBlank()
                        ? template.freshPrompt(chunk)
                        : template.updatePrompt(previousSummary, chunk);
        conversation.add(new ChatMessage(ChatRole.USER, prompt, List.of(), null));

        for (int attempt = 0; attempt <= repairNudges; attempt++) {
            ClientResult result = send(request, conversation);
            if (result == null || result.end() != ClientResult.End.COMPLETE) {
                failureReason = result == null ? "no response" : "request ended " + result.end();
                return null;
            }
            AssembledReply reply = result.reply();
            String text = reply.text() == null ? "" : reply.text().strip();
            boolean truncated = isLengthEnd(reply);
            if (!truncated && template.isValid(text)) {
                return text;
            }
            if (attempt == repairNudges) {
                return null;
            }
            conversation.add(new ChatMessage(ChatRole.ASSISTANT, text, List.of(), null));
            conversation.add(new ChatMessage(ChatRole.USER, template.repairNudge(), List.of(), null));
        }
        return null;
    }

    private ClientResult send(SummaryRequest request, List<ChatMessage> conversation) {
        SamplingOptions sampling = request.samplingOptions();
        String reasoningEffort = request.flags().supportsReasoningEffort() ? sampling.reasoningEffort() : null;
        ChatRequestSpec spec = new ChatRequestSpec(request.apiBase(), request.model(), List.copyOf(conversation),
                                                   new JsonArray(), sampling.temperature(), sampling.topP(),
                                                   sampling.maxOutputTokens(), reasoningEffort, null);
        try {
            return client.send(spec, request.flags(), request.apiKey(), request.headers(), limits, request.handle(),
                               null, debug, request.keySituation()).get();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (request.handle() != null) {
                request.handle().cancel();
            }
            return null;
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            failureReason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            return null;
        }
        catch (RuntimeException ex) {
            failureReason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return null;
        }
    }

    private int charBudget(SummaryRequest request) {
        int window = Math.max(0, request.window());
        int reserve = budget.reserve(window);
        int availableTokens = window - reserve;
        int templateTokens = estimateTokens(template.freshPrompt(""));
        int pinnedTokens = estimateTokens(request.pinned());
        availableTokens -= templateTokens + pinnedTokens;
        int chars = (int) (availableTokens * CHARS_PER_TOKEN);
        return Math.max(MIN_CHUNK_CHARS, chars);
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN) + 4;
    }

    private static boolean isLengthEnd(AssembledReply reply) {
        return reply.finishReason() != null && "length".equalsIgnoreCase(reply.finishReason());
    }

    private static String filter(Set<McpToolEnum> hiddenTools, String text) {
        if (text == null) {
            return null;
        }
        String filtered = HiddenToolTextFilter.omitSentencesReferencing(text, hiddenTools);
        return filtered == null ? null : filtered.strip();
    }

    private static void reportStatus(StatusSink sink, long startedNanos) {
        if (sink == null) {
            return;
        }
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos);
        sink.status("Compacting " + seconds + " s");
    }
}

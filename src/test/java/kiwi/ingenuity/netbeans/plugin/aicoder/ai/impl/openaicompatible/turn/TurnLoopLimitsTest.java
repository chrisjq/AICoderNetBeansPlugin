package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnEndReason;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ChatRequestSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ClientResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.CompatibilityFlags;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ErrorMapper;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.MappedError;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.OpenAiCompatibleHttpClient.DebugSink;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.RequestHandle;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders.ExtraHeader;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The P2a limits of the turn loop (plan 4 task 8): length continuations, malformed rounds, the tool-round cap and
 * repetition.
 */
class TurnLoopLimitsTest {

    private static final OpenAiCompatibleLimits LIMITS = OpenAiCompatibleLimits.defaults()
            .withCheckInterval(Duration.ofMillis(20))
            .withFirstByteTimeout(Duration.ofMillis(300))
            .withIdleTimeout(Duration.ofMillis(300))
            .withTotalCap(Duration.ofSeconds(2))
            .withRetryBackoff(Duration.ofMillis(50));

    private final ExecutorService toolExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger invocations = new AtomicInteger();

    @AfterEach
    void tearDown() {
        toolExecutor.shutdownNow();
    }

    private static JsonArray toolSchemas() {
        JsonObject function = new JsonObject();
        function.addProperty("name", "Read");
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        JsonArray tools = new JsonArray();
        tools.add(tool);
        return tools;
    }

    private static final TurnLoop.RequestContext CONTEXT = new TurnLoop.RequestContext(
            "http://localhost:1/v1", "model-x", toolSchemas(), null, List.of(), ErrorMapper.KeySituation.NONE);

    /**
     * Answers each request with the next scripted result (the last one repeats) and records what was sent.
     */
    private static final class ScriptedClient implements ChatClient {

        final List<Function<ChatRequestSpec, ClientResult>> script;
        final List<ChatRequestSpec> requests = new CopyOnWriteArrayList<>();

        @SafeVarargs
        ScriptedClient(Function<ChatRequestSpec, ClientResult>... steps) {
            script = new ArrayList<>(List.of(steps));
        }

        @Override
        public CompletableFuture<ClientResult> send(ChatRequestSpec request, CompatibilityFlags flags, char[] apiKey,
                                                    List<ExtraHeader> headers, OpenAiCompatibleLimits limits,
                                                    RequestHandle handle, Consumer<String> onTextDelta,
                                                    DebugSink debug, ErrorMapper.KeySituation keySituation) {
            requests.add(request);
            int index = requests.size() - 1;
            return CompletableFuture.completedFuture(script.get(Math.min(index, script.size() - 1)).apply(request));
        }
    }

    private static ClientResult prose(String text, String finishReason) {
        return new ClientResult(new AssembledReply(text, List.of(), finishReason, null, null, true, !text.isEmpty()),
                                ClientResult.End.COMPLETE, null, false);
    }

    private static ClientResult toolCall(String name, String argumentsJson, String finishReason) {
        return new ClientResult(new AssembledReply("", List.of(new ChatToolCall(null, name, argumentsJson)),
                                                   finishReason, null, null, true, true),
                                ClientResult.End.COMPLETE, null, false);
    }

    private static ClientResult httpError(String message) {
        return new ClientResult(null, ClientResult.End.HTTP_ERROR,
                                new MappedError(MappedError.Kind.SERVER_ERROR, message, null, null), false);
    }

    private record Run(TurnOutcome outcome, List<ChatMessage> history) {

    }

    private Run run(ChatClient client, TurnLoop.Counts counts, ToolRunner.Invoker invoker, Set<String> mutatingTools) {
        List<ChatMessage> history = new ArrayList<>();
        TurnOutcome outcome = new TurnOutcome();
        ToolRunner tools = new ToolRunner((name, args) -> {
            invocations.incrementAndGet();
            return invoker.invoke(name, args);
        }, () -> toolExecutor, null);
        TurnLoop.RequestContext context = new TurnLoop.RequestContext(CONTEXT.apiBase(), CONTEXT.model(), CONTEXT.tools(),
                                                                      CONTEXT.apiKey(), CONTEXT.headers(), CONTEXT.keySituation(),
                                                                      CONTEXT.samplingOptions(), mutatingTools);
        TurnLoop loop = new TurnLoop(history, "SYSTEM", client, new CompatibilityFlags(), LIMITS, new RequestHandle(),
                                     outcome, context, text -> {
                                     }, null, () -> false, tools, 1)
                .withCounts(counts);
        loop.run("go");
        return new Run(outcome, history);
    }

    private Run run(ChatClient client, TurnLoop.Counts counts, ToolRunner.Invoker invoker) {
        return run(client, counts, invoker, Set.of());
    }

    private Run run(ChatClient client, ToolRunner.Invoker invoker) {
        return run(client, TurnLoop.Counts.defaults(), invoker, Set.of());
    }

    private static TurnLoop.Counts withRoundCap(int cap) {
        TurnLoop.Counts d = TurnLoop.Counts.defaults();
        return new TurnLoop.Counts(cap, d.maxLengthContinuations(), d.maxMalformedRounds(), d.repetitionWindow(),
                                   d.unproductiveRounds());
    }

    private static List<ChatMessage> lastSent(ScriptedClient client) {
        return client.requests.get(client.requests.size() - 1).messages();
    }

    // ---- length ----
    @Test
    @Timeout(10)
    void proseCutOffByLengthIsContinuedAndJoined() {
        ScriptedClient client = new ScriptedClient(request -> prose("Part one, ", "length"),
                                                   request -> prose("part two.", "stop"));

        Run run = run(client, (name, args) -> "unused");

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals(2, client.requests.size());
        List<ChatMessage> second = lastSent(client);
        assertEquals(ChatRole.USER, second.get(second.size() - 1).role());
        assertEquals(TurnLoop.CONTINUE_PROMPT, second.get(second.size() - 1).content());
        assertEquals("Part one, ", second.get(second.size() - 2).content(), "the cut-off part is sent back");
        ChatMessage answer = run.history().get(run.history().size() - 1);
        assertEquals("Part one, part two.", answer.content(), "the pieces are joined into one answer");
        assertTrue(run.history().stream().noneMatch(m -> TurnLoop.CONTINUE_PROMPT.equals(m.content())),
                   "the continuation prompt is sent, never stored");
    }

    @Test
    @Timeout(10)
    void proseStillCutOffAfterTheContinuationLimitEndsWithLength() {
        ScriptedClient client = new ScriptedClient(request -> prose("more ", "length"));

        Run run = run(client, (name, args) -> "unused");

        assertEquals(TurnEndReason.LENGTH, run.outcome().reason());
        assertEquals(TurnEndReason.LENGTH.message(2), run.outcome().message());
        assertEquals(3, client.requests.size(), "the first reply plus two continuations");
        assertEquals("more more more ", run.history().get(run.history().size() - 1).content(),
                     "the partial answer is kept");
    }

    @Test
    @Timeout(10)
    void toolCallsCutOffByLengthAreFailedBackNotRun() {
        ScriptedClient client = new ScriptedClient(request -> toolCall("Read", "{\"path\":\"a", "length"),
                                                   request -> prose("Done.", "stop"));

        Run run = run(client, (name, args) -> "file text");

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals(0, invocations.get(), "a cut-off call is never run");
        assertTrue(run.history().stream().anyMatch(m -> m.role() == ChatRole.TOOL
                && TurnLoop.LENGTH_TOOL_RESULT.equals(m.content())), run.history().toString());
    }

    // ---- malformed ----
    @Test
    @Timeout(10)
    void aServerToolCallParseErrorIsFedBackThenEndsMalformed() {
        ScriptedClient client = new ScriptedClient(
                request -> httpError("Server said: error parsing tool call: unexpected token"));

        Run run = run(client, (name, args) -> "unused");

        assertEquals(TurnEndReason.MALFORMED, run.outcome().reason());
        assertEquals(TurnEndReason.MALFORMED.message(2), run.outcome().message());
        assertEquals(2, client.requests.size(), "fed back once, then the limit");
        List<ChatMessage> second = lastSent(client);
        ChatMessage feedback = second.get(second.size() - 1);
        assertEquals(ChatRole.TOOL, feedback.role());
        assertTrue(feedback.content().contains("could not be parsed"), feedback.content());
    }

    @Test
    @Timeout(10)
    void aServerParseErrorThenAValidAnswerIsAnswered() {
        ScriptedClient client = new ScriptedClient(request -> httpError("error parsing tool call"),
                                                   request -> prose("Fine.", "stop"));

        Run run = run(client, (name, args) -> "unused");

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
    }

    @Test
    @Timeout(10)
    void theSameCallFailingValidationInConsecutiveRoundsEndsMalformed() {
        ScriptedClient client = new ScriptedClient(request -> toolCall("Read", "not json", "tool_calls"));

        Run run = run(client, (name, args) -> "unused");

        assertEquals(TurnEndReason.MALFORMED, run.outcome().reason());
        assertEquals(0, invocations.get());
        assertEquals(3, client.requests.size(),
                     "round 1 fails, rounds 2 and 3 repeat the failure: two malformed rounds");
    }

    @Test
    @Timeout(10)
    void otherServerErrorsAreNotMalformed() {
        ScriptedClient client = new ScriptedClient(request -> httpError("model not loaded"));

        Run run = run(client, (name, args) -> "unused");

        assertEquals(TurnEndReason.SERVER_ERROR, run.outcome().reason());
        assertEquals(1, client.requests.size());
    }

    // ---- round cap ----
    @Test
    @Timeout(10)
    void theRoundCapSendsOneFinalRequestWithoutToolsThenStepLimit() {
        AtomicInteger n = new AtomicInteger();
        ScriptedClient client = new ScriptedClient(
                request -> toolCall("Read", "{\"path\":\"a\"}", "tool_calls"),
                request -> toolCall("Read", "{\"path\":\"b\"}", "tool_calls"),
                request -> prose("Summary so far.", "stop"));

        Run run = run(client, withRoundCap(2), (name, args) -> "result " + n.incrementAndGet());

        assertEquals(TurnEndReason.STEP_LIMIT, run.outcome().reason());
        assertEquals(TurnEndReason.STEP_LIMIT.message(2), run.outcome().message());
        assertEquals(3, client.requests.size());
        assertNotNull(client.requests.get(0).tools());
        assertNull(client.requests.get(2).tools(), "the final request offers no tools");
        assertTrue(lastSent(client).get(lastSent(client).size() - 1).content().contains("tool-round limit"));
        assertEquals("Summary so far.", run.history().get(run.history().size() - 1).content());
    }

    @Test
    @Timeout(10)
    void aRejectedFinalRequestStillEndsStepLimitAndSaysSo() {
        AtomicInteger n = new AtomicInteger();
        ScriptedClient client = new ScriptedClient(
                request -> toolCall("Read", "{\"path\":\"a\"}", "tool_calls"),
                request -> httpError("400 Bad Request"));

        Run run = run(client, withRoundCap(1), (name, args) -> "result " + n.incrementAndGet());

        assertEquals(TurnEndReason.STEP_LIMIT, run.outcome().reason());
        assertEquals(TurnEndReason.STEP_LIMIT.message(1) + TurnLoop.STEP_LIMIT_REJECTED_NOTE,
                     run.outcome().message());
    }

    @Test
    @Timeout(10)
    void noRoundCapMeansNoFinalRequest() {
        AtomicInteger n = new AtomicInteger();
        ScriptedClient client = new ScriptedClient(
                request -> toolCall("Read", "{\"path\":\"a\"}", "tool_calls"),
                request -> toolCall("Read", "{\"path\":\"b\"}", "tool_calls"),
                request -> toolCall("Read", "{\"path\":\"c\"}", "tool_calls"),
                request -> prose("Done.", "stop"));

        Run run = run(client, withRoundCap(0), (name, args) -> "result " + n.incrementAndGet());

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertTrue(client.requests.stream().allMatch(r -> r.tools() != null));
    }

    // ---- repetition ----
    @Test
    @Timeout(10)
    void aRepeatedCallGetsTheRepeatedNoteAndUnproductiveRoundsEndWithRepetition() {
        ScriptedClient client = new ScriptedClient(request -> toolCall("Read", "{\"path\":\"a\"}", "tool_calls"));

        Run run = run(client, (name, args) -> "same text");

        assertEquals(TurnEndReason.REPETITION, run.outcome().reason());
        assertEquals(TurnEndReason.REPETITION.message("Read"), run.outcome().message());
        assertEquals(4, client.requests.size(), "one productive round, then three unproductive ones");
        assertTrue(run.history().stream().anyMatch(m -> m.role() == ChatRole.TOOL
                && m.content().startsWith("You already called Read")), run.history().toString());
    }

    @Test
    @Timeout(10)
    void newResultsKeepTheTurnGoing() {
        AtomicInteger n = new AtomicInteger();
        ScriptedClient client = new ScriptedClient(
                request -> toolCall("Read", "{\"path\":\"a\"}", "tool_calls"),
                request -> toolCall("Read", "{\"path\":\"b\"}", "tool_calls"),
                request -> toolCall("Read", "{\"path\":\"c\"}", "tool_calls"),
                request -> toolCall("Read", "{\"path\":\"d\"}", "tool_calls"),
                request -> prose("Done.", "stop"));

        Run run = run(client, (name, args) -> "result " + n.incrementAndGet());

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals(5, client.requests.size());
    }

    @Test
    @Timeout(10)
    void editThenVerifyWithSameDiagnosticResultDoesNotStop() {
        ScriptedClient client = new ScriptedClient(
                request -> toolCall("GetDiagnostics", "{\"path\":\"x\"}", "tool_calls"),
                request -> toolCall("ApplyEdit", "{\"path\":\"x\"}", "tool_calls"),
                request -> toolCall("GetDiagnostics", "{\"path\":\"x\"}", "tool_calls"),
                request -> prose("Done.", "stop"));
        Run run = run(client, TurnLoop.Counts.defaults(), (name, args) -> "ApplyEdit".equals(name)
                                                                          ? "File updated and saved (1 occurrence replaced)" : "W",
                      Set.of("ApplyEdit"));
        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals(4, client.requests.size());
    }

    @Test
    @Timeout(10)
    void readOnlyDiagnosticsStillStopsAfterUnproductiveRounds() {
        ScriptedClient client = new ScriptedClient(
                request -> toolCall("GetDiagnostics", "{\"path\":\"x\"}", "tool_calls"));
        Run run = run(client, (name, args) -> "W");
        assertEquals(TurnEndReason.REPETITION, run.outcome().reason());
    }

    @Test
    @Timeout(10)
    void readOnlyPingPongStillStops() {
        ScriptedClient client = new ScriptedClient(
                request -> toolCall("Read", "{\"path\":\"a\"}", "tool_calls"),
                request -> toolCall("Read", "{\"path\":\"b\"}", "tool_calls"),
                request -> toolCall("Read", "{\"path\":\"a\"}", "tool_calls"),
                request -> toolCall("Read", "{\"path\":\"b\"}", "tool_calls"));
        Run run = run(client, (name, args) -> "same");
        assertEquals(TurnEndReason.REPETITION, run.outcome().reason());
    }

    @Test
    @Timeout(10)
    void failedMutationDoesNotResetRepetitionMemory() {
        ScriptedClient client = new ScriptedClient(
                request -> toolCall("Read", "{\"path\":\"x\"}", "tool_calls"),
                request -> toolCall("ApplyEdit", "{\"path\":\"x\"}", "tool_calls"),
                request -> toolCall("Read", "{\"path\":\"x\"}", "tool_calls"),
                request -> toolCall("Read", "{\"path\":\"x\"}", "tool_calls"));
        Run run = run(client, TurnLoop.Counts.defaults(),
                      (name, args) -> "ApplyEdit".equals(name) ? "Error: failed" : "W",
                      Set.of("ApplyEdit"));
        assertEquals(TurnEndReason.REPETITION, run.outcome().reason());
    }

    @Test
    @Timeout(10)
    void sameReadResultWithDifferentArgumentsIsNewAtTurnLevel() {
        ScriptedClient client = new ScriptedClient(
                request -> toolCall("Read", "{\"path\":\"a\"}", "tool_calls"),
                request -> toolCall("Read", "{\"path\":\"b\"}", "tool_calls"),
                request -> prose("Done.", "stop"));
        Run run = run(client, (name, args) -> "same");
        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
    }
}

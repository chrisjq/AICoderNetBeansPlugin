package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnEndReason;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.TokenEstimator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ChatRequestSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ClientResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.CompatibilityFlags;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ErrorMapper;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.MappedError;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.OpenAiCompatibleHttpClient.DebugSink;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.RequestHandle;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.CompactionStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.SessionDecisions;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders.ExtraHeader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Context management in the turn loop (plan 6 task 4): the overflow compact-and-retry, the NONE message, the fixed
 * overhead checks and compaction before a request.
 */
class TurnLoopContextTest {

    private static final OpenAiCompatibleLimits LIMITS = OpenAiCompatibleLimits.defaults()
            .withCheckInterval(Duration.ofMillis(20))
            .withFirstByteTimeout(Duration.ofMillis(300))
            .withIdleTimeout(Duration.ofMillis(300))
            .withTotalCap(Duration.ofSeconds(2))
            .withRetryBackoff(Duration.ofMillis(50));

    private static JsonArray readTool() {
        return JsonParser.parseString("[{\"name\":\"Read\",\"description\":\"Reads a file.\",\"inputSchema\":"
                + "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}}]").getAsJsonArray();
    }

    private final SessionDecisions decisions = new SessionDecisions("g");
    private final TurnContext turnContext = new TurnContext(new TokenEstimator(), decisions, null);
    private final List<String> notices = new CopyOnWriteArrayList<>();
    private TurnContext.SummaryRunner summaryRunner;

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

    private static ClientResult prose(String text) {
        return new ClientResult(new AssembledReply(text, List.of(), "stop", null, null, true, true),
                                ClientResult.End.COMPLETE, null, false);
    }

    private static ClientResult overflow(Integer statedLimit) {
        return new ClientResult(null, ClientResult.End.HTTP_ERROR,
                                new MappedError(MappedError.Kind.CONTEXT_OVERFLOW,
                                                "The prompt is larger than the server's context window.", null,
                                                statedLimit), false);
    }

    /**
     * {@code turns} earlier exchanges with a 3 000-character answer each.
     */
    private static List<ChatMessage> earlierTurns(int turns) {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 1; i <= turns; i++) {
            history.add(new ChatMessage(ChatRole.USER, "question " + i, List.of(), null));
            history.add(new ChatMessage(ChatRole.ASSISTANT, "a".repeat(3000), List.of(), null));
        }
        return history;
    }

    private static TurnContext.Config config(CompactionStrategyEnum strategy, int override) {
        return new TurnContext.Config(strategy, true, true, override, 8192, 6144, "GENERIC", "model-x", "http://h");
    }

    private record Run(TurnOutcome outcome, List<ChatMessage> history) {

    }

    private Run run(ChatClient client, List<ChatMessage> history, String systemPrompt, JsonArray tools,
                    TurnContext.Config config) {
        return run(client, history, systemPrompt, tools, config, loop -> loop);
    }

    private Run run(ChatClient client, List<ChatMessage> history, String systemPrompt, JsonArray tools,
                    TurnContext.Config config, java.util.function.UnaryOperator<TurnLoop> tune) {
        turnContext.beginTurn(config, summaryRunner);
        TurnOutcome outcome = new TurnOutcome();
        TurnLoop.RequestContext context = new TurnLoop.RequestContext("http://h/v1", "model-x", tools, null,
                                                                      List.of(), ErrorMapper.KeySituation.NONE);
        TurnLoop.Listener listener = new TurnLoop.Listener() {
            @Override
            public void textDelta(String text) {
            }

            @Override
            public void notice(String text) {
                notices.add(text);
            }
        };
        TurnLoop loop = new TurnLoop(history, systemPrompt, client, new CompatibilityFlags(), LIMITS,
                                     new RequestHandle(), outcome, context, listener, null, () -> false, null, 1)
                .withContext(turnContext);
        tune.apply(loop).run("now");
        return new Run(outcome, history);
    }

    @Test
    @Timeout(10)
    void aConfiguredZeroOverflowRetriesEndsAtTheFirstOverflow() {
        ScriptedClient client = new ScriptedClient(request -> overflow(4096), request -> prose("Fits now."));

        Run run = run(client, earlierTurns(6), "SYSTEM", null, config(CompactionStrategyEnum.AUTO, 0),
                      loop -> loop.withOverflowRetries(0));

        assertEquals(TurnEndReason.CONTEXT_OVERFLOW, run.outcome().reason());
        assertEquals(1, client.requests.size(), "no compact-and-retry when the count is zero");
    }

    @Test
    @Timeout(10)
    void anOverflowLearnsTheStatedLimitCompactsAndRetriesOnce() {
        ScriptedClient client = new ScriptedClient(request -> overflow(4096), request -> prose("Fits now."));
        List<ChatMessage> history = earlierTurns(6);

        Run run = run(client, history, "SYSTEM", null, config(CompactionStrategyEnum.AUTO, 0));

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals(2, client.requests.size());
        assertEquals(4096, decisions.learnedWindow(), "the stated limit is a session decision");
        assertTrue(client.requests.get(1).messages().size() < client.requests.get(0).messages().size(),
                   "the retry sends the compacted history");
        assertTrue(notices.stream().anyMatch(n -> n.startsWith("Context trimmed (")), notices.toString());
    }

    @Test
    @Timeout(10)
    void aSecondOverflowInTheTurnEndsWithContextOverflow() {
        ScriptedClient client = new ScriptedClient(request -> overflow(4096));

        Run run = run(client, earlierTurns(6), "SYSTEM", null, config(CompactionStrategyEnum.AUTO, 0));

        assertEquals(TurnEndReason.CONTEXT_OVERFLOW, run.outcome().reason());
        assertEquals("The prompt is larger than the server's context window.", run.outcome().message());
        assertEquals(2, client.requests.size(), "one compact-and-retry per turn");
    }

    @Test
    @Timeout(10)
    void anOverflowWithNothingToCompactEndsAtOnce() {
        ScriptedClient client = new ScriptedClient(request -> overflow(null));

        Run run = run(client, new ArrayList<>(), "SYSTEM", null, config(CompactionStrategyEnum.AUTO, 0));

        assertEquals(TurnEndReason.CONTEXT_OVERFLOW, run.outcome().reason());
        assertEquals(1, client.requests.size());
    }

    @Test
    @Timeout(10)
    void anOverflowUnderNoneSaysToEnableCompaction() {
        ScriptedClient client = new ScriptedClient(request -> overflow(4096));

        Run run = run(client, earlierTurns(6), "SYSTEM", null, config(CompactionStrategyEnum.NONE, 0));

        assertEquals(TurnEndReason.CONTEXT_OVERFLOW, run.outcome().reason());
        assertEquals(TurnContext.NONE_OVERFLOW_MESSAGE, run.outcome().message());
        assertEquals(1, client.requests.size());
    }

    @Test
    @Timeout(10)
    void toolsInAWindowBelowTheMinimumEndTheTurnBeforeSending() {
        ScriptedClient client = new ScriptedClient(request -> prose("unused"));

        Run run = run(client, new ArrayList<>(), "s".repeat(12_000), readTool(),
                      config(CompactionStrategyEnum.AUTO, 4096));

        assertEquals(TurnEndReason.CONTEXT_OVERFLOW, run.outcome().reason());
        assertTrue(run.outcome().message().startsWith("Context window 4096 is too small for tools"),
                   run.outcome().message());
        assertEquals(0, client.requests.size(), "nothing is sent");
    }

    @Test
    @Timeout(10)
    void anOverheadTooBigForTheWindowEndsTheTurnBeforeSending() {
        ScriptedClient client = new ScriptedClient(request -> prose("unused"));

        Run run = run(client, new ArrayList<>(), "s".repeat(60_000), null,
                      config(CompactionStrategyEnum.AUTO, 16384));

        assertEquals(TurnEndReason.CONTEXT_OVERFLOW, run.outcome().reason());
        assertTrue(run.outcome().message().startsWith("Tool definitions alone need ~"), run.outcome().message());
        assertEquals(0, client.requests.size());
    }

    @Test
    @Timeout(10)
    void aHistoryOverAConfirmedWindowIsCompactedBeforeTheRequest() {
        ScriptedClient client = new ScriptedClient(request -> prose("Done."));

        Run run = run(client, earlierTurns(6), "SYSTEM", null, config(CompactionStrategyEnum.AUTO, 4096));

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals(1, client.requests.size());
        List<ChatMessage> sent = client.requests.get(0).messages();
        assertTrue(sent.get(1).content().contains("earlier exchanges were trimmed"), sent.get(1).content());
        assertTrue(notices.stream().anyMatch(n -> n.startsWith("Context trimmed (")), notices.toString());
    }

    @Test
    @Timeout(10)
    void theSummaryGoesInAsOneUserMessageMergedSoRolesAlternate() {
        summaryRunner = (messages, previous, window, pinned, handle)
                -> new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.Summariser.Result(
                        true, "S1");
        ScriptedClient client = new ScriptedClient(request -> prose("Done."));

        Run run = run(client, earlierTurns(24), "SYSTEM", null, config(CompactionStrategyEnum.AUTO, 16384));

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        List<ChatMessage> sent = client.requests.get(0).messages();
        assertEquals(ChatRole.SYSTEM, sent.get(0).role());
        assertEquals(ChatRole.USER, sent.get(1).role());
        assertTrue(sent.get(1).content().startsWith(TurnContext.SUMMARY_PREFIX + "S1\n\nquestion "),
                   "summary first, a blank line, then the user text: " + sent.get(1).content());
        for (int i = 1; i < sent.size(); i++) {
            assertTrue(!(sent.get(i).role() == ChatRole.USER && sent.get(i - 1).role() == ChatRole.USER),
                       "roles alternate at " + i);
        }
        assertTrue(notices.stream().anyMatch(n -> n.startsWith("Context compacted (")
                && n.endsWith(" messages summarised)")), notices.toString());
    }

    @Test
    @Timeout(10)
    void theStatusSaysCompactingWhileASummaryIsWritten() {
        List<String> seen = new CopyOnWriteArrayList<>();
        TurnLoop[] loop = new TurnLoop[1];
        summaryRunner = (messages, previous, window, pinned, handle) -> {
            seen.add(loop[0].statusText());
            return new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.Summariser.Result(
                    true, "S1");
        };
        turnContext.beginTurn(config(CompactionStrategyEnum.AUTO, 16384), summaryRunner);
        loop[0] = new TurnLoop(earlierTurns(24), "SYSTEM", new ScriptedClient(request -> prose("Done.")),
                               new CompatibilityFlags(), LIMITS, new RequestHandle(), new TurnOutcome(),
                               new TurnLoop.RequestContext("http://h/v1", "model-x", null, null, List.of(),
                                                           ErrorMapper.KeySituation.NONE),
                               text -> {
                               }, null, () -> false, null, 1)
                .withContext(turnContext);

        loop[0].run("now");

        assertEquals(1, seen.size());
        assertTrue(seen.get(0).startsWith("Compacting "), seen.toString());
    }

    @Test
    @Timeout(10)
    void anUnconfirmedWindowIsNotCompactedAutomatically() {
        ScriptedClient client = new ScriptedClient(request -> prose("Done."));
        List<ChatMessage> history = earlierTurns(6);

        run(client, history, "SYSTEM", null, config(CompactionStrategyEnum.AUTO, 0));

        assertEquals(1 + 12 + 1, client.requests.get(0).messages().size(), "system + the history + the prompt");
        assertTrue(notices.isEmpty());
    }
}

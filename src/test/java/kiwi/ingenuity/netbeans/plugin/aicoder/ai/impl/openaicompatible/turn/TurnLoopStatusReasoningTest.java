package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnEndReason;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ClientResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.CompatibilityFlags;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ErrorMapper;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.RequestHandle;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The status line ("Approval…", "Thinking N s", the full-sentence tooltip) and the optional reasoning display.
 */
class TurnLoopStatusReasoningTest {

    private static final OpenAiCompatibleLimits LIMITS = OpenAiCompatibleLimits.defaults()
            .withCheckInterval(Duration.ofMillis(20))
            .withFirstByteTimeout(Duration.ofMillis(300))
            .withIdleTimeout(Duration.ofMillis(300))
            .withTotalCap(Duration.ofSeconds(2))
            .withRetryBackoff(Duration.ofMillis(50));

    private final ExecutorService toolExecutor = Executors.newSingleThreadExecutor();
    private final List<String> deltas = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        toolExecutor.shutdownNow();
    }

    private static JsonArray readTool() {
        return JsonParser.parseString("[{\"name\":\"Read\",\"description\":\"Reads.\",\"inputSchema\":"
                + "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}}]").getAsJsonArray();
    }

    private static TurnLoop.RequestContext context(JsonArray tools) {
        return new TurnLoop.RequestContext("http://h/v1", "model-x", tools, null, List.of(),
                                           ErrorMapper.KeySituation.NONE);
    }

    private static ClientResult complete(String text, String reasoning, List<ChatToolCall> calls) {
        return new ClientResult(new AssembledReply(text, calls, calls.isEmpty() ? "stop" : "tool_calls", null, null,
                                                   true, true, reasoning),
                                ClientResult.End.COMPLETE, null, false);
    }

    /**
     * Streams each scripted reply's {@code chunks} as deltas, then completes with {@code text} (all chunks joined).
     */
    private static ChatClient streaming(List<List<String>> replies, List<String> fieldReasoning) {
        AtomicInteger index = new AtomicInteger();
        return (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation) -> {
            int i = Math.min(index.getAndIncrement(), replies.size() - 1);
            StringBuilder text = new StringBuilder();
            for (String chunk : replies.get(i)) {
                onTextDelta.accept(chunk);
                text.append(chunk);
            }
            return CompletableFuture.completedFuture(complete(text.toString(), fieldReasoning.get(i), List.of()));
        };
    }

    private TurnLoop loop(ChatClient client, List<ChatMessage> history, TurnOutcome outcome, ToolRunner tools,
                          JsonArray toolSchemas) {
        return new TurnLoop(history, "SYSTEM", client, new CompatibilityFlags(), LIMITS, new RequestHandle(),
                            outcome, context(toolSchemas), deltas::add, null, () -> false, tools, 1);
    }

    // ---- status ----
    @Test
    @Timeout(10)
    void aToolWaitingForTheUserShowsApprovalAndOtherwiseItsNameAndSeconds() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean pendingDiff = new AtomicBoolean();
        ToolRunner tools = new ToolRunner((name, args) -> {
            toolStarted.countDown();
            try {
                release.await();
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "done";
        }, () -> toolExecutor, null);
        AtomicInteger sent = new AtomicInteger();
        ChatClient client = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                -> CompletableFuture.completedFuture(sent.getAndIncrement() == 0
                                                     ? complete("", "", List.of(new ChatToolCall("c1", "Read", "{\"path\":\"a\"}")))
                                                     : complete("Done.", "", List.of()));
        TurnOutcome outcome = new TurnOutcome();
        TurnLoop loop = loop(client, new ArrayList<>(), outcome, tools, readTool()).withApprovalWait(pendingDiff::get);
        Thread turn = new Thread(() -> loop.run("go"));
        turn.start();
        assertTrue(toolStarted.await(5, TimeUnit.SECONDS));

        assertTrue(loop.statusText().startsWith("Read ") && loop.statusText().endsWith(" s"), loop.statusText());
        assertTrue(loop.statusTooltip().startsWith("Running the tool Read ("), loop.statusTooltip());

        pendingDiff.set(true);

        assertEquals(TurnLoop.APPROVAL_STATUS, loop.statusText());
        assertEquals("Read is waiting for you to accept or reject it.", loop.statusTooltip());
        assertFalse(loop.statusTooltip().equals(loop.statusText()), "the tooltip is a sentence, not the label");

        pendingDiff.set(false);
        release.countDown();
        turn.join(5000);
        assertEquals(TurnEndReason.ANSWERED, outcome.reason());
    }

    @Test
    @Timeout(10)
    void theTooltipIsAFullSentenceAndNamesTheRoundFromRoundTwo() {
        List<String> tips = new CopyOnWriteArrayList<>();
        TurnLoop[] holder = new TurnLoop[1];
        AtomicInteger sent = new AtomicInteger();
        ChatClient client = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation) -> {
            tips.add(holder[0].statusTooltip());
            return CompletableFuture.completedFuture(sent.getAndIncrement() == 0 ? complete("", "", List.of())
                                                     : complete("Answer.", "", List.of()));
        };
        holder[0] = loop(client, new ArrayList<>(), new TurnOutcome(), null, null);

        holder[0].run("hi");

        assertTrue(tips.get(0).startsWith("Waiting for the server to start its reply ("), tips.toString());
        assertTrue(tips.get(0).endsWith(" s so far)."), tips.toString());
        assertTrue(tips.get(1).startsWith("Round 2 of this turn. Waiting for the server"), tips.toString());
    }

    @Test
    @Timeout(10)
    void thinkTextStreamingShowsThinkingInTheStatusLine() {
        List<String> seen = new CopyOnWriteArrayList<>();
        TurnLoop[] holder = new TurnLoop[1];
        ChatClient client = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation) -> {
            onTextDelta.accept("<think>weighing it");
            seen.add(holder[0].statusText());
            onTextDelta.accept("</think>Answer.");
            seen.add(holder[0].statusText());
            return CompletableFuture.completedFuture(complete("<think>weighing it</think>Answer.", "", List.of()));
        };
        holder[0] = loop(client, new ArrayList<>(), new TurnOutcome(), null, null);

        holder[0].run("hi");

        assertTrue(seen.get(0).startsWith("Thinking "), seen.toString());
        assertTrue(seen.get(1).startsWith("Generating "), seen.toString());
    }

    // ---- reasoning display ----
    @Test
    @Timeout(10)
    void withShowReasoningOnThinkTextStreamsAsAQuoteBlockBeforeTheAnswer() {
        ChatClient client = streaming(List.of(List.of("<think>plan A\n", "plan B</think>", "Answer.")), List.of(""));
        List<ChatMessage> history = new ArrayList<>();
        TurnOutcome outcome = new TurnOutcome();

        loop(client, history, outcome, null, null).withShowReasoning(true).run("hi");

        assertEquals(TurnEndReason.ANSWERED, outcome.reason());
        assertEquals("> plan A\n> plan B\n\nAnswer.", String.join("", deltas));
        assertEquals("Answer.", history.get(history.size() - 1).content(), "the model's history keeps the answer only");
        assertTrue(history.stream().noneMatch(m -> m.content() != null && m.content().contains("plan")),
                   "reasoning is never sent back to the model");
    }

    @Test
    @Timeout(10)
    void withShowReasoningOffTheChatGetsTheAnswerOnly() {
        ChatClient client = streaming(List.of(List.of("<think>plan A</think>", "Answer.")), List.of("field why"));

        loop(client, new ArrayList<>(), new TurnOutcome(), null, null).run("hi");

        assertEquals("Answer.", String.join("", deltas));
    }

    @Test
    @Timeout(10)
    void reasoningFieldsAreShownWhenTheReplyEnds() {
        ChatClient client = streaming(List.of(List.of("Answer.")), List.of("because\nreasons"));
        List<ChatMessage> history = new ArrayList<>();

        loop(client, history, new TurnOutcome(), null, null).withShowReasoning(true).run("hi");

        String chat = String.join("", deltas);
        assertTrue(chat.startsWith("Answer.\n\n> because\n> reasons"), chat);
        assertEquals("Answer.", history.get(history.size() - 1).content());
    }

    /**
     * A client that streams reasoning fields through the ten-argument send, then the content.
     */
    private static ChatClient streamingReasoningFields(List<String> reasoningChunks, String answer,
                                                       List<String> statusSeen, TurnLoop[] holder) {
        return new ChatClient() {
            @Override
            public CompletableFuture<ClientResult> send(
                    kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ChatRequestSpec request,
                    CompatibilityFlags flags, char[] apiKey,
                    List<kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders.ExtraHeader> headers,
                    OpenAiCompatibleLimits limits, RequestHandle handle, java.util.function.Consumer<String> onTextDelta,
                    kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.OpenAiCompatibleHttpClient.DebugSink debug,
                    ErrorMapper.KeySituation keySituation) {
                throw new AssertionError("the turn loop uses the reasoning-aware send");
            }

            @Override
            public CompletableFuture<ClientResult> send(
                    kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ChatRequestSpec request,
                    CompatibilityFlags flags, char[] apiKey,
                    List<kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders.ExtraHeader> headers,
                    OpenAiCompatibleLimits limits, RequestHandle handle, java.util.function.Consumer<String> onTextDelta,
                    java.util.function.Consumer<String> onReasoningDelta,
                    kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.OpenAiCompatibleHttpClient.DebugSink debug,
                    ErrorMapper.KeySituation keySituation) {
                reasoningChunks.forEach(onReasoningDelta);
                if (statusSeen != null) {
                    statusSeen.add(holder[0].statusText());
                }
                onTextDelta.accept(answer);
                return CompletableFuture.completedFuture(complete(answer, String.join("", reasoningChunks),
                                                                  List.of()));
            }
        };
    }

    @Test
    @Timeout(10)
    void reasoningFieldsStreamLiveIntoTheQuoteBlockAndAreNotRepeatedAtTheEnd() {
        List<String> status = new CopyOnWriteArrayList<>();
        TurnLoop[] holder = new TurnLoop[1];
        List<ChatMessage> history = new ArrayList<>();
        ChatClient client = streamingReasoningFields(List.of("step one\n", "step two"), "Answer.", status, holder);
        holder[0] = loop(client, history, new TurnOutcome(), null, null).withShowReasoning(true);

        holder[0].run("hi");

        assertEquals("> step one\n> step two\n\nAnswer.", String.join("", deltas),
                     "streamed before the answer, and not echoed again when the reply ends");
        assertTrue(status.get(0).startsWith("Thinking "), status.toString());
        assertEquals("Answer.", history.get(history.size() - 1).content());
    }

    @Test
    @Timeout(10)
    void streamedReasoningFieldsStayOutOfTheChatWhenShowReasoningIsOff() {
        ChatClient client = streamingReasoningFields(List.of("secret"), "Answer.", null, new TurnLoop[1]);

        loop(client, new ArrayList<>(), new TurnOutcome(), null, null).run("hi");

        assertEquals("Answer.", String.join("", deltas));
    }

    @Test
    @Timeout(5)
    void aMappedClientForwardsTheReasoningDeltasAndAppliesTheMapping() throws Exception {
        List<String> reasoning = new ArrayList<>();
        ChatClient mapped = ChatClient.mapped(
                streamingReasoningFields(List.of("r1", "r2"), "A", null, new TurnLoop[1]),
                result -> new ClientResult(result.reply(), ClientResult.End.TIMEOUT, null, false));

        ClientResult result = mapped.send(null, new CompatibilityFlags(), null, List.of(), LIMITS, new RequestHandle(),
                                          text -> {
                                          }, reasoning::add, null, ErrorMapper.KeySituation.NONE).get();

        assertEquals(List.of("r1", "r2"), reasoning);
        assertEquals(ClientResult.End.TIMEOUT, result.end(), "the mapping is applied");
    }

    @Test
    @Timeout(5)
    void aLambdaClientIgnoresReasoningDeltasThroughTheDefault() throws Exception {
        ChatClient lambda = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                -> CompletableFuture.completedFuture(complete("A", "late", List.of()));
        List<String> reasoning = new ArrayList<>();

        ClientResult result = lambda.send(null, new CompatibilityFlags(), null, List.of(), LIMITS, new RequestHandle(),
                                          text -> {
                                          }, reasoning::add, null, ErrorMapper.KeySituation.NONE).get();

        assertTrue(reasoning.isEmpty());
        assertEquals("late", result.reply().reasoning(), "the reasoning still arrives with the reply");
    }

    @Test
    @Timeout(10)
    void reasoningOnAToolRoundOpensANewParagraphInTheSameBubble() {
        AtomicInteger sent = new AtomicInteger();
        ToolRunner tools = new ToolRunner((name, args) -> "file", () -> toolExecutor, null);
        ChatClient client = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation) -> {
            if (sent.getAndIncrement() == 0) {
                onTextDelta.accept("Let me look.");
                return CompletableFuture.completedFuture(new ClientResult(
                        new AssembledReply("Let me look.", List.of(new ChatToolCall("c1", "Read", "{\"path\":\"a\"}")),
                                           "tool_calls", null, null, true, true, ""),
                        ClientResult.End.COMPLETE, null, false));
            }
            onTextDelta.accept("<think>it says file</think>It says file.");
            return CompletableFuture.completedFuture(complete("<think>it says file</think>It says file.", "",
                                                              List.of()));
        };

        loop(client, new ArrayList<>(), new TurnOutcome(), tools, readTool()).withShowReasoning(true).run("go");

        assertEquals("Let me look.\n\n> it says file\n\nIt says file.", String.join("", deltas));
    }
}

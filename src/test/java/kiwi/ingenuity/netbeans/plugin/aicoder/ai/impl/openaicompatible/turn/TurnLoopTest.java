package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.RequestHandle;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TurnLoopTest {

    private static final OpenAiCompatibleLimits LIMITS = OpenAiCompatibleLimits.defaults()
            .withCheckInterval(Duration.ofMillis(20))
            .withFirstByteTimeout(Duration.ofMillis(300))
            .withIdleTimeout(Duration.ofMillis(300))
            .withTotalCap(Duration.ofSeconds(2))
            .withRetryBackoff(Duration.ofMillis(50));

    private static final TurnLoop.RequestContext CONTEXT = new TurnLoop.RequestContext(
            "http://localhost:1/v1", "model-x", null, null, List.of(), ErrorMapper.KeySituation.NONE);

    /**
     * Answers each request with the next scripted result and records what was sent.
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
                                                    List<kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders.ExtraHeader> headers,
                                                    OpenAiCompatibleLimits limits, RequestHandle handle,
                                                    java.util.function.Consumer<String> onTextDelta,
                                                    kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.OpenAiCompatibleHttpClient.DebugSink debug,
                                                    ErrorMapper.KeySituation keySituation) {
            requests.add(request);
            int index = requests.size() - 1;
            Function<ChatRequestSpec, ClientResult> step = script.get(Math.min(index, script.size() - 1));
            ClientResult result = step.apply(request);
            if (!result.reply().text().isEmpty()) {
                onTextDelta.accept(result.reply().text());
            }
            return CompletableFuture.completedFuture(result);
        }
    }

    private static ClientResult complete(String text, String finishReason, boolean endedCleanly) {
        return new ClientResult(new AssembledReply(text, List.of(), finishReason, null, null, endedCleanly,
                                                   !text.isEmpty()),
                                ClientResult.End.COMPLETE, null, false);
    }

    private static ClientResult timeout(ClientResult.TimeoutKind kind) {
        return new ClientResult(null, ClientResult.End.TIMEOUT, null, false, kind);
    }

    private record Run(TurnOutcome outcome, List<ChatMessage> history, List<String> deltas) {

    }

    private static Run run(ChatClient client, String prompt) {
        return run(client, prompt, new TurnOutcome(), () -> false);
    }

    private static Run run(ChatClient client, String prompt, TurnOutcome outcome,
                           java.util.function.BooleanSupplier takeMail) {
        List<ChatMessage> history = new ArrayList<>();
        List<String> deltas = new ArrayList<>();
        TurnLoop loop = new TurnLoop(history, "SYSTEM PROMPT", client, new CompatibilityFlags(), LIMITS,
                                     new RequestHandle(), outcome, CONTEXT, deltas::add, null, takeMail, null, 1);
        loop.run(prompt);
        return new Run(outcome, history, deltas);
    }

    @Test
    @Timeout(10)
    void proseWithStopIsAnswered() {
        ScriptedClient client = new ScriptedClient(request -> complete("Hello there.", "stop", true));

        Run run = run(client, "hi");

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertNull(run.outcome().message());
        assertEquals(1, client.requests.size(), "a reply with no tool call ends the turn after one request");
        assertEquals(List.of("Hello there."), run.deltas());
        assertEquals(ChatRole.ASSISTANT, run.history().get(run.history().size() - 1).role());
        ChatRequestSpec sent = client.requests.get(0);
        assertEquals(ChatRole.SYSTEM, sent.messages().get(0).role());
        assertEquals("hi", sent.messages().get(1).content());
    }

    @Test
    @Timeout(10)
    void proseEndedByDoneOnlyIsAnswered() {
        ScriptedClient client = new ScriptedClient(request -> complete("Done.", null, true));

        Run run = run(client, "hi");

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals(1, client.requests.size());
    }

    @Test
    @Timeout(10)
    void endOfStreamWithoutFinishReasonOrDoneIsServerError() {
        ScriptedClient client = new ScriptedClient(request -> complete("Half an ans", null, false));

        Run run = run(client, "hi");

        assertEquals(TurnEndReason.SERVER_ERROR, run.outcome().reason());
        assertEquals(TurnLoop.EARLY_END_MESSAGE, run.outcome().message());
        assertEquals("Half an ans", run.history().get(run.history().size() - 1).content(),
                     "the partial text the user saw stays in the model's context");
    }

    @Test
    @Timeout(10)
    void emptyReplyIsNudgedOnceThenEndsWithANote() {
        ScriptedClient client = new ScriptedClient(request -> complete("", "stop", true));

        Run run = run(client, "hi");

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals(TurnLoop.EMPTY_REPLY_MESSAGE, run.outcome().message());
        assertEquals(2, client.requests.size(), "exactly one nudge");
        List<ChatMessage> second = client.requests.get(1).messages();
        assertTrue(second.get(second.size() - 1).content().endsWith(TurnLoop.EMPTY_REPLY_NUDGE),
                   second.toString());
        assertTrue(run.history().stream().noneMatch(m -> TurnLoop.EMPTY_REPLY_NUDGE.equals(m.content())),
                   "the nudge is sent, never stored");
    }

    private static Run run(ChatClient client, String prompt, java.util.function.UnaryOperator<TurnLoop> tune) {
        List<ChatMessage> history = new ArrayList<>();
        List<String> deltas = new ArrayList<>();
        TurnOutcome outcome = new TurnOutcome();
        TurnLoop loop = new TurnLoop(history, "SYSTEM PROMPT", client, new CompatibilityFlags(), LIMITS,
                                     new RequestHandle(), outcome, CONTEXT, deltas::add, null, () -> false, null, 1);
        tune.apply(loop).run(prompt);
        return new Run(outcome, history, deltas);
    }

    @Test
    @Timeout(10)
    void configuredEmptyReplyNudgesAreSentInFullThenTheNoteEnds() {
        ScriptedClient client = new ScriptedClient(request -> complete("", "stop", true));

        Run run = run(client, "hi", loop -> loop.withEmptyReplyNudges(2));

        assertEquals(3, client.requests.size(), "the first empty reply plus exactly two nudges");
        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals(TurnLoop.EMPTY_REPLY_MESSAGE, run.outcome().message());
    }

    @Test
    @Timeout(10)
    void zeroEmptyReplyNudgesEndsAtTheFirstEmptyReply() {
        ScriptedClient client = new ScriptedClient(request -> complete("", "stop", true));

        Run run = run(client, "hi", loop -> loop.withEmptyReplyNudges(0));

        assertEquals(1, client.requests.size(), "no nudge when the count is zero");
        assertEquals(TurnLoop.EMPTY_REPLY_MESSAGE, run.outcome().message());
    }

    @Test
    @Timeout(10)
    void keepAliveIsCarriedOnlyWhenSet() {
        ScriptedClient set = new ScriptedClient(request -> complete("Hi.", "stop", true));
        run(set, "hi", loop -> loop.withKeepAlive("5m"));
        assertEquals("5m", set.requests.get(0).keepAlive());

        ScriptedClient unset = new ScriptedClient(request -> complete("Hi.", "stop", true));
        run(unset, "hi");
        assertNull(unset.requests.get(0).keepAlive(), "keep_alive is not sent unless configured");
    }

    @Test
    @Timeout(10)
    void firstByteTimeoutMessageSaysToRaiseTheFirstByteTimeout() {
        Run run = run(new ScriptedClient(request -> timeout(ClientResult.TimeoutKind.FIRST_BYTE)), "hi");

        assertEquals(TurnEndReason.TIMEOUT, run.outcome().reason());
        assertTrue(run.outcome().message().contains("raise the First-byte timeout"), run.outcome().message());
        assertTrue(run.outcome().message().startsWith("No data from the server for "), run.outcome().message());
    }

    @Test
    @Timeout(10)
    void totalTimeoutMessageNamesTheTotalLimit() {
        Run run = run(new ScriptedClient(request -> timeout(ClientResult.TimeoutKind.TOTAL)), "hi");

        assertEquals(TurnEndReason.TIMEOUT, run.outcome().reason());
        assertTrue(run.outcome().message().contains("total limit"), run.outcome().message());
    }

    @Test
    @Timeout(10)
    void rejectedKeyEndsWithTheKeyMessage() {
        String keyMessage = "The server rejected the API key. Check the API key in session settings.";
        ClientResult rejected = new ClientResult(null, ClientResult.End.HTTP_ERROR,
                                                 new MappedError(MappedError.Kind.KEY_REJECTED, keyMessage, null, null),
                                                 false);

        Run run = run(new ScriptedClient(request -> rejected), "hi");

        assertEquals(TurnEndReason.SERVER_ERROR, run.outcome().reason());
        assertEquals(keyMessage, run.outcome().message());
    }

    @Test
    @Timeout(10)
    void sizeLimitNamesTheCapInMegabytes() {
        Run run = run(new ScriptedClient(request -> new ClientResult(null, ClientResult.End.SIZE_LIMIT, null, false)),
                      "hi");

        assertEquals(TurnEndReason.SIZE_LIMIT, run.outcome().reason());
        assertTrue(run.outcome().message().contains("(64 MB)"), run.outcome().message());
    }

    @Test
    @Timeout(10)
    void aCancelClaimedFirstWinsOverALaterTimeout() {
        TurnOutcome outcome = new TurnOutcome();
        ScriptedClient client = new ScriptedClient(request -> {
            // Cancel lands while the request is ending with a timeout: exactly one reason survives.
            outcome.claim(TurnEndReason.CANCELLED, "Cancelled.");
            return timeout(ClientResult.TimeoutKind.IDLE);
        });

        Run run = run(client, "hi", outcome, () -> false);

        assertEquals(TurnEndReason.CANCELLED, run.outcome().reason());
        assertEquals("Cancelled.", run.outcome().message());
    }

    @Test
    @Timeout(10)
    void mailArrivingDuringTheTurnIsAppendedBeforeTheNextRequest() {
        AtomicBoolean mailQueued = new AtomicBoolean();
        ScriptedClient client = new ScriptedClient(
                request -> {
                    mailQueued.set(true);
                    return complete("", "stop", true);
                },
                request -> complete("Answer.", "stop", true));

        Run run = run(client, "hi", new TurnOutcome(), () -> mailQueued.getAndSet(false));

        assertEquals(2, client.requests.size());
        assertTrue(client.requests.get(0).messages().stream()
                .noneMatch(m -> m.content() != null && m.content().contains(InterruptTypeEnum.MAIL_NOTIFICATION_TEXT)));
        assertTrue(client.requests.get(1).messages().stream()
                .anyMatch(m -> m.content() != null && m.content().contains(InterruptTypeEnum.MAIL_NOTIFICATION_TEXT)),
                   "the mail notice reaches the request after it arrived");
        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
    }

    @Test
    @Timeout(10)
    void aReplyWithToolCallsInASessionWithoutToolsEndsWithAServerError() {
        ClientResult withTool = new ClientResult(
                new AssembledReply("", List.of(new ChatToolCall("call_1", "GetFileContent", "{}")), "tool_calls",
                                   null, null, true, true),
                ClientResult.End.COMPLETE, null, false);

        Run run = run(new ScriptedClient(request -> withTool), "hi");

        assertEquals(TurnEndReason.SERVER_ERROR, run.outcome().reason());
        assertEquals(TurnLoop.NO_TOOLS_MESSAGE, run.outcome().message());
    }

    @Test
    @Timeout(10)
    void statusTextPrefixesTheRoundFromRoundTwo() {
        List<String> seen = new CopyOnWriteArrayList<>();
        TurnLoop[] loop = new TurnLoop[1];
        ScriptedClient client = new ScriptedClient(
                request -> {
                    seen.add(loop[0].statusText());
                    return complete("", "stop", true);
                },
                request -> {
                    seen.add(loop[0].statusText());
                    return complete("Answer.", "stop", true);
                });
        loop[0] = new TurnLoop(new ArrayList<>(), "", client, new CompatibilityFlags(), LIMITS, new RequestHandle(),
                               new TurnOutcome(), CONTEXT, text -> {
                               }, null, () -> false, null, 1);

        loop[0].run("hi");

        assertTrue(seen.get(0).startsWith("Waiting "), seen.toString());
        assertTrue(seen.get(1).startsWith("R2 · Waiting "), seen.toString());
    }
}

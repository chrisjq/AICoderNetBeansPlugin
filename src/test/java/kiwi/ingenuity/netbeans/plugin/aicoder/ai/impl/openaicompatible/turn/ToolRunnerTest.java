package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.RequestHandle;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ToolRunnerTest {

    private static final OpenAiCompatibleLimits LIMITS = OpenAiCompatibleLimits.defaults()
            .withCheckInterval(Duration.ofMillis(20))
            .withFirstByteTimeout(Duration.ofMillis(300))
            .withIdleTimeout(Duration.ofMillis(300))
            .withTotalCap(Duration.ofSeconds(2));

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "test-tool-executor");
        thread.setDaemon(true);
        return thread;
    });

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static ChatToolCall call(String id, String name, String args) {
        return new ChatToolCall(id, name, args);
    }

    @Test
    @Timeout(10)
    void callsRunInOrderOnTheToolExecutorThread() {
        List<String> ran = new CopyOnWriteArrayList<>();
        List<String> started = new CopyOnWriteArrayList<>();
        ToolRunner runner = new ToolRunner((name, args) -> {
            ran.add(name + "@" + Thread.currentThread().getName());
            return name + " ok";
        }, () -> executor, c -> started.add(c.name()));

        ToolRunner.RunResult result = runner.run(List.of(call("a", "First", "{}"), call("b", "Second", "{}")), null);

        assertEquals(List.of("First@test-tool-executor", "Second@test-tool-executor"), ran);
        assertEquals(List.of("First", "Second"), started, "a ToolUseEvent per call, in order");
        assertFalse(result.cancelled());
        assertEquals(List.of("First ok", "Second ok"), result.outcomes().stream().map(ToolRunner.ToolOutcome::result)
                     .toList());
        assertTrue(result.outcomes().stream().allMatch(ToolRunner.ToolOutcome::completed));
    }

    @Test
    @Timeout(10)
    void missingAndDuplicateIdsAreSynthesised() {
        List<ChatToolCall> calls = ToolRunner.withIds(List.of(
                call(null, "A", "{}"), call("x", "B", "{}"), call("x", "C", "{}"), call("", "D", "{}")), 3, 2);

        assertEquals("call_3_2_1", calls.get(0).id());
        assertEquals("x", calls.get(1).id());
        assertEquals("call_3_2_3", calls.get(2).id(), "a duplicate id is replaced");
        assertEquals("call_3_2_4", calls.get(3).id());
        assertEquals(List.of("A", "B", "C", "D"), calls.stream().map(ChatToolCall::name).toList());
    }

    @Test
    @Timeout(10)
    void invalidJsonArgumentsBecomeAnErrorResultAndTheNextCallStillRuns() {
        List<JsonObject> received = new CopyOnWriteArrayList<>();
        ToolRunner runner = new ToolRunner((name, args) -> {
            received.add(args);
            return "ran " + name;
        }, () -> executor, null);

        ToolRunner.RunResult result = runner.run(List.of(call("a", "Broken", "{not json"),
                                                         call("b", "Fine", "{\"path\":\"x\"}")), null);

        assertTrue(result.outcomes().get(0).result().startsWith("Invalid arguments: "),
                   result.outcomes().get(0).result());
        assertEquals("ran Fine", result.outcomes().get(1).result());
        assertEquals(1, received.size(), "the broken call is never invoked");
        assertEquals("x", received.get(0).get("path").getAsString());
    }

    /**
     * Scripted client for loop-level tests.
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
            int index = Math.min(requests.size() - 1, script.size() - 1);
            return CompletableFuture.completedFuture(script.get(index).apply(request));
        }
    }

    private static ClientResult toolReply(List<ChatToolCall> calls) {
        return new ClientResult(new AssembledReply("", calls, "tool_calls", null, null, true, true),
                                ClientResult.End.COMPLETE, null, false);
    }

    private static ClientResult prose(String text) {
        return new ClientResult(new AssembledReply(text, List.of(), "stop", null, null, true, true),
                                ClientResult.End.COMPLETE, null, false);
    }

    @Test
    @Timeout(10)
    void toolResultsFollowTheAssistantMessageInOrderAndProseThenEndsTheTurn() {
        ScriptedClient client = new ScriptedClient(
                request -> toolReply(List.of(call(null, "GetFileContent", "{\"filePath\":\"/a\"}"),
                                             call(null, "SearchInFiles", "{\"query\":\"q\"}"))),
                request -> prose("All done."));
        ToolRunner runner = new ToolRunner((name, args) -> "result of " + name, () -> executor, null);
        List<ChatMessage> history = new ArrayList<>();
        TurnOutcome outcome = new TurnOutcome();
        TurnLoop loop = new TurnLoop(history, "", client, new CompatibilityFlags(), LIMITS, new RequestHandle(),
                                     outcome,
                                     new TurnLoop.RequestContext("http://localhost:1/v1", "m", null, null, List.of(),
                                                                 ErrorMapper.KeySituation.NONE),
                                     text -> {
                                     }, null, () -> false, runner, 7);

        loop.run("read it");

        assertEquals(TurnEndReason.ANSWERED, outcome.reason());
        assertEquals(2, client.requests.size(), "a reply with tool calls never ends the turn by itself");
        // user, assistant(calls), tool, tool, assistant(prose)
        assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.TOOL, ChatRole.ASSISTANT),
                     history.stream().map(ChatMessage::role).toList());
        ChatMessage withCalls = history.get(1);
        assertNull(withCalls.content(), "no prose next to the calls");
        assertEquals(List.of("call_7_1_1", "call_7_1_2"), withCalls.toolCalls().stream().map(ChatToolCall::id).toList());
        assertEquals("call_7_1_1", history.get(2).toolCallId());
        assertEquals("result of GetFileContent", history.get(2).content());
        assertEquals("call_7_1_2", history.get(3).toolCallId());
        assertEquals("result of SearchInFiles", history.get(3).content());
        List<ChatMessage> second = client.requests.get(1).messages();
        assertEquals(ChatRole.TOOL, second.get(second.size() - 1).role(), "the next request carries the results");
        assertNotEquals(history.get(2).toolCallId(), history.get(3).toolCallId());
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders.ExtraHeader;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * P2b in the turn loop (plan 4 task 9): argument coercion, the result cap, rescue, the native-failure count, the
 * immediate and schema-mode tool calling, and reasoning that is never sent back.
 */
class TurnLoopToolCallingTest {

    private static final OpenAiCompatibleLimits LIMITS = OpenAiCompatibleLimits.defaults()
            .withCheckInterval(Duration.ofMillis(20))
            .withFirstByteTimeout(Duration.ofMillis(300))
            .withIdleTimeout(Duration.ofMillis(300))
            .withTotalCap(Duration.ofSeconds(2))
            .withRetryBackoff(Duration.ofMillis(50));

    private static final String MODEL = "model-x";

    private final ExecutorService toolExecutor = Executors.newSingleThreadExecutor();
    private final List<JsonObject> invokedWith = new CopyOnWriteArrayList<>();
    private final List<String> deltas = new CopyOnWriteArrayList<>();
    private final List<String> notices = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        toolExecutor.shutdownNow();
    }

    /**
     * One MCP-shaped tool: Read(path: string, line: integer).
     */
    private static JsonArray readTool() {
        return JsonParser.parseString("[{\"name\":\"Read\",\"description\":\"Reads a file.\",\"inputSchema\":"
                + "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},"
                + "\"line\":{\"type\":\"integer\"}},\"required\":[\"path\"]}}]").getAsJsonArray();
    }

    private static final TurnLoop.RequestContext CONTEXT = new TurnLoop.RequestContext(
            "http://localhost:1/v1", MODEL, readTool(), null, List.of(), ErrorMapper.KeySituation.NONE);

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
            ClientResult result = script.get(Math.min(index, script.size() - 1)).apply(request);
            if (!result.reply().text().isEmpty()) {
                onTextDelta.accept(result.reply().text());
            }
            return CompletableFuture.completedFuture(result);
        }
    }

    private static ClientResult prose(String text) {
        return new ClientResult(new AssembledReply(text, List.of(), "stop", null, null, true, !text.isEmpty()),
                                ClientResult.End.COMPLETE, null, false);
    }

    private static ClientResult toolCall(String argumentsJson) {
        return new ClientResult(new AssembledReply("", List.of(new ChatToolCall("c1", "Read", argumentsJson)),
                                                   "tool_calls", null, null, true, true),
                                ClientResult.End.COMPLETE, null, false);
    }

    private static ClientResult error(MappedError.Kind kind, String message) {
        return new ClientResult(null, ClientResult.End.HTTP_ERROR, new MappedError(kind, message, null, null), false);
    }

    private record Run(TurnLoop loop, TurnOutcome outcome, List<ChatMessage> history) {

    }

    private Run run(ChatClient client, ToolCallingState state, ToolCallingModeEnum mode, int resultCap,
                    String result) {
        List<ChatMessage> history = new ArrayList<>();
        TurnOutcome outcome = new TurnOutcome();
        ToolRunner tools = new ToolRunner((name, args) -> {
            invokedWith.add(args);
            return result;
        }, () -> toolExecutor, null);
        TurnLoop.Listener listener = new TurnLoop.Listener() {
            @Override
            public void textDelta(String text) {
                deltas.add(text);
            }

            @Override
            public void notice(String text) {
                notices.add(text);
            }
        };
        TurnLoop loop = new TurnLoop(history, "SYSTEM", client, new CompatibilityFlags(), LIMITS, new RequestHandle(),
                                     outcome, CONTEXT, listener, null, () -> false, tools, 1)
                .withToolCalling(state, mode)
                .withToolResultCap(resultCap);
        loop.run("go");
        return new Run(loop, outcome, history);
    }

    private Run run(ChatClient client, ToolCallingState state) {
        return run(client, state, ToolCallingModeEnum.NATIVE, 0, "file text");
    }

    private static String systemPromptOf(ChatRequestSpec request) {
        ChatMessage first = request.messages().get(0);
        return first.role() == ChatRole.SYSTEM ? first.content() : "";
    }

    // ---- coercion, result cap, rescue ----
    @Test
    @Timeout(10)
    void argumentsAreCoercedToTheToolSchemaBeforeTheCall() {
        ScriptedClient client = new ScriptedClient(request -> toolCall("{\"Path\":\"a.txt\",\"line\":\"12\"}"),
                                                   request -> prose("Done."));

        Run run = run(client, new ToolCallingState());

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals(1, invokedWith.size());
        JsonObject args = invokedWith.get(0);
        assertEquals("a.txt", args.get("path").getAsString(), args.toString());
        assertTrue(args.get("line").getAsJsonPrimitive().isNumber(), args.toString());
        assertEquals(12, args.get("line").getAsInt());
    }

    @Test
    @Timeout(10)
    void longResultsAreCappedWhenAppended() {
        ScriptedClient client = new ScriptedClient(request -> toolCall("{\"path\":\"a\"}"), request -> prose("Done."));

        Run run = run(client, new ToolCallingState(), ToolCallingModeEnum.NATIVE, 50, "x".repeat(500));

        ChatMessage toolMessage = run.history().stream().filter(m -> m.role() == ChatRole.TOOL).findFirst()
                .orElseThrow();
        assertTrue(toolMessage.content().length() < 500, toolMessage.content());
        assertTrue(toolMessage.content().contains("[truncated at 50"), toolMessage.content());
    }

    @Test
    @Timeout(10)
    void toolJsonWrittenAsProseIsRescuedAndRun() {
        ScriptedClient client = new ScriptedClient(
                request -> prose("I will read it: {\"name\":\"Read\",\"arguments\":{\"path\":\"a.txt\"}}"),
                request -> prose("Done."));
        ToolCallingState state = new ToolCallingState();

        Run run = run(client, state);

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals(1, invokedWith.size(), "the rescued call ran");
        assertEquals("a.txt", invokedWith.get(0).get("path").getAsString());
        assertEquals(0, state.nativeFailures(MODEL), "a rescued call is not a native failure");
    }

    // ---- native-failure count ----
    @Test
    @Timeout(10)
    void anInvalidNativeCallIsCountedAndAWorkingRoundResetsTheCount() {
        ToolCallingState state = new ToolCallingState();
        run(new ScriptedClient(request -> toolCall("not json"), request -> prose("Done.")), state);
        assertEquals(1, state.nativeFailures(MODEL));

        run(new ScriptedClient(request -> toolCall("{\"path\":\"a\"}"), request -> prose("Done.")), state);

        assertEquals(0, state.nativeFailures(MODEL), "a working native round resets the count");
    }

    @Test
    @Timeout(10)
    void aServerToolCallParseErrorIsANativeFailure() {
        ToolCallingState state = new ToolCallingState();

        run(new ScriptedClient(request -> error(MappedError.Kind.SERVER_ERROR, "error parsing tool call"),
                               request -> prose("Done.")), state);

        assertEquals(1, state.nativeFailures(MODEL));
        assertFalse(state.switchedToSchema(), "the switch applies from the next turn, decided by the manager");
    }

    // ---- immediate switch ----
    @Test
    @Timeout(10)
    void toolsRejectedOutrightSwitchToSchemaAtOnceAndResend() {
        ScriptedClient client = new ScriptedClient(
                request -> error(MappedError.Kind.TOOLS_UNSUPPORTED, "model does not support tools"),
                request -> prose("{\"message\":\"Hi there.\",\"tool_name\":\"\",\"tool_arguments\":{}}"));
        ToolCallingState state = new ToolCallingState();

        Run run = run(client, state);

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals(List.of(TurnLoop.SWITCHED_IMMEDIATELY_MESSAGE), notices);
        assertEquals(2, client.requests.size());
        assertNotNull(client.requests.get(0).tools());
        assertNull(client.requests.get(1).tools(), "the resend offers no native tools");
        String prompt = systemPromptOf(client.requests.get(1));
        assertTrue(prompt.contains("tool_name") && prompt.contains("- Read("), prompt);
        assertTrue(state.switchedToSchema(), "the switch is sticky for the session");
        assertEquals(0, state.nativeFailures(MODEL), "an immediate switch is not counted");
        assertEquals(ToolCallingModeEnum.SCHEMA, run.loop().mode());
        assertEquals("Hi there.", run.history().get(run.history().size() - 1).content());
        assertEquals("Hi there.", String.join("", deltas), "the JSON is held back and the message shown");
    }

    @Test
    @Timeout(10)
    void theImmediateSwitchHappensAtMostOncePerTurn() {
        ScriptedClient client = new ScriptedClient(
                request -> error(MappedError.Kind.TOOLS_UNSUPPORTED, "model does not support tools"));

        Run run = run(client, new ToolCallingState());

        assertEquals(TurnEndReason.SERVER_ERROR, run.outcome().reason());
        assertEquals(2, client.requests.size());
        assertEquals(1, notices.size());
    }

    @Test
    @Timeout(10)
    void noImmediateSwitchOnceSomethingHappenedInTheTurn() {
        ScriptedClient client = new ScriptedClient(
                request -> toolCall("{\"path\":\"a\"}"),
                request -> error(MappedError.Kind.TOOLS_UNSUPPORTED, "model does not support tools"));
        ToolCallingState state = new ToolCallingState();

        Run run = run(client, state);

        assertEquals(TurnEndReason.SERVER_ERROR, run.outcome().reason());
        assertTrue(notices.isEmpty());
        assertFalse(state.switchedToSchema());
    }

    // ---- schema mode ----
    @Test
    @Timeout(10)
    void schemaModeRunsTheToolAndFeedsTheResultBackAsText() {
        ScriptedClient client = new ScriptedClient(
                request -> prose("{\"message\":\"\",\"tool_name\":\"Read\",\"tool_arguments\":{\"path\":\"a\"}}"),
                request -> prose("{\"message\":\"It says hello.\",\"tool_name\":\"\",\"tool_arguments\":{}}"));

        Run run = run(client, new ToolCallingState(), ToolCallingModeEnum.SCHEMA, 0, "hello");

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals(1, invokedWith.size());
        assertTrue(client.requests.stream().allMatch(r -> r.tools() == null), "schema mode never offers native tools");
        assertTrue(run.history().stream().noneMatch(m -> m.role() == ChatRole.TOOL || !m.toolCalls().isEmpty()),
                   "schema mode stores no native tool messages");
        assertTrue(run.history().stream().anyMatch(m -> m.role() == ChatRole.USER
                && m.content().equals(TurnLoop.SCHEMA_RESULT_PREFIX + "Read:\nhello")), run.history().toString());
        assertEquals("It says hello.", run.history().get(run.history().size() - 1).content());
    }

    @Test
    @Timeout(10)
    void schemaModeSendsTheResponseFormatWithTheToolNamesAndNativeModeDoesNot() {
        ScriptedClient schemaClient = new ScriptedClient(
                request -> prose("{\"message\":\"Hi.\",\"tool_name\":\"\",\"tool_arguments\":{}}"));
        run(schemaClient, new ToolCallingState(), ToolCallingModeEnum.SCHEMA, 0, "unused");

        JsonObject format = schemaClient.requests.get(0).responseFormat();
        assertNotNull(format, "schema mode constrains the reply");
        assertEquals("json_schema", format.get("type").getAsString());
        assertTrue(format.toString().contains("\"Read\""), format.toString());

        ScriptedClient nativeClient = new ScriptedClient(request -> prose("Hi."));
        run(nativeClient, new ToolCallingState());

        assertNull(nativeClient.requests.get(0).responseFormat(), "native mode sends no response_format");
    }

    @Test
    @Timeout(10)
    void theImmediateSwitchResendsWithTheResponseFormat() {
        ScriptedClient client = new ScriptedClient(
                request -> error(MappedError.Kind.TOOLS_UNSUPPORTED, "model does not support tools"),
                request -> prose("{\"message\":\"Hi.\",\"tool_name\":\"\",\"tool_arguments\":{}}"));

        run(client, new ToolCallingState());

        assertNull(client.requests.get(0).responseFormat());
        assertNotNull(client.requests.get(1).responseFormat());
    }

    @Test
    @Timeout(10)
    void schemaModeProseStreamsAsUsual() {
        ScriptedClient client = new ScriptedClient(request -> prose("Plain answer."));

        Run run = run(client, new ToolCallingState(), ToolCallingModeEnum.SCHEMA, 0, "unused");

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals("Plain answer.", String.join("", deltas));
    }

    @Test
    @Timeout(10)
    void schemaRepliesNamingAnUnknownToolEndMalformed() {
        ScriptedClient client = new ScriptedClient(
                request -> prose("{\"message\":\"\",\"tool_name\":\"Nope\",\"tool_arguments\":{}}"));

        Run run = run(client, new ToolCallingState(), ToolCallingModeEnum.SCHEMA, 0, "unused");

        assertEquals(TurnEndReason.MALFORMED, run.outcome().reason());
        assertEquals(2, client.requests.size());
        assertTrue(invokedWith.isEmpty());
    }

    // ---- reasoning ----
    @Test
    @Timeout(10)
    void reasoningIsCollectedAndNeverSentBack() {
        ScriptedClient client = new ScriptedClient(request -> new ClientResult(
                new AssembledReply("<think>secret plan</think>The answer.", List.of(), "stop", null, null, true, true,
                                   "field reasoning"),
                ClientResult.End.COMPLETE, null, false));

        Run run = run(client, new ToolCallingState());

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals("The answer.", run.history().get(run.history().size() - 1).content());
        assertEquals("The answer.", String.join("", deltas));
        assertTrue(run.loop().reasoning().contains("secret plan"), run.loop().reasoning());
        assertTrue(run.loop().reasoning().contains("field reasoning"), run.loop().reasoning());
        assertTrue(run.history().stream().noneMatch(m -> m.content() != null
                && (m.content().contains("secret") || m.content().contains("field reasoning"))));
    }
}

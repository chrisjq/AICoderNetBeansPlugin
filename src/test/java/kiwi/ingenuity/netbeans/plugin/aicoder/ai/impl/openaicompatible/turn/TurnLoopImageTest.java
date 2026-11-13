package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolResult;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tool images in the turn loop: sent once in the wire tail after the results, never stored, never merged away, dropped
 * when the policy says so, and resent without when the server refuses them.
 */
class TurnLoopImageTest {

    private static final OpenAiCompatibleLimits LIMITS = OpenAiCompatibleLimits.defaults()
            .withCheckInterval(Duration.ofMillis(20))
            .withFirstByteTimeout(Duration.ofMillis(300))
            .withIdleTimeout(Duration.ofMillis(300))
            .withTotalCap(Duration.ofSeconds(2))
            .withRetryBackoff(Duration.ofMillis(50));

    private static final String TOOL = "Read";
    private static final String IMAGE_BASE64 = "iVBORw0KGgo=";

    private final ExecutorService toolExecutor = Executors.newSingleThreadExecutor();
    private final List<String> notices = new CopyOnWriteArrayList<>();
    private final AtomicInteger rejections = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private TurnLoop.Counts counts = TurnLoop.Counts.defaults();

    @AfterEach
    void tearDown() {
        toolExecutor.shutdownNow();
    }

    private static JsonArray readTool() {
        return JsonParser.parseString("[{\"name\":\"" + TOOL + "\",\"description\":\"Reads a file.\",\"inputSchema\":"
                + "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}}]")
                .getAsJsonArray();
    }

    private static final TurnLoop.RequestContext CONTEXT = new TurnLoop.RequestContext(
            "http://localhost:1/v1", "model-x", readTool(), null, List.of(), ErrorMapper.KeySituation.NONE);

    private static final class ScriptedClient implements ChatClient {

        final List<Function<ChatRequestSpec, ClientResult>> script;
        final List<List<ChatMessage>> requests = new CopyOnWriteArrayList<>();

        @SafeVarargs
        ScriptedClient(Function<ChatRequestSpec, ClientResult>... steps) {
            script = new ArrayList<>(List.of(steps));
        }

        @Override
        public CompletableFuture<ClientResult> send(ChatRequestSpec request, CompatibilityFlags flags, char[] apiKey,
                                                    List<ExtraHeader> headers, OpenAiCompatibleLimits limits,
                                                    RequestHandle handle, Consumer<String> onTextDelta,
                                                    DebugSink debug, ErrorMapper.KeySituation keySituation) {
            // Copies, so a later rewording of a stored message cannot change what this request carried.
            requests.add(request.messages().stream().map(ChatMessage::copy).toList());
            int index = requests.size() - 1;
            return CompletableFuture.completedFuture(script.get(Math.min(index, script.size() - 1)).apply(request));
        }
    }

    private static ClientResult prose(String text) {
        return new ClientResult(new AssembledReply(text, List.of(), "stop", null, null, true, !text.isEmpty()),
                                ClientResult.End.COMPLETE, null, false);
    }

    private static ClientResult toolCall(String id) {
        return new ClientResult(new AssembledReply("", List.of(new ChatToolCall(id, TOOL, "{\"path\":\"a.png\"}")),
                                                   "tool_calls", null, null, true, true),
                                ClientResult.End.COMPLETE, null, false);
    }

    private static ClientResult schemaToolCall() {
        return prose("{\"message\":\"\",\"tool_name\":\"" + TOOL + "\",\"tool_arguments\":{\"path\":\"a.png\"}}");
    }

    private static ClientResult error(MappedError.Kind kind, String message) {
        return new ClientResult(null, ClientResult.End.HTTP_ERROR, new MappedError(kind, message, null, null), false);
    }

    private record Run(TurnOutcome outcome, List<ChatMessage> history) {

    }

    /**
     * The first call returns text and one image, every later call text only.
     */
    private McpToolResult imageOnFirstCall(String name, Object arguments) {
        if (toolCalls.incrementAndGet() == 1) {
            return McpToolResult.withImage("a.png, PNG, 2×2",
                                           new McpToolResult.ImagePart("image/png", IMAGE_BASE64, 2, 2));
        }
        return McpToolResult.text("plain text");
    }

    private Run run(ChatClient client, TurnLoop.ImagePolicy policy, ToolCallingModeEnum mode, BooleanSupplier mail) {
        List<ChatMessage> history = new ArrayList<>();
        TurnOutcome outcome = new TurnOutcome();
        ToolRunner tools = ToolRunner.withResults(this::imageOnFirstCall, () -> toolExecutor, null);
        TurnLoop.Listener listener = new TurnLoop.Listener() {
            @Override
            public void textDelta(String text) {
            }

            @Override
            public void notice(String text) {
                notices.add(text);
            }
        };
        new TurnLoop(history, "SYSTEM", client, new CompatibilityFlags(), LIMITS, new RequestHandle(), outcome,
                     CONTEXT, listener, null, mail, tools, 1)
                .withCounts(counts)
                .withToolCalling(new ToolCallingState(), mode)
                .withImagePolicy(policy, rejections::incrementAndGet)
                .run("look at a.png");
        return new Run(outcome, history);
    }

    private Run run(ChatClient client, TurnLoop.ImagePolicy policy) {
        return run(client, policy, ToolCallingModeEnum.NATIVE, () -> false);
    }

    private static long imagesIn(List<ChatMessage> messages) {
        return messages.stream().mapToLong(m -> m.images().size()).sum();
    }

    private static ChatMessage storedResult(Run run) {
        return run.history().stream().filter(m -> m.role() == ChatRole.TOOL).findFirst().orElseThrow();
    }

    @Test
    @Timeout(10)
    void theImageIsSentOnceInAUserMessageAfterTheResultAndNeverStored() {
        ScriptedClient client = new ScriptedClient(request -> toolCall("c1"), request -> toolCall("c2"),
                                                   request -> prose("A red square."));

        Run run = run(client, TurnLoop.ImagePolicy.SEND);

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        List<ChatMessage> second = client.requests.get(1);
        ChatMessage last = second.get(second.size() - 1);
        assertEquals(ChatRole.USER, last.role());
        assertEquals(1, last.images().size());
        assertEquals(IMAGE_BASE64, last.images().get(0).base64());
        assertTrue(last.content().startsWith(TurnLoop.IMAGE_MESSAGE_PREFIX + TOOL + " (call c1)"), last.content());
        assertEquals(ChatRole.TOOL, second.get(second.size() - 2).role(), "the result still follows the call");
        assertEquals(0, imagesIn(client.requests.get(2)), "sent in exactly one request");
        assertEquals(0, imagesIn(run.history()), "the history never holds an image");
        assertTrue(storedResult(run).content().contains(TurnLoop.imageAttachedNote(TOOL)), storedResult(run).content());
    }

    @Test
    @Timeout(10)
    void aMailNoticeBeforeTheImageDoesNotMergeItAway() {
        AtomicInteger mailChecks = new AtomicInteger();
        ScriptedClient client = new ScriptedClient(request -> toolCall("c1"), request -> prose("Seen."));

        run(client, TurnLoop.ImagePolicy.SEND, ToolCallingModeEnum.NATIVE, () -> mailChecks.incrementAndGet() == 2);

        List<ChatMessage> second = client.requests.get(1);
        assertTrue(second.stream().anyMatch(m -> InterruptTypeEnum.MAIL_NOTIFICATION_TEXT.equals(m.content())),
                   "the mail notice was sent");
        assertEquals(1, imagesIn(second), "the adjacent user messages kept the image");
    }

    @Test
    @Timeout(10)
    void schemaModeKeepsTheImageApartFromTheResultMessage() {
        ScriptedClient client = new ScriptedClient(request -> schemaToolCall(),
                                                   request -> prose("{\"message\":\"Seen.\",\"tool_name\":\"\","
                                                           + "\"tool_arguments\":{}}"));

        Run run = run(client, TurnLoop.ImagePolicy.SEND, ToolCallingModeEnum.SCHEMA, () -> false);

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        List<ChatMessage> second = client.requests.get(1);
        assertEquals(1, imagesIn(second));
        ChatMessage last = second.get(second.size() - 1);
        assertTrue(last.content().startsWith(TurnLoop.IMAGE_MESSAGE_PREFIX), last.content());
        assertTrue(second.get(second.size() - 2).content().startsWith(TurnLoop.SCHEMA_RESULT_PREFIX + TOOL),
                   "the result message is still its own message");
    }

    @Test
    @Timeout(10)
    void offDropsTheImageAndSaysWhy() {
        ScriptedClient client = new ScriptedClient(request -> toolCall("c1"), request -> prose("Ok."));

        Run run = run(client, TurnLoop.ImagePolicy.DROP_SETTING_OFF);

        assertEquals(0, imagesIn(client.requests.get(1)));
        assertTrue(storedResult(run).content().contains(TurnLoop.IMAGE_SETTING_OFF_NOTE));
        assertTrue(storedResult(run).content().startsWith("a.png, PNG"), "the info line is kept");
    }

    @Test
    @Timeout(10)
    void noVisionDropsTheImageAndSaysWhy() {
        ScriptedClient client = new ScriptedClient(request -> toolCall("c1"), request -> prose("Ok."));

        Run run = run(client, TurnLoop.ImagePolicy.DROP_NO_VISION);

        assertEquals(0, imagesIn(client.requests.get(1)));
        assertTrue(storedResult(run).content().contains(TurnLoop.IMAGE_NO_VISION_NOTE));
        assertEquals(0, rejections.get());
    }

    @Test
    @Timeout(10)
    void aRejectedImageIsResentWithoutAndTheDecisionRecorded() {
        ScriptedClient client = new ScriptedClient(request -> toolCall("c1"),
                                                   request -> error(MappedError.Kind.UNNAMED_REJECTION,
                                                                    "image input is not supported by this model"),
                                                   request -> prose("I cannot see it, but the file is a PNG."));

        Run run = run(client, TurnLoop.ImagePolicy.SEND);

        assertEquals(TurnEndReason.ANSWERED, run.outcome().reason());
        assertEquals(3, client.requests.size());
        assertEquals(1, imagesIn(client.requests.get(1)));
        assertEquals(0, imagesIn(client.requests.get(2)), "resent without the image");
        assertEquals(1, rejections.get(), "the no-vision decision is recorded");
        String stored = storedResult(run).content();
        assertTrue(stored.contains(TurnLoop.IMAGE_NO_VISION_NOTE), stored);
        assertFalse(stored.contains(TurnLoop.imageAttachedNote(TOOL)), stored);
        assertTrue(notices.contains(TurnLoop.IMAGES_REJECTED_NOTICE), notices.toString());
    }

    @Test
    @Timeout(10)
    void aRejectedImageOnTheStepLimitsFinalRequestIsResentWithoutIt() {
        TurnLoop.Counts defaults = TurnLoop.Counts.defaults();
        counts = new TurnLoop.Counts(1, defaults.maxLengthContinuations(), defaults.maxMalformedRounds(),
                                     defaults.repetitionWindow(), defaults.unproductiveRounds());
        ScriptedClient client = new ScriptedClient(request -> toolCall("c1"),
                                                   request -> error(MappedError.Kind.UNNAMED_REJECTION,
                                                                    "this model does not support image input"),
                                                   request -> prose("It is a PNG; I cannot see it."));

        Run run = run(client, TurnLoop.ImagePolicy.SEND);

        assertEquals(TurnEndReason.STEP_LIMIT, run.outcome().reason());
        assertFalse(run.outcome().message().contains(TurnLoop.STEP_LIMIT_REJECTED_NOTE.strip()),
                    "the resent final request answered: " + run.outcome().message());
        assertEquals(3, client.requests.size());
        List<ChatMessage> rejected = client.requests.get(1);
        assertEquals(1, imagesIn(rejected), "the final request carried the image");
        assertEquals(TurnLoop.STEP_LIMIT_PROMPT, rejected.get(rejected.size() - 1).content());
        List<ChatMessage> resent = client.requests.get(2);
        assertEquals(0, imagesIn(resent), "resent without the image");
        assertEquals(TurnLoop.STEP_LIMIT_PROMPT, resent.get(resent.size() - 1).content(),
                     "the resend is still the final request");
        assertEquals(1, rejections.get());
        assertTrue(storedResult(run).content().contains(TurnLoop.IMAGE_NO_VISION_NOTE), storedResult(run).content());
        assertTrue(notices.contains(TurnLoop.IMAGES_REJECTED_NOTICE), notices.toString());
    }

    @Test
    @Timeout(5)
    void ourOwnImageTextEchoedInAnErrorIsNotARejection() {
        String echoed = "invalid request: messages[4].content: \"" + TurnLoop.IMAGE_MESSAGE_PREFIX + TOOL
                + " (call c1):\" and \"a.png " + TurnLoop.imageAttachedNote(TOOL) + "\" exceed the limit";

        assertFalse(TurnLoop.isImageRejection(new MappedError(MappedError.Kind.UNNAMED_REJECTION, echoed, null,
                                                              null)), echoed);
        assertTrue(TurnLoop.isImageRejection(new MappedError(MappedError.Kind.UNNAMED_REJECTION,
                                                             echoed + "; image input is not supported", null, null)),
                   "a real rejection alongside the echo still counts");
        String multiEcho = "invalid request: messages[4].content: \"" + TurnLoop.IMAGE_MESSAGE_PREFIX + TOOL
                + " (call c1), 3 images:\nImage from " + TOOL + " (call c2):\" exceeds the limit";
        assertFalse(TurnLoop.isImageRejection(new MappedError(MappedError.Kind.UNNAMED_REJECTION, multiEcho, null,
                                                              null)), "the multi-image wording is ours too: " + multiEcho);
    }

    @Test
    @Timeout(10)
    void anUnrelatedErrorIsNotTakenForAnImageRejection() {
        ScriptedClient client = new ScriptedClient(request -> toolCall("c1"),
                                                   request -> error(MappedError.Kind.UNNAMED_REJECTION,
                                                                    "temperature out of range"));

        Run run = run(client, TurnLoop.ImagePolicy.SEND);

        assertEquals(TurnEndReason.SERVER_ERROR, run.outcome().reason());
        assertEquals(0, rejections.get());
    }

    @Test
    @Timeout(5)
    void imageRejectionNeedsARejectionKindAndAnImageMention() {
        assertTrue(TurnLoop.isImageRejection(new MappedError(MappedError.Kind.SERVER_ERROR,
                                                             "image input is not supported - you may need the mmproj",
                                                             null, null)));
        assertTrue(TurnLoop.isImageRejection(new MappedError(MappedError.Kind.UNNAMED_REJECTION,
                                                             "messages[3].content must be a string", null, null)));
        assertFalse(TurnLoop.isImageRejection(new MappedError(MappedError.Kind.RATE_LIMITED, "image", null, null)));
        assertFalse(TurnLoop.isImageRejection(new MappedError(MappedError.Kind.UNNAMED_REJECTION, "bad tools", null,
                                                              null)));
        assertFalse(TurnLoop.isImageRejection(null));
    }
}

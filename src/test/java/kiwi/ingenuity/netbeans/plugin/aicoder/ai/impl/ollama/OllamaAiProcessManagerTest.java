package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRequest;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.HttpAiClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.OpenAiHttpStatusException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.OpenAiJsonKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.OpenAiToolCallParseException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.AbstractChatContextBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextBrokerSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextTrimStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.OllamaChatContextBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.session.OllamaAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.ContextPersistenceManager;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(5)
class OllamaAiProcessManagerTest {

    /**
     * Most tests here script bare-prose replies and expect one request per turn. Under the EndTurn contract
     * bare prose is NARRATION, which continues the turn, so at the production defaults (narration 10,
     * unproductive rounds 3) every such test consumes more scripted replies and every request count is off.
     * <p>
     * Pinning the narration limit (counter B) to 1 restores "prose ends the turn" for tests that are about
     * something else entirely — trimming, calibration, history, retries — so they keep asserting what they
     * were written to assert. Pinning the unproductive-round limit (counter A) to 1 keeps the single old
     * counter's tool-round semantics: one round whose results were all already seen ends the turn. Tests that
     * are genuinely ABOUT either limit raise it themselves.
     * <p>
     * The iteration cap is pinned low for a different reason: at the production default of 500 a runaway
     * takes 500 requests to stop, which is far longer than the 5s class timeout. And note that
     * {@code @Timeout} does NOT interrupt the loop — it reports the failure while the code keeps allocating,
     * which is how an uncapped run reached 8 GB of ChatMessage objects on 2026-09-25.
     */
    @BeforeEach
    void pinLoopBoundsForTests() {
        PluginSettings.setOllamaMaxNarrationTurns(1);
        PluginSettings.setOllamaMaxUnproductiveRounds(1);
        PluginSettings.setOllamaMaxToolIterations(20);
    }

    @AfterEach
    void restoreLoopBounds() {
        PluginSettings.setOllamaMaxNarrationTurns(10);
        PluginSettings.setOllamaMaxUnproductiveRounds(3);
        PluginSettings.setOllamaMaxToolIterations(500);
    }

    private static int countOccurrences(String text, String target) {
        if (text == null || target == null || target.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }

    /**
     * Whether a request's message list already carries the mid-turn mail poke. The notice is the exact
     * {@link InterruptTypeEnum#MAIL_NOTIFICATION_TEXT} constant as a USER message — no real user ever types
     * that text, so equality against the constant is a precise matcher.
     */
    private static boolean containsMailNotice(List<ChatMessage> messages) {
        for (ChatMessage m : messages) {
            if (m.role() == ChatRole.USER && InterruptTypeEnum.MAIL_NOTIFICATION_TEXT.equals(m.content())) {
                return true;
            }
        }
        return false;
    }

    private static AiSession newSession() {
        return new AiSession("sid", "session", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL
                        .createDefaultSettings(),
                Instant.now(), Instant.now());
    }

    private static void awaitIdle(OllamaAiProcessManager manager) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (manager.isProcessing() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertFalse(manager.isProcessing(), "turn did not finish within 5s");
    }

    /**
     * A contract-following reply: the model ends its turn with an EndTurn tool call carrying a message.
     */
    private static final ChatResult END_TURN_ANSWER = new ChatResult(
            "{\"message\":\"answer\",\"tool_name\":\"EndTurn\",\"tool_arguments\":{}}", List.of(), "stop");

    /**
     * The EndTurn envelope in the form the extractor path recognises in NATIVE mode: {@code name} and
     * {@code arguments} keys (native replies carry no schema-shaped {@code tool_name}). Ends the turn the
     * same way END_TURN_ANSWER does in schema mode.
     */
    private static final ChatResult NATIVE_END_TURN_ANSWER = new ChatResult(
            "{\"name\":\"EndTurn\",\"arguments\":{\"message\":\"native final answer\"}}", List.of(), "stop");

    /**
     * Serves a fixed {@code /api/tags} body (and an empty {@code /v1/models} list) on an ephemeral loopback
     * port — never the real Ollama, per the standing "do not hammer the box" instruction. A fresh ephemeral
     * port per call keeps the discovery cache entry isolated from every other test.
     */
    private static HttpServer startFakeOllamaDiscoveryServer(String tagsResponseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] bytes = tagsResponseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.createContext("/v1/models", exchange -> {
            byte[] bytes = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    /**
     * The EndTurn contract end to end. This is the test whose ABSENCE let EndTurn ship as dead code: the
     * handler only fires when the call survives parsing, and both SchemaToolCalls.parse and ToolCallExtractor
     * drop any name not present in knownToolNames. EndTurn was offered as a tool and allowed by the
     * response-format grammar, but never added to that set, so every EndTurn the model sent was silently
     * discarded — the turn ran on and the raw envelope was emitted as narration.
     * <p>
     * Deleting {@code knownToolNames.add(END_TURN_TOOL_NAME)} from OllamaAiProcessManager turns this red.
     */
    @Test
    void endTurnEndsTheTurnAndItsMessageArgumentIsTheFinalAnswer() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("{\"message\":\"envelope text\",\"tool_name\":\"EndTurn\","
                        + "\"tool_arguments\":{\"message\":\"the real final answer\"}}",
                        List.of(), "stop")));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("do the thing", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, fake.requests.size(),
                "EndTurn must end the turn on the first reply, not continue as narration");
        assertTrue(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td
                && td.text() != null && td.text().contains("the real final answer")),
                "the EndTurn message argument must be the user-visible final answer");
        assertFalse(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td
                && td.text() != null && td.text().contains("tool_name")),
                "the raw EndTurn envelope must never reach the user");
        assertTrue(manager.invokedToolNames.isEmpty(),
                "EndTurn is synthetic and must never be dispatched to the bridge as a real tool");
    }

    /**
     * EndTurn with no usable message argument falls back to the schema envelope's top-level message, so a
     * model that sets one but not the other still produces a visible answer rather than a silent turn.
     */
    @Test
    void endTurnWithoutAMessageArgumentFallsBackToTheEnvelopeMessage() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("{\"message\":\"fallback answer\",\"tool_name\":\"EndTurn\","
                        + "\"tool_arguments\":{}}",
                        List.of(), "stop")));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("do the thing", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td
                && td.text() != null && td.text().contains("fallback answer")),
                "with no arguments.message the top-level envelope message must be used");
    }

    /**
     * EndTurn arriving as a NATIVE-STYLE envelope, which the schema parser does not recognise and hands to
     * ToolCallExtractor. That path has no exemption for EndTurn — unlike SchemaToolCalls:313, which
     * special-cases the literal — so it drops any name missing from knownToolNames.
     * <p>
     * This is the test that actually bites: comment out {@code knownToolNames.add(END_TURN_TOOL_NAME)} in
     * OllamaAiProcessManager and it goes red, because the EndTurn call is discarded, the turn continues as
     * narration, and the raw envelope is emitted to the user. The two schema-envelope tests above pass either
     * way and cannot catch this.
     */
    @Test
    void endTurnArrivingThroughTheExtractorPathAlsoEndsTheTurn() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        // No tool_name/message keys, so SchemaToolCalls.parse falls through to ToolCallExtractor.
        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("{\"name\":\"EndTurn\",\"arguments\":{\"message\":\"extractor final answer\"}}",
                        List.of(), "stop"),
                new ChatResult("this second reply must never be requested", List.of(), "stop")));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("do the thing", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, fake.requests.size(),
                "an EndTurn reaching us through the extractor must end the turn on the first reply");
        assertFalse(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td
                && td.text() != null && td.text().contains("\"name\"")),
                "the raw EndTurn envelope must never be emitted to the user as narration");
        assertTrue(manager.invokedToolNames.isEmpty(),
                "EndTurn is synthetic and must never be dispatched to the bridge");
    }

    @TempDir
    Path contextTempDir;

    @Test
    void toolCallThenFinalTextLoopsAndTerminates() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("{\"name\":\"GetPluginVersion\",\"arguments\":{}}",
                        List.of(), "stop"),
                new ChatResult("Finished.", List.of(), "stop")));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        AiSession shared = new AiSession("sid", "session", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(),
                Instant.now(), Instant.now());
        manager.setCurrentSession(shared);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hello", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(2, fakeClient.requests.size());
        ChatRequest secondRequest = fakeClient.requests.get(1);
        ChatMessage assistantToolCall = secondRequest.messages().get(2);
        assertEquals(ChatRole.ASSISTANT, assistantToolCall.role());
        assertEquals(1, assistantToolCall.toolCalls().size());
        assertEquals("call_0_0", assistantToolCall.toolCalls().get(0).id());
        assertEquals("GetPluginVersion", assistantToolCall.toolCalls().get(0).name());
        ChatMessage toolResult = secondRequest.messages().get(3);
        assertEquals(ChatRole.TOOL, toolResult.role());
        assertEquals("call_0_0", toolResult.toolCallId());
        assertEquals("ok 1", toolResult.content());
        // Prose final answer must be streamed exactly once
        assertTrue(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td && td.text().contains("Finished.")));
        // JSON tool-call content must NOT be emitted as a TextDeltaEvent
        assertFalse(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td && td.text().contains("GetPluginVersion")));
        assertInstanceOf(TurnCompleteEvent.class, events.get(events.size() - 1));
    }

    /**
     * FIX 1 (devstral:24b) — a narration round (prose, no tool call) immediately followed by a tool-calling
     * round used to produce TWO consecutive ASSISTANT messages. Ollama's Mistral-family chat templates
     * require strict USER/ASSISTANT alternation, and the duplicate broke the template so the model stopped
     * calling tools mid-session. The two must fold into ONE assistant message: content joined, tool call
     * retained.
     * <p>
     * Deleting the merge in {@code AbstractChatContextBroker.appendAssistant} turns this red: the narration
     * keeps its own ASSISTANT entry and the tool round becomes a second, consecutive one.
     */
    @Test
    void narrationThenToolRoundSendsOneMergedAssistantMessage() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        // Bare prose is NARRATION under the EndTurn contract. This test IS about a narration round continuing
        // into a second, tool-calling round, so the narration bound must not end the turn after round one.
        PluginSettings.setOllamaMaxNarrationTurns(10);
        PluginSettings.setOllamaMaxUnproductiveRounds(10);

        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("I will check the plugin version first", List.of(), "stop"),
                new ChatResult("off I go",
                        List.of(new ChatToolCall("c1", "GetPluginVersion", "{}")), "tool_calls"),
                NATIVE_END_TURN_ANSWER));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(3, fake.requests.size());
        List<ChatMessage> finalMessages = fake.requests.get(2).messages();
        List<ChatMessage> assistants = finalMessages.stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT).toList();
        assertEquals(1, assistants.size(),
                "narration round and the tool round must fold into a single ASSISTANT entry");
        ChatMessage merged = assistants.get(0);
        assertTrue(merged.content().contains("I will check the plugin version first"),
                "narration text must survive the merge");
        assertTrue(merged.content().contains("off I go"),
                "the tool round's text must survive the merge");
        assertEquals(1, merged.toolCalls().size(), "the tool call must survive the merge");
        assertEquals("GetPluginVersion", merged.toolCalls().get(0).name());
        // The EndTurn answer is appended after request index 2 was captured, so it is verified through the
        // committed broker: exactly USER + one merged ASSISTANT + TOOL + the final answer, each separate.
        assertEquals(4, manager.broker.entryCount(),
                "USER + one merged ASSISTANT + one TOOL + the EndTurn answer");
        for (int i = 1; i < finalMessages.size(); i++) {
            assertFalse(finalMessages.get(i - 1).role() == ChatRole.ASSISTANT
                    && finalMessages.get(i).role() == ChatRole.ASSISTANT,
                    "no two consecutive ASSISTANT messages may reach the model");
        }
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count());
    }

    /**
     * Native tool calling must never put an assistant message on the wire that carries BOTH prose and
     * tool_calls — devstral:24b stops calling tools for the rest of the turn when one reaches it. The tool
     * round's narration is dropped from the committed assistant message in native mode (the call alone is the
     * message); schema mode keeps it, because there it is a distinct protocol field.
     * <p>
     * Reverting the blanked content in the tool-round append in OllamaAiProcessManager turns this red: the
     * tool-calling assistant on request two then carries "let me check that" AND the call.
     */
    @Test
    void nativeAssistantWithToolCallsNeverCarriesContentOnTheWire() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("let me check that",
                        List.of(new ChatToolCall("c0", "GetPluginVersion", "{}")), "tool_calls"),
                NATIVE_END_TURN_ANSWER));

        AiSession session = newSession();
        ((OllamaSessionSettings) session.settings()).setUseNativeToolCalling(Boolean.TRUE);
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(session);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(2, fake.requests.size());
        for (int i = 0; i < fake.requests.size(); i++) {
            for (ChatMessage m : fake.requests.get(i).messages()) {
                if (m.role() == ChatRole.ASSISTANT) {
                    boolean carriesText = m.content() != null && !m.content().isBlank();
                    assertFalse(carriesText && !m.toolCalls().isEmpty(),
                            "request " + i + ": no native-mode assistant message may carry both prose and tool_calls");
                }
            }
        }
        ChatMessage toolAssistant = fake.requests.get(1).messages().stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT).findFirst().orElseThrow();
        assertNull(toolAssistant.content(), "the tool-calling assistant must carry blank content");
        assertEquals(List.of("call_0_0"), toolAssistant.toolCalls().stream().map(ChatToolCall::id).toList(),
                "the call must survive with its per-iteration id");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count());
    }

    /**
     * Native mode end to end: the narration round MUST fold into the tool round — one assistant message with
     * the narration CONTENT dropped (never split into a separate preceding entry, which breaks devstral's
     * strict alternation). The call survives, so request three carries exactly one assistant: blank content,
     * the call. Schema keeps the narration; the fold semantics this relies on are in the broker test.
     * <p>
     * Making the broker's native merge rule drop nothing turns this red (request three's assistant then
     * carries both prose and the call — the banned shape that makes devstral stop calling tools).
     */
    @Test
    void nativeNarrationRoundFoldsIntoToolRoundWithContentDropped() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        // Bare prose is NARRATION under the EndTurn contract. This test IS about a narration round continuing
        // into a tool round, so the narration bound must not end the turn after the prose round.
        PluginSettings.setOllamaMaxNarrationTurns(10);
        PluginSettings.setOllamaMaxUnproductiveRounds(10);

        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("I will check the plugin version first", List.of(), "stop"),
                new ChatResult("off I go",
                        List.of(new ChatToolCall("c1", "GetPluginVersion", "{}")), "tool_calls"),
                NATIVE_END_TURN_ANSWER));

        AiSession session = newSession();
        ((OllamaSessionSettings) session.settings()).setUseNativeToolCalling(Boolean.TRUE);
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(session);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(3, fake.requests.size());
        for (ChatRequest request : fake.requests) {
            for (ChatMessage m : request.messages()) {
                if (m.role() == ChatRole.ASSISTANT) {
                    boolean carriesText = m.content() != null && !m.content().isBlank();
                    assertFalse(carriesText && !m.toolCalls().isEmpty(),
                            "no native-mode assistant message may carry both prose and tool_calls");
                }
            }
        }
        List<ChatMessage> assistants = fake.requests.get(2).messages().stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT).toList();
        assertEquals(1, assistants.size(),
                "the narration folds INTO the tool round — a separate preceding narration entry breaks alternation");
        assertNull(assistants.get(0).content(),
                "the narration text is dropped, not carried on the folded tool-calling message");
        assertEquals(List.of("call_1_0"), assistants.get(0).toolCalls().stream().map(ChatToolCall::id).toList(),
                "iteration-qualified id: the narration round was iteration 0, the tool round iteration 1");
        // The EndTurn answer is appended after request index 2 was captured, so it is verified through the
        // committed broker: USER + the folded ASSISTANT + one TOOL + the final answer.
        assertEquals(4, manager.broker.entryCount(),
                "USER + one folded ASSISTANT + one TOOL + the EndTurn answer");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count());
    }

    /**
     * Boss proved TWO shapes break devstral's template, not one: an assistant message carrying prose AND
     * tool_calls, and two ADJACENT assistant messages (a content-bearing {@code text</s>} immediately
     * followed by {@code [TOOL_CALLS]...</s>} — no [INST] between them, violating the template's strict
     * alternation). The broker's fold-and-drop is what keeps adjacent assistants off the wire in native mode:
     * the narration folds INTO the tool round instead of being emitted as its own preceding entry. This
     * asserts the adjacency invariant directly, across every captured request.
     * <p>
     * Reintroducing the split (refusing the native merge) turns this red: request 3 then carries the
     * narration ASSISTANT immediately followed by the call ASSISTANT.
     */
    @Test
    void nativeModeNeverEmitsTwoAdjacentAssistantMessages() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        PluginSettings.setOllamaMaxNarrationTurns(10);
        PluginSettings.setOllamaMaxUnproductiveRounds(10);

        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("I will check the plugin version first", List.of(), "stop"),
                new ChatResult("off I go",
                        List.of(new ChatToolCall("c1", "GetPluginVersion", "{}")), "tool_calls"),
                NATIVE_END_TURN_ANSWER));

        AiSession session = newSession();
        ((OllamaSessionSettings) session.settings()).setUseNativeToolCalling(Boolean.TRUE);
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(session);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
        assertTrue(done.await(5, TimeUnit.SECONDS));

        for (int i = 0; i < fake.requests.size(); i++) {
            List<ChatMessage> messages = fake.requests.get(i).messages();
            for (int j = 0; j + 1 < messages.size(); j++) {
                assertFalse(messages.get(j).role() == ChatRole.ASSISTANT
                        && messages.get(j + 1).role() == ChatRole.ASSISTANT,
                        "request " + i + ": two adjacent assistant messages break devstral's alternation");
            }
        }
    }

    /**
     * FIX 2 — a call id was "call_" + callIndex, so a turn whose first iteration carried a tool call and
     * whose second iteration carried another produced two ids "call_0" (one per iteration). Results are
     * paired to calls by id, so the duplicate made the pairing ambiguous on the committed request. Ids must
     * stay unique for the whole turn.
     * <p>
     * Reverting to "call_" + callIndex turns this red: both assistant calls carry "call_0".
     */
    @Test
    void toolCallIdsStayUniqueAcrossIterations() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        // Identical calls are caught by the repeat guard, so the two rounds call the SAME tool with DIFFERENT
        // arguments — still the same bug shape (a duplicate id across iterations).
        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("", List.of(new ChatToolCall("c0", "GetPluginVersion", "{}")), "tool_calls"),
                new ChatResult("", List.of(new ChatToolCall("c1", "GetPluginVersion", "{\"n\":2}")), "tool_calls"),
                END_TURN_ANSWER));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(3, fake.requests.size());
        List<String> callIds = fake.requests.get(2).messages().stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT)
                .flatMap(m -> m.toolCalls().stream())
                .map(ChatToolCall::id)
                .toList();
        assertEquals(List.of("call_0_0", "call_1_0"), callIds,
                "call ids must be unique across the whole turn");
        assertEquals(2, fake.requests.get(2).messages().stream()
                .filter(m -> m.role() == ChatRole.TOOL).count(),
                "each call must be paired with its own result");
    }

    /**
     * FIX 5 (devstral:24b) — the A/B showed 11 requests with zero tool calls after a mid-session flip to
     * native tool calling. History accumulates across turns and every entry serialises whichever protocol it
     * was built under; a Mistral-family template cannot switch protocols mid-conversation. When the mode the
     * next turn would build under differs from the mode the existing history was built under, the accumulated
     * dialogue must be reset — the system-role pins survive.
     * <p>
     * Removing the clearHistory in runTurn turns this red: turn two's request would still carry turn one's
     * assistant answer.
     */
    @Test
    void modeFlipResetsHistoryButKeepsPinsBetweenTurns() throws Exception {
        // Turn completion is awaited via awaitIdle below — no latch is needed.
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                // completion is awaited via awaitIdle below
            }
        };
        FakeHttpClient fake = new FakeHttpClient(List.of(
                END_TURN_ANSWER,
                new ChatResult("{\"message\":\"second answer\",\"tool_name\":\"EndTurn\",\"tool_arguments\":{}}",
                        List.of(), "stop")));

        AiSession session = newSession();
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(session);
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        awaitIdle(manager);

        // OLLAMA_LOCAL defaults to schema-based tool calling; flip the live session to native.
        ((OllamaSessionSettings) session.settings()).setUseNativeToolCalling(Boolean.TRUE);
        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);

        assertEquals(2, fake.requests.size());
        List<ChatMessage> msgs = fake.requests.get(1).messages();
        assertEquals(ChatRole.SYSTEM, msgs.get(0).role(), "the system-role pins must survive the reset");
        assertTrue(msgs.stream().noneMatch(m -> m.role() == ChatRole.ASSISTANT
        ),
                "turn two must start from a clean history — no assistant message may leak from turn one");
        // Turn two's own reply arrives only after its request is captured, so this request holds no
        // assistant message at all — neither turn one's, nor its own. The noneMatch above covers the reset.
        // (See the events assertion below for the update notice.)
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.INFO
                && se.text() != null && se.text().contains("history was reset")),
                "the flip must be reported to the user");
    }

    @Test
    void stopsAtIterationCap() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        // Arguments must differ per call: identical calls are caught by the
        // repeat guard long before the iteration cap is reached.
        List<ChatResult> repeating = new ArrayList<>();
        // This test is ABOUT the cap, so it sets its own rather than inheriting the class default —
        // the asserted counts below only mean something against a bound stated here.
        PluginSettings.setOllamaMaxToolIterations(25);
        for (int i = 0; i < 30; i++) {
            repeating.add(new ChatResult("",
                    List.of(new ChatToolCall("call-" + i, "GetPluginVersion", "{\"n\":" + i + "}")),
                    "tool_calls"));
        }
        FakeHttpClient fakeClient = new FakeHttpClient(repeating);
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        AiSession shared = new AiSession("sid", "session", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(),
                Instant.now(), Instant.now());
        manager.setCurrentSession(shared);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hello", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(25, manager.invokedToolNames.size());
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("Stopped after 25 tool iterations")));
    }

    /**
     * qwen2.5-coder:14b answered "hi" by calling UpdateSessionDescription, got back "Description updated.",
     * and — having no signal that the work was done — reissued the identical call every iteration. The tool
     * must run once and the turn must end promptly rather than at the iteration cap.
     */
    @Test
    void stopsWhenModelRepeatsTheSameCall() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        List<ChatResult> identical = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            identical.add(new ChatResult("",
                    List.of(new ChatToolCall("call-" + i, "GetPluginVersion", "{}")),
                    "tool_calls"));
        }
        TestOllamaProcessManager manager
                = new TestOllamaProcessManager(listener, new FakeHttpClient(identical));
        AiSession shared = new AiSession("sid3", "session3", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(),
                Instant.now(), Instant.now());
        manager.setCurrentSession(shared);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, manager.invokedToolNames.size(),
                "a repeated identical call must not re-run the tool");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("kept repeating the same tool call")));
        assertFalse(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("Stopped after 25")),
                "should end on the repeat guard, not grind out the iteration cap");
    }

    /**
     * Observed with qwen2.5-coder:14b: it called UpdateSessionDescription four times, varying the description
     * each time, so an arguments-based guard never matched — yet every call returned "Description updated."
     * An already-seen result means the call taught the model nothing.
     */
    @Test
    void stopsWhenRepeatedCallsVaryArgsButReturnTheSameResult() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        List<ChatResult> varying = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            varying.add(new ChatResult("",
                    List.of(new ChatToolCall("call-" + i, "GetPluginVersion",
                            "{\"description\":\"attempt " + i + "\"}")),
                    "tool_calls"));
        }
        TestOllamaProcessManager manager = new TestOllamaProcessManager(
                listener, new FakeHttpClient(varying), "Description updated.");
        AiSession shared = new AiSession("sid4", "session4", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(),
                Instant.now(), Instant.now());
        manager.setCurrentSession(shared);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(manager.invokedToolNames.size() <= 3,
                "identical results must end the turn quickly, got " + manager.invokedToolNames.size());
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("kept repeating the same tool call")));
    }

    /**
     * A stalled loop previously ended on a status line, leaving the user with no reply to "hi" at all. The
     * turn now makes one final request with no tools offered, so the model can only answer in prose.
     */
    @Test
    void nativeTrailingNarrationGetsWireOnlyUserNudgeWithoutHistoryMutation() throws Exception {
        PluginSettings.setOllamaMaxNarrationTurns(10);
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("I will inspect that.", List.of(), "stop"),
                NATIVE_END_TURN_ANSWER));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        AiSession session = newSession();
        ((OllamaSessionSettings) session.settings()).setUseNativeToolCalling(true);
        manager.setCurrentSession(session);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("inspect this", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(fake.requests.size() >= 2, "the nudge must permit a follow-up request");
        ChatRequest followUp = fake.requests.get(1);
        ChatMessage last = followUp.messages().get(followUp.messages().size() - 1);
        assertEquals(ChatRole.USER, last.role(),
                "native follow-up must end on a synthetic USER nudge, not trailing assistant prose");
        assertTrue(last.content().contains("call EndTurn"),
                "the nudge must offer EndTurn as the completion path");
        assertEquals(2, manager.broker.entryCount(),
                "the wire-only nudge must not mutate broker history");
    }

    @Test
    void schemaModeDoesNotAppendNativeTrailingAssistantNudge() throws Exception {
        PluginSettings.setOllamaMaxNarrationTurns(10);
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        AiSession session = newSession();
        ((OllamaSessionSettings) session.settings()).setUseNativeToolCalling(false);
        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("{\"message\":\"schema narration\",\"tool_name\":\"\"}", List.of(), "stop"),
                END_TURN_ANSWER));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(session);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("schema task", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(fake.requests.size() >= 2);
        ChatRequest followUp = fake.requests.get(1);
        ChatMessage last = followUp.messages().get(followUp.messages().size() - 1);
        assertEquals(ChatRole.ASSISTANT, last.role(),
                "schema mode must not receive the native-only USER nudge");
        assertTrue(followUp.responseFormat() != null,
                "schema mode remains constrained by response_format");
    }

    @Test
    void nativeNarrationAfterNudgeIsAcceptedAsFinalAnswer() throws Exception {
        PluginSettings.setOllamaMaxNarrationTurns(10);
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        AiSession session = newSession();
        ((OllamaSessionSettings) session.settings()).setUseNativeToolCalling(true);
        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("complete answer, first wording", List.of(), "stop"),
                new ChatResult("complete answer, second wording", List.of(), "stop"),
                new ChatResult("must not be requested", List.of(), "stop")));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(session);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("summarize", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(2, fake.requests.size(),
                "narration after the explicit nudge must be accepted without a third request");
        assertTrue(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td
                && td.text() != null && td.text().contains("second wording")),
                "the accepted narration must reach the user as the final answer");
    }

    @Test
    void nudgeThenToolCallContinuesTheTurn() throws Exception {
        PluginSettings.setOllamaMaxNarrationTurns(10);
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        AiSession session = newSession();
        ((OllamaSessionSettings) session.settings()).setUseNativeToolCalling(true);
        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("I will inspect it.", List.of(), "stop"),
                new ChatResult("", List.of(new ChatToolCall("c1", "GetPluginVersion", "{}")), "tool_calls"),
                NATIVE_END_TURN_ANSWER));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(session);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("inspect", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(3, fake.requests.size(),
                "a tool call after the nudge must continue to EndTurn");
        assertEquals(List.of("GetPluginVersion"), manager.invokedToolNames);
    }

    @Test
    void stalledLoopStillProducesAnAnswer() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        List<ChatResult> scripted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            scripted.add(new ChatResult("",
                    List.of(new ChatToolCall("c" + i, "GetPluginVersion", "{\"d\":\"v" + i + "\"}")),
                    "tool_calls"));
        }
        scripted.add(new ChatResult("Hello! How can I help with your project?", List.of(), "stop"));

        // About the UNPRODUCTIVE-ROUND limit, so it raises it to 2: one productive
        // round, two that learn nothing, then the no-tools fallback. The class-wide pin of 1 would exit a
        // round early here.
        PluginSettings.setOllamaMaxUnproductiveRounds(2);

        FakeHttpClient fake = new FakeHttpClient(scripted);
        TestOllamaProcessManager manager
                = new TestOllamaProcessManager(listener, fake, "Description updated.");
        AiSession shared = new AiSession("sid6", "session6", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(),
                Instant.now(), Instant.now());
        manager.setCurrentSession(shared);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td
                && td.text() != null && td.text().contains("How can I help")),
                "the user must get a real answer, not just a status line");
        // The recovery request must offer no tools, or the model can stall again.
        ChatRequest recovery = fake.requests.get(fake.requests.size() - 1);
        assertTrue(recovery.toolSchemas().isEmpty(), "final request must offer no tools");
    }

    /**
     * Counter A ("no new information"): a reply identical to the previous one is the exact-loop signal. With
     * the limit configured to 2 (not the default 3), two repeats must end the turn with the counter-A message
     * — and the narration bound raised to 10 proves it was A, not B, that fired.
     */
    @Test
    void identicalProseRepliesTripUnproductiveRoundsAtTheirOwnLimit() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        PluginSettings.setOllamaMaxUnproductiveRounds(2);
        PluginSettings.setOllamaMaxNarrationTurns(10);

        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("same", List.of(), "stop"),
                new ChatResult("same", List.of(), "stop"),
                new ChatResult("same", List.of(), "stop"),
                new ChatResult("never reached", List.of(), "stop")));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(3, fake.requests.size(),
                "two identical repeats in a row must end the turn at the 2-limited unproductive bound");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("kept repeating itself")),
                "counter A must report the exact loop");
        assertFalse(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("without signalling")),
                "counter B must not fire — the narration bound (10 here) is far above A's 2");
    }

    /**
     * The regression the split exists for: VARIED narration must never trip counter A. A is "no new
     * information"; different prose every round resets it, regardless of how A is pinned. The turn ends only
     * when the scripted EndTurn arrives.
     */
    @Test
    void varyingProseNeverTripsTheUnproductiveRoundsBound() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        PluginSettings.setOllamaMaxUnproductiveRounds(1);
        PluginSettings.setOllamaMaxNarrationTurns(10);

        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("first line", List.of(), "stop"),
                new ChatResult("a different line", List.of(), "stop"),
                new ChatResult("yet another line", List.of(), "stop"),
                END_TURN_ANSWER));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(4, fake.requests.size(),
                "three varied prose rounds must all pass with A pinned to 1 — none is unproductive — "
                + "and only the scripted EndTurn may end the turn");
        assertFalse(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("kept repeating")),
                "varying narration must never hit the exact-loop bound");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "exactly one completion, from the EndTurn");
    }

    /**
     * Counter B ("never signals completion"): even fully varied narration must end the turn once the
     * narration bound is reached. The limit is read from settings — configured to 3 here, not the default 10
     * — and the turn must stop on the third varied reply despite counter A being raised out of the way.
     */
    @Test
    void variedProseTripsTheNarrationBoundAtTheConfiguredLimit() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        PluginSettings.setOllamaMaxUnproductiveRounds(10);
        PluginSettings.setOllamaMaxNarrationTurns(3);

        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("one", List.of(), "stop"),
                new ChatResult("two", List.of(), "stop"),
                new ChatResult("three", List.of(), "stop"),
                new ChatResult("never reached", List.of(), "stop")));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(3, fake.requests.size(),
                "three varied prose replies must trip the narration bound of 3, as configured here");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("without signalling")),
                "counter B must report that the model answered without signalling completion");
        assertFalse(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("kept repeating itself")),
                "varied prose is never counter A");
    }

    /**
     * A productive tool round is NEW information: it must reset both counters. The loop steps one round short
     * of the unproductive bound, then a REAL tool call runs (fresh arguments, so madeProgress is true) and,
     * after it, an identical prose reply must need two fresh repeats before A trips again — the pre-tool
     * counts must not carry over.
     */
    @Test
    void aProductiveToolRoundResetsBothCounters() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        PluginSettings.setOllamaMaxUnproductiveRounds(2);
        PluginSettings.setOllamaMaxNarrationTurns(4);

        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("x", List.of(), "stop"),
                new ChatResult("x", List.of(), "stop"),
                new ChatResult("{\"name\":\"GetPluginVersion\",\"arguments\":{\"d\":\"fresh\"}}",
                        List.of(), "stop"),
                new ChatResult("x", List.of(), "stop"),
                new ChatResult("x", List.of(), "stop"),
                new ChatResult("x", List.of(), "stop")));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake, "Description updated.");
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(6, fake.requests.size(),
                "two identical prose, a productive tool round, then two more identical prose must rest a "
                + "freshly-reset unproductive bound — the turn reaches all six requests");
        assertFalse(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("without signalling")),
                "counter B must never fire: the productive round reset it, so it stays below 4");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("kept repeating itself")),
                "the turn ends only when the post-reset exact loop finally trips counter A");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "exactly one completion");
    }

    /**
     * Mail interrupts used to be a pure no-op for Ollama: the blocking HTTP request had no channel to inject
     * into, so a peer marking a message important could not get it read until the turn ended on its own.
     * interrupt(Mail) now arms a flag whose next loop iteration turns into a USER message. This test pins the
     * visible side: a mail interrupt fired while request 0 is in flight must land in request 1's messages —
     * and in no earlier request.
     */
    @Test
    void mailArrivingMidTurnAppearsInTheNextRequestsMessages() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        PluginSettings.setOllamaMaxUnproductiveRounds(10);
        PluginSettings.setOllamaMaxNarrationTurns(10);

        GatedHttpClient client = new GatedHttpClient(
                new Gate(new ChatResult("first prose", List.of(), "stop"), false),
                new Gate(END_TURN_ANSWER, true));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("hi", home, List.of());
        client.gates[0].awaitInCall();
        assertEquals(1, client.requests.size(), "one request in flight so far");
        assertFalse(containsMailNotice(client.requests.get(0).messages()),
                "the in-flight request must not carry a mail notice");

        manager.interrupt(InterruptTypeEnum.Mail);
        client.gates[0].open();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(2, client.requests.size(), "the mail must not end or abort the turn");
        assertTrue(containsMailNotice(client.requests.get(1).messages()),
                "the mail notice must appear in the request that follows the interrupt");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "exactly one completion");
    }

    /**
     * Delivery must never cost the turn: the notice is queued, the in-flight tool call still executes, and
     * the turn carries on to its normal completion. This pins that Mail is not a cancel-in-disguise — nothing
     * is STOPPED or FAILED and there is exactly one TurnCompleteEvent.
     */
    @Test
    void mailArrivingMidTurnDoesNotAbortOrEndTheTurn() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        GatedHttpClient client = new GatedHttpClient(
                new Gate(new ChatResult("", List.of(new ChatToolCall("c0", "GetPluginVersion", "{}")), "tool_calls"), false),
                new Gate(END_TURN_ANSWER, true));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("hi", home, List.of());
        client.gates[0].awaitInCall();

        manager.interrupt(InterruptTypeEnum.Mail);
        client.gates[0].open();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(2, client.requests.size(),
                "the tool round must complete and the turn must run its next iteration carrying the notice");
        assertTrue(containsMailNotice(client.requests.get(1).messages()),
                "the mail notice must ride the request after the interrupt");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "exactly one completion — the turn was not aborted or ended early");
        assertTrue(events.stream().noneMatch(e -> e instanceof StatusEvent se
                && (se.type() == StatusEventTypeEnum.STOPPED
                || se.type() == StatusEventTypeEnum.FAILED)),
                "no abort, no failure: Mail must not disturb the turn");
    }

    /**
     * The flag must be the delivery mechanism, not a drop: if the turn ends before any loop iteration can
     * consume the notice, it must survive into the NEXT turn. Losing it would regress to the old behaviour
     * where an important message could not be read until an unscheduled end-of-turn flush.
     */
    @Test
    void mailQueuedButTheTurnEndsFirstIsStillDeliveredNextTurn() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;

        GatedHttpClient client = new GatedHttpClient(new Gate(END_TURN_ANSWER, false));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        client.gates[0].awaitInCall();
        manager.interrupt(InterruptTypeEnum.Mail);
        client.gates[0].open();
        awaitIdle(manager);

        assertEquals(1, client.requests.size(), "the EndTurn request must end the turn immediately");
        assertFalse(containsMailNotice(client.requests.get(0).messages()),
                "no next iteration ever ran, so the notice must not have been injected this turn");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "turn one completed normally");

        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);

        assertEquals(2, client.requests.size());
        assertTrue(containsMailNotice(client.requests.get(1).messages()),
                "the notice queued during turn one must be delivered at the start of turn two");
        assertEquals(2, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "both turns complete");
    }

    /**
     * An epoch guard separates the mail notice from the thread that armed it: Cancel ends the turn's epoch,
     * so a stale in-flight thread must not be able to consume a notice that was queued for the NEXT turn.
     * Cancel-on-the-heels-of-Mail is exactly that race — the stale thread's loop must terminate at the epoch
     * guard before it can reach the injection point, and the notice must be delivered by the fresh turn.
     */
    @Test
    void staleThreadDoesNotConsumeAQueuedMailNotice() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        PluginSettings.setOllamaMaxUnproductiveRounds(2);

        GatedHttpClient client = new GatedHttpClient(
                new Gate(new ChatResult("", List.of(new ChatToolCall("c0", "GetPluginVersion", "{}")), "tool_calls"), false),
                new Gate(END_TURN_ANSWER, true));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        client.gates[0].awaitInCall();

        manager.interrupt(InterruptTypeEnum.Mail);
        manager.interrupt(InterruptTypeEnum.Cancel);
        client.gates[0].open();
        awaitIdle(manager);

        assertEquals(1, client.requests.size(), "the cancelled turn must not loop past its one request");
        assertFalse(containsMailNotice(client.requests.get(0).messages()),
                "the stale turn must not have injected the notice");
        assertEquals(0, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "the cancelled turn must not complete");

        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);

        assertEquals(2, client.requests.size());
        assertTrue(containsMailNotice(client.requests.get(1).messages()),
                "the notice armed before the Cancel must be delivered by the NEXT turn, not consumed by the stale thread");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "exactly one completion — the one after the cancel");
    }

    /**
     * A notice that was CONSUMED (injected at the loop top) but whose turn then ROLLED BACK must not be lost.
     * The injected message lives only in the broker's open turn group: an exit that did not commit (here
     * EndTurn-under-cancel) triggers the finally's rollbackTurn(), which discards the notice while the flag
     * is already false — the nudge would vanish. Consumption is remembered and the finally re-arms the flag
     * for the next turn when the consuming turn did NOT commit. Without the re-arm, turn two's first request
     * must NOT contain the notice and this test goes red.
     */
    @Test
    void mailNoticeConsumedByARolledBackTurnIsStillDeliveredNextTurn() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;

        GatedHttpClient client = new GatedHttpClient(
                new Gate(new ChatResult("", List.of(new ChatToolCall("c0", "GetPluginVersion", "{}")), "tool_calls"), false),
                new Gate(END_TURN_ANSWER, false),
                new Gate(END_TURN_ANSWER, true));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        client.gates[0].awaitInCall();
        manager.interrupt(InterruptTypeEnum.Mail);
        client.gates[0].open();
        // The tool call from request 0 executes productively, the loop continues, and its next top injects
        // the notice; request 1 is then blocked on gate 1 until the test opens it.
        client.gates[1].awaitInCall();
        manager.interrupt(InterruptTypeEnum.Cancel);
        client.gates[1].open();
        awaitIdle(manager);

        assertEquals(2, client.requests.size(), "the cancelled turn must stop at its second request");
        assertFalse(containsMailNotice(client.requests.get(0).messages()),
                "the notice must not have ridden the request sent before the interrupt");
        assertTrue(containsMailNotice(client.requests.get(1).messages()),
                "the notice must have been injected and consumed by the rolled-back turn");
        assertEquals(0, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "a cancelled turn must not complete");

        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);

        assertEquals(3, client.requests.size());
        assertTrue(containsMailNotice(client.requests.get(2).messages()),
                "a notice consumed and then rolled back must be re-armed and delivered at the start of the next turn");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "exactly one completion — the one after the cancel");
    }

    /**
     * The notice is NEW information, like a productive tool round: injecting it must reset both the narration
     * counter and the unproductive counter, and it must never trip either by itself. Here the narration bound
     * is pinned to 3 and a mail interrupt lands during request 0; the reset at the next iteration's top must
     * force THREE more prose replies before the bound trips — four requests in total. Without the reset the
     * third varied reply would trip it and the turn would stop at three.
     */
    @Test
    void mailNoticeResetsTheLoopCountersAndNeverTripsOneItself() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        PluginSettings.setOllamaMaxUnproductiveRounds(3);
        PluginSettings.setOllamaMaxNarrationTurns(3);

        GatedHttpClient client = new GatedHttpClient(
                new Gate(new ChatResult("first", List.of(), "stop"), false),
                new Gate(new ChatResult("second", List.of(), "stop"), true),
                new Gate(new ChatResult("third", List.of(), "stop"), true),
                new Gate(new ChatResult("fourth", List.of(), "stop"), true));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("hi", home, List.of());
        client.gates[0].awaitInCall();

        manager.interrupt(InterruptTypeEnum.Mail);
        client.gates[0].open();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(4, client.requests.size(),
                "no-reset would end the turn after the third varied reply; the notice's reset makes it need a fourth");
        assertTrue(containsMailNotice(client.requests.get(1).messages()),
                "the notice rides the first request after the interrupt");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("without signalling")),
                "the turn must end at the narration bound, never at the mail notice");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "exactly one completion");
    }

    /**
     * The production values (unproductive 3, narration 10) must leave ordinary short prose sequences alone:
     * two identical prose replies are one short of the unproductive bound, three prose are nowhere near the
     * narration bound, and only the scripted EndTurn ends the turn. {@code @BeforeEach} pinned both counters
     * to 1, so the values are re-applied by hand — which is the point: the SAME scripted sequence that would
     * trip the pins does not trip once the settings move to their production values.
     */
    @Test
    void productionValuesLeaveShortProseSequencesCompleteNormally() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };
        PluginSettings.setOllamaMaxUnproductiveRounds(3);
        PluginSettings.setOllamaMaxNarrationTurns(10);

        FakeHttpClient fake = new FakeHttpClient(List.of(
                new ChatResult("one", List.of(), "stop"),
                new ChatResult("one", List.of(), "stop"),
                new ChatResult("two", List.of(), "stop"),
                END_TURN_ANSWER));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(4, fake.requests.size(),
                "two identical (A=2, one below the unproductive limit of 3) followed by varied prose and the "
                + "EndTurn must not trip either counter at the production values");
        assertFalse(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && (se.text().contains("kept repeating")
                || se.text().contains("without signalling"))),
                "neither counter may fire on this short sequence");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "exactly one completion, from the EndTurn");
    }

    /**
     * "{}" is what the model produced after giving up; it must not be shown as the reply.
     */
    @Test
    void emptyJsonObjectIsNotPresentedAsTheAnswer() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        FakeHttpClient fake = new FakeHttpClient(List.of(new ChatResult("{}", List.of(), "stop")));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        AiSession shared = new AiSession("sid5", "session5", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(),
                Instant.now(), Instant.now());
        manager.setCurrentSession(shared);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td
                && td.text() != null && td.text().contains("{}")),
                "a bare {} must never reach the user as the assistant's reply");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("empty response")));
    }

    @Test
    void emptyJsonDetectionLeavesRealAnswersAlone() {
        assertTrue(OllamaAiProcessManager.isEmptyJson("{}"));
        assertTrue(OllamaAiProcessManager.isEmptyJson("  []  "));
        assertTrue(OllamaAiProcessManager.isEmptyJson("```json\n{}\n```"));
        assertFalse(OllamaAiProcessManager.isEmptyJson("{\"answer\":42}"),
                "a user can ask for JSON and must still receive it");
        assertFalse(OllamaAiProcessManager.isEmptyJson("Hello!"));
        assertFalse(OllamaAiProcessManager.isEmptyJson(""));
    }

    @Test
    void cancelDoesNotEmitCapMessageOrTurnComplete() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();

        OllamaAiProcessManager[] ref = {null};

        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof StatusEvent se && se.type() == StatusEventTypeEnum.STOPPED) {
                stopped.countDown();
            }
        };

        HttpAiClient cancelClient = (ChatRequest request, Consumer<String> onTextDelta) -> {
            if (ref[0] != null) {
                ref[0].interrupt(InterruptTypeEnum.Cancel);
            }
            return new ChatResult("",
                    List.of(new ChatToolCall("c0", "GetPluginVersion", "{}")),
                    "tool_calls");
        };

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, cancelClient);
        ref[0] = manager;

        AiSession shared = new AiSession("sid2", "session2", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(),
                Instant.now(), Instant.now());
        manager.setCurrentSession(shared);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hello", new File(System.getProperty("user.home")), List.of());

        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        Thread turnThread = manager.activeTurnThread;
        if (turnThread != null) {
            turnThread.join(2000);
        }

        assertFalse(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("Stopped after 25")));
        assertFalse(events.stream().anyMatch(e -> e instanceof TurnCompleteEvent));
        assertTrue(manager.invokedToolNames.isEmpty());
    }

    /**
     * The resurrection window. Pressing Stop used to clear {@code processing} immediately, so a reprompt sent
     * while the cancelled turn was still unwinding started a SECOND turn underneath the first; once that
     * second turn reset {@code cancelledByUser}, the stale first thread re-entered its loop and both threads
     * drove the same broker — double commits, or a rollback that deleted the second turn's user message. The
     * fix: interrupt() leaves {@code processing} set (only the owning thread's finally clears it) and bumps
     * {@code turnGeneration}, and runTurn's loop and streaming callback compare against the epoch the turn
     * was started with. A reprompt during the wind-down must therefore be IGNORED, the cancelled turn must
     * leave no trace, and the next turn must commit exactly once.
     */
    @Test
    void cancelThenRepromptProducesExactlyOneCommittedTurn() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        GatedHttpClient client = new GatedHttpClient(
                new Gate(new ChatResult("", List.of(new ChatToolCall("c0", "GetPluginVersion", "{}")), "tool_calls"), false),
                new Gate(END_TURN_ANSWER, true));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        // At the class-wide pin of 1, a cancelled turn's first unproductive tool round exits immediately and
        // the loop's epoch guard is never load-bearing. Raising the unproductive bound to 2 puts a cancelled,
        // in-flight tool round INTO the next iteration, so the guard is exactly what stops a second request
        // from ever being made against the broker after Stop.
        PluginSettings.setOllamaMaxUnproductiveRounds(2);

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        client.gates[0].awaitInCall();
        Thread firstTurnThread = manager.activeTurnThread;
        assertTrue(firstTurnThread != null, "the first turn must own a thread");
        assertEquals(1, client.requests.size(), "exactly one request so far");

        manager.interrupt(InterruptTypeEnum.Cancel);
        manager.sendPrompt("turn two", home, List.of());
        // The reprompt was sent while the cancelled turn's request is still in flight. With the fix the gate
        // stays closed (processing is cleared only by the owning thread's finally), so it must not have
        // started anything — and the owner reference must not have moved.
        assertSame(firstTurnThread, manager.activeTurnThread,
                "a reprompt during the wind-down must not replace the owner reference");
        assertEquals(1, client.requests.size(), "no request may be issued for a turn that never started");

        client.gates[0].open();
        awaitIdle(manager);
        assertEquals(1, client.requests.size(), "the cancelled turn must not loop into a second request");
        assertNull(manager.activeTurnThread, "the owner reference must be released once the turn unwound");
        assertTrue(events.stream().noneMatch(e -> e instanceof TurnCompleteEvent),
                "no turn may complete: the first was cancelled and the reprompt was ignored");
        assertEquals(1, events.stream().filter(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.STOPPED).count(), "exactly one Stop");

        events.clear();
        manager.sendPrompt("turn three", home, List.of());
        awaitIdle(manager);

        assertEquals(2, client.requests.size());
        StringBuilder history = new StringBuilder();
        for (ChatMessage m : client.requests.get(1).messages()) {
            history.append(m.content() == null ? "" : m.content()).append('\n');
        }
        String text = history.toString();
        assertEquals(1, countOccurrences(text, "turn three"), "the fresh turn's user message must survive");
        assertFalse(text.contains("turn one"), "the cancelled turn must leave no trace in history");
        assertFalse(text.contains("turn two"), "the ignored reprompt must leave no trace in history");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "exactly one turn completes — the fresh one, nothing else");
    }

    /**
     * The stale-thread teardown guard. A cancelled thread's finally must never be able to tear a LATER live
     * turn apart — its rollback would delete the later turn's currently-open group (orphaning its user
     * message) and its clearing of {@code activeTurnThread} would leave the next Stop with nothing to
     * interrupt. The teardown is gated on being the owner, and this test pins the observable side: a reprompt
     * during the cancelled turn's wind-down is ignored (the reference never moves), and once the cancelled
     * turn has fully unwound, the follow-up turn owns the reference, commits exactly once, and its history is
     * never touched by the predecessor's rollback.
     */
    @Test
    void staleThreadFinallyNeverRollsBackALiveTurn() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        GatedHttpClient client = new GatedHttpClient(
                new Gate(new ChatResult("", List.of(new ChatToolCall("c0", "GetPluginVersion", "{}")), "tool_calls"), false),
                new Gate(END_TURN_ANSWER, true));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        // As in cancelThenReprompt…, raise the unproductive bound so the cancelled turn's round is about to
        // LOOP rather than exit early: this is the moment the owner guard (and the loop epoch guard) must
        // stop it from ever touching the broker again.
        PluginSettings.setOllamaMaxUnproductiveRounds(2);

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        client.gates[0].awaitInCall();
        Thread firstTurnThread = manager.activeTurnThread;
        assertTrue(firstTurnThread != null, "the first turn must own a thread");

        manager.interrupt(InterruptTypeEnum.Cancel);
        manager.sendPrompt("turn two", home, List.of());
        assertSame(firstTurnThread, manager.activeTurnThread,
                "a reprompt during the wind-down must not replace the owner reference");
        client.gates[0].open();
        awaitIdle(manager);
        assertNull(manager.activeTurnThread, "teardown must release the owner reference");
        assertEquals(1, client.requests.size(), "the cancelled turn must make its one request and unwind");

        manager.sendPrompt("survivor", home, List.of());
        awaitIdle(manager);
        assertNull(manager.activeTurnThread, "the survivor turn must release the reference when it completes");
        assertEquals(2, client.requests.size());

        StringBuilder history = new StringBuilder();
        for (ChatMessage m : client.requests.get(1).messages()) {
            history.append(m.content() == null ? "" : m.content()).append('\n');
        }
        String text = history.toString();
        assertTrue(text.contains("survivor"), "the survivor's user message must survive the rollback");
        assertFalse(text.contains("turn one"), "the cancelled turn must leave no trace behind the survivor");
        assertFalse(text.contains("turn two"), "the ignored reprompt must leave no trace");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "exactly one committed turn (the survivor) and no ghost from the cancelled turn");
    }

    /**
     * The lost-Stop case. After a cancelled turn and a fresh reprompt, a SECOND Stop must land on the LIVE
     * turn. The old failure was a stale thread's finally clearing {@code activeTurnThread} after the new turn
     * had taken the reference, so the next Stop found no thread and was silently lost — the fresh turn kept
     * running and committed despite the Stop.
     */
    @Test
    void stopAfterCancelThenRepromptStillInterruptsTheLiveTurn() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        GatedHttpClient client = new GatedHttpClient(
                new Gate(new ChatResult("", List.of(new ChatToolCall("c0", "GetPluginVersion", "{}")), "tool_calls"), false),
                new Gate(END_TURN_ANSWER, false));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));

        // Turn one: cancelled while its first request is in flight.
        manager.sendPrompt("turn one", home, List.of());
        client.gates[0].awaitInCall();
        manager.interrupt(InterruptTypeEnum.Cancel);
        client.gates[0].open();
        awaitIdle(manager);
        assertEquals(1, client.requests.size(), "turn one must make its one request and unwind");

        // Turn two: the fresh turn, live with its own request in flight.
        manager.sendPrompt("turn two", home, List.of());
        client.gates[1].awaitInCall();
        assertTrue(manager.activeTurnThread != null, "the fresh turn must own the thread reference");

        // The SECOND Stop — must find turn two's thread, not a stale or nulled reference.
        manager.interrupt(InterruptTypeEnum.Cancel);
        assertTrue(manager.activeTurnThread != null,
                "the second Stop must find a live thread, not a stale null");
        client.gates[1].open();
        awaitIdle(manager);

        assertEquals(2, events.stream().filter(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.STOPPED).count(), "both Stops must be acknowledged");
        assertTrue(events.stream().noneMatch(e -> e instanceof TurnCompleteEvent),
                "turn two must not complete — the second Stop must have landed on it");

        // History: both cancelled turns rolled back; the follow-up commits exactly once with only its own message.
        manager.sendPrompt("turn three", home, List.of());
        awaitIdle(manager);
        StringBuilder history = new StringBuilder();
        for (ChatMessage m : client.requests.get(2).messages()) {
            history.append(m.content() == null ? "" : m.content()).append('\n');
        }
        String text = history.toString();
        assertTrue(text.contains("turn three"), "the follow-up's user message must survive");
        assertFalse(text.contains("turn one"), "the cancelled first turn must leave no trace");
        assertFalse(text.contains("turn two"), "the cancelled second turn must leave no trace");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "only the follow-up turn completes");
    }

    /**
     * The gate-stays-closed contract of the fix: interrupt() must NOT clear {@code processing}. Only the
     * owning thread's finally may, so after a Stop the sendPrompt gate remains closed until the cancelled
     * turn has fully unwound. Clearing it in interrupt() is exactly what let a second turn start underneath
     * the first.
     */
    @Test
    void interruptKeepsProcessingSetUntilTheOwningThreadFullyUnwinds() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        GatedHttpClient client = new GatedHttpClient(
                new Gate(new ChatResult("", List.of(new ChatToolCall("c0", "GetPluginVersion", "{}")), "tool_calls"), false),
                new Gate(END_TURN_ANSWER, true));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        client.gates[0].awaitInCall();
        assertTrue(manager.isProcessing(), "the turn must be in flight");

        manager.interrupt(InterruptTypeEnum.Cancel);

        assertTrue(manager.isProcessing(),
                "after a Stop the turn is still unwinding — interrupt() must not clear processing itself");
        assertTrue(manager.activeTurnThread != null,
                "the cancelled turn must still be unwinding, not already torn down");

        client.gates[0].open();
        awaitIdle(manager);

        assertFalse(manager.isProcessing(), "the owning thread's finally must clear processing once unwound");
        assertTrue(events.stream().noneMatch(e -> e instanceof TurnCompleteEvent),
                "the cancelled turn must not complete");
        assertEquals(1, events.stream().filter(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.STOPPED).count());
        assertNull(manager.activeTurnThread, "teardown must release the owner reference");

        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "the follow-up turn completes exactly once");
    }

    @Test
    void historySurvivesAcrossTurns() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("first answer", List.of(), "stop"),
                new ChatResult("second answer", List.of(), "stop")));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        awaitIdle(manager);
        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);

        assertEquals(2, fakeClient.requests.size());
        StringBuilder second = new StringBuilder();
        for (ChatMessage m : fakeClient.requests.get(1).messages()) {
            second.append(m.content() == null ? "" : m.content()).append('\n');
        }
        String text = second.toString();

        assertTrue(text.contains("turn one"),
                "the second request must still carry the first user message");
        assertTrue(text.contains("first answer"),
                "the second request must still carry the first assistant answer");
        assertTrue(text.contains("turn two"));
    }

    @Test
    void anIoExceptionMidTurnDoesNotBrickTheSession() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FailFirstHttpClient client = new FailFirstHttpClient(List.of(
                new ChatResult("answer after recovery", List.of(), "stop")));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn that fails", home, List.of());
        awaitIdle(manager);

        manager.sendPrompt("turn that should still work", home, List.of());
        awaitIdle(manager);

        assertEquals(2, client.requests.size(),
                "the second turn must reach the client — a stuck open turn would throw "
                + "IllegalStateException in beginTurn() and never get here");
        StringBuilder second = new StringBuilder();
        for (ChatMessage m : client.requests.get(1).messages()) {
            second.append(m.content() == null ? "" : m.content()).append('\n');
        }
        assertFalse(second.toString().contains("turn that fails"),
                "a turn that failed must leave no trace in history");
    }

    @Test
    void theStopCallingToolsPromptNeverEntersHistory() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("{\"name\":\"GetPluginVersion\",\"arguments\":{\"d\":\"a\"}}",
                        List.of(), "stop"),
                new ChatResult("{\"name\":\"GetPluginVersion\",\"arguments\":{\"d\":\"b\"}}",
                        List.of(), "stop"),
                new ChatResult("{\"name\":\"GetPluginVersion\",\"arguments\":{\"d\":\"c\"}}",
                        List.of(), "stop"),
                new ChatResult("fine, here is your answer", List.of(), "stop"),
                new ChatResult("second turn answer", List.of(), "stop")));

        // About the UNPRODUCTIVE-ROUND limit reaching the no-tools fallback, so it needs 2 unproductive
        // rounds before the fallback rather than the class-wide pin of 1.
        PluginSettings.setOllamaMaxUnproductiveRounds(2);

        TestOllamaProcessManager manager
                = new TestOllamaProcessManager(listener, fakeClient, "Description updated.");
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("do something repetitive", home, List.of());
        awaitIdle(manager);
        manager.sendPrompt("next question", home, List.of());
        awaitIdle(manager);

        ChatRequest last = fakeClient.requests.get(fakeClient.requests.size() - 1);
        StringBuilder all = new StringBuilder();
        for (ChatMessage m : last.messages()) {
            String c = m.content() == null ? "" : m.content();
            assertFalse(c.contains("Stop calling tools"),
                    "the synthetic fallback prompt must never be persisted into history");
            all.append(c).append('\n');
        }
        assertTrue(all.toString().contains("fine, here is your answer"),
                "the answer the fallback produced must be kept");
    }

    /**
     * The live bug: Ollama's server rejected the whole request with HTTP 500 because the model's tool call
     * was not valid JSON ("error parsing tool call: raw='...', err=invalid character '?' ..."). Before the
     * fix the turn died with "Failed to send: HTTP 500 ...". The parse error must be fed back to the model as
     * a tool result so it can repair the call, and the turn must survive and still deliver its answer.
     */
    @Test
    void serverSideToolCallParseFailureIsRecoveredAndTheTurnSurvives() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        ToolCallParse500ThenAnswerClient client = new ToolCallParse500ThenAnswerClient();
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hello", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertEquals(2, client.requests.size(),
                "one parse-failure request, then the repaired retry — the turn must survive, not die on the 500");
        ChatRequest repaired = client.requests.get(1);
        ChatMessage recoveryAssistant = repaired.messages().stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT
                        && m.toolCalls().size() == 1
                        && "unknown_tool".equals(m.toolCalls().get(0).name()))
                .findFirst().orElse(null);
        assertTrue(recoveryAssistant != null, "the repaired request must carry the synthetic malformed-call pair");
        String callId = recoveryAssistant.toolCalls().get(0).id();
        assertTrue(callId.startsWith("call_malformed_"), "the synthetic call id must be unique per malformed call");
        ChatMessage recoveryResult = repaired.messages().stream()
                .filter(m -> m.role() == ChatRole.TOOL && callId.equals(m.toolCallId()))
                .findFirst().orElse(null);
        assertTrue(recoveryResult != null, "the parse error must be paired to the synthetic call by id");
        assertTrue(recoveryResult.content().contains("not valid JSON"),
                "the model must be told its tool call was malformed: " + recoveryResult.content());

        assertFalse(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.FAILED),
                "a recoverable parse failure must not fail the turn");
        assertTrue(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td
                && td.text() != null && td.text().contains("Understood")),
                "the turn must still deliver its final answer");
        assertInstanceOf(TurnCompleteEvent.class, events.get(events.size() - 1));
    }

    /**
     * A model that keeps emitting unparseable tool calls must not spin forever: two consecutive server-side
     * parse failures hit the bound and the turn fails exactly as an unrelated transport 500 would today.
     */
    @Test
    void persistentServerSideToolCallParseFailureHitsTheBoundAndFailsLikeATransportError() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        ToolCallParse500EveryTimeClient client = new ToolCallParse500EveryTimeClient();
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hello", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertEquals(2, client.requests.size(),
                "two parse-failure rounds reach the bound; a third request must never be made");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.FAILED
                && se.text() != null && se.text().contains("Failed to send") && se.text().contains("HTTP 500")),
                "after the bound the turn must fail as a transport error would");
        assertFalse(events.stream().anyMatch(e -> e instanceof TurnCompleteEvent),
                "a failed turn must not complete normally");
    }

    /**
     * An unrelated 500 (no tool-call parse marker) must keep failing the turn exactly as it did before —
     * nothing is recovered, no synthetic pair is fabricated.
     */
    @Test
    void anUnrelatedHttp500StillFailsTheTurnUntouched() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        Plain500Client client = new Plain500Client();
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hello", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertEquals(1, client.requests.size(), "an unrelated 500 must fail on the first request");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.FAILED
                && se.text() != null && se.text().contains("Failed to send") && se.text().contains("HTTP 500")),
                "an unrelated 500 must still fail the turn");
        assertFalse(events.stream().anyMatch(e -> e instanceof TurnCompleteEvent),
                "a failed turn must not complete normally");
        assertEquals(0, manager.invokedToolNames.size(),
                "no tool must run for a turn that never produced a parseable tool call");
    }

    @Test
    void pinnedContextReachesTheBrokerAndIsNotDuplicatedPerTurn() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("first answer", List.of(), "stop"),
                new ChatResult("second answer", List.of(), "stop")));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.updatePinnedContext("IDENTITY-BLOCK", "BASELINE-BLOCK", null);
        manager.sendPrompt("turn one", home, List.of());
        awaitIdle(manager);

        // Identical second call must not duplicate the blocks (upsertPin is idempotent).
        manager.updatePinnedContext("IDENTITY-BLOCK", "BASELINE-BLOCK", null);
        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);

        assertEquals(2, fakeClient.requests.size());
        String system = fakeClient.requests.get(1).messages().get(0).content();
        assertEquals(1, countOccurrences(system, "IDENTITY-BLOCK"),
                "IDENTITY pin must appear exactly once after two identical upserts");
        assertEquals(1, countOccurrences(system, "BASELINE-BLOCK"),
                "BASELINE pin must appear exactly once after two identical upserts");
        assertTrue(system.indexOf("IDENTITY-BLOCK") < system.indexOf("BASELINE-BLOCK"),
                "IDENTITY slot is declared before BASELINE in PinSlotEnum so it must render first");
    }

    @Test
    void reportedUsageReachesTheBrokerAndCalibratesTheEstimator() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("answer", List.of(), "stop", 5000, 20)));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hello", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertTrue(manager.broker.hasSeenReportedUsage(),
                "a backend that reports usage must be recorded as doing so");
        assertTrue(manager.broker.calibrationRatio() > 1.0d,
                "a large reported prompt_tokens must push the estimate upward");
    }

    @Test
    void sessionSettingsOverrideGlobalDefaultsInTheBroker() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("a", List.of(), "stop")));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);

        AiSession s = newSession();
        ((OllamaSessionSettings) s.settings()).setContextTokenThreshold(1234);
        ((OllamaSessionSettings) s.settings()).setContextTrimStrategy(ContextTrimStrategyEnum.DROP);
        manager.setCurrentSession(s);
        manager.start(null, "qwen2.5-coder:7b");

        assertEquals(1234, manager.brokerSettingsForTest().tokenThreshold());
        assertEquals(ContextTrimStrategyEnum.DROP, manager.brokerSettingsForTest().strategy());
    }

    @Test
    void summariserWiredInStartIsUsedWhenSummariseStrategyTrims() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("first answer", List.of(), "stop"),
                new ChatResult("a short summary of turn one", List.of(), "stop"),
                new ChatResult("second answer", List.of(), "stop")));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        AiSession s = newSession();
        ((OllamaSessionSettings) s.settings()).setContextTrimStrategy(ContextTrimStrategyEnum.SUMMARISE);
        ((OllamaSessionSettings) s.settings()).setContextTokenThreshold(1);
        manager.setCurrentSession(s);
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        awaitIdle(manager);
        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);

        assertEquals(3, fakeClient.requests.size(),
                "turn two's trim must trigger one extra summariser call in between");
        boolean sawSummary = fakeClient.requests.get(2).messages().stream()
                .anyMatch(m -> m.content() != null
                        && m.content().contains("[Summary of earlier conversation:"));
        assertTrue(sawSummary, "the summariser wired unconditionally in start() must be used "
                + "by the broker's SUMMARISE trim path");
    }

    @Test
    void compactContextRunsOffTheCallingThreadAndReportsUsageWhenDone() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch usageReported = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof OllamaTokenUsageEvent) {
                usageReported.countDown();
            }
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of());
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        manager.compactContext();

        assertTrue(usageReported.await(5, TimeUnit.SECONDS),
                "compactContext must report usage once its background thread finishes, "
                + "the same way the info bar learns a turn finished");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("Nothing to compact")),
                "an empty broker has nothing to compact");
        assertFalse(manager.isSummarising(),
                "summarising must have cleared by the time usage is reported");
    }

    @Test
    void contextIsSavedByDefaultAndRestoredIntoTheNextWireRequest() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient client = new FakeHttpClient(List.of(
                new ChatResult("first answer", List.of(), "stop"),
                new ChatResult("second answer", List.of(), "stop")));

        AiSession session = newSession();
        TestOllamaProcessManager first = new TestOllamaProcessManager(listener, client);
        first.setContextBaseDir(contextTempDir);
        first.setCurrentSession(session);
        first.start(null, "qwen2.5-coder:7b");
        first.sendPrompt("remember by default", new File(System.getProperty("user.home")), List.of());
        awaitIdle(first);
        first.stop();

        assertTrue(contextTempDir.resolve("sid").resolve("context.json").toFile().isFile(),
                "Ollama must persist context by default on close");

        TestOllamaProcessManager second = new TestOllamaProcessManager(listener, client);
        second.setContextBaseDir(contextTempDir);
        second.setCurrentSession(session);
        second.start(null, "qwen2.5-coder:7b");
        second.sendPrompt("continue", new File(System.getProperty("user.home")), List.of());
        awaitIdle(second);

        assertTrue(client.requests.get(1).messages().stream().anyMatch(m
                -> m.role() == ChatRole.USER && "remember by default".equals(m.content())),
                "restored context must reach the next Ollama wire request");
    }

    @Test
    void contextIsSavedOnStopAndRestoredOnStartWhenEnabled() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient client = new FakeHttpClient(List.of(
                new ChatResult("first answer", List.of(), "stop"),
                new ChatResult("second answer", List.of(), "stop")));

        AiSession session = newSession();
        ((OllamaSessionSettings) session.settings()).setContextPersistOnClose(true);

        TestOllamaProcessManager first = new TestOllamaProcessManager(listener, client);
        first.setContextBaseDir(contextTempDir);
        first.setCurrentSession(session);
        first.start(null, "qwen2.5-coder:7b");
        first.sendPrompt("remember this", new File(System.getProperty("user.home")), List.of());
        awaitIdle(first);
        first.stop();

        TestOllamaProcessManager second = new TestOllamaProcessManager(listener, client);
        second.setContextBaseDir(contextTempDir);
        second.setCurrentSession(session);
        second.start(null, "qwen2.5-coder:7b");

        assertTrue(second.broker.entryCount() > 0,
                "history must survive a stop/start cycle when persistence is enabled");
    }

    @Test
    void contextIsNotSavedWhenPersistenceIsOff() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient client = new FakeHttpClient(List.of(
                new ChatResult("answer", List.of(), "stop")));

        AiSession session = newSession();
        ((OllamaSessionSettings) session.settings()).setContextPersistOnClose(false);

        TestOllamaProcessManager first = new TestOllamaProcessManager(listener, client);
        first.setContextBaseDir(contextTempDir);
        first.setCurrentSession(session);
        first.start(null, "qwen2.5-coder:7b");
        first.sendPrompt("do not remember", new File(System.getProperty("user.home")), List.of());
        awaitIdle(first);
        first.stop();

        TestOllamaProcessManager second = new TestOllamaProcessManager(listener, client);
        second.setContextBaseDir(contextTempDir);
        second.setCurrentSession(session);
        second.start(null, "qwen2.5-coder:7b");

        assertEquals(0, second.broker.entryCount());
        second.sendPrompt("continue without memory", new File(System.getProperty("user.home")), List.of());
        awaitIdle(second);
        assertFalse(client.requests.get(1).messages().stream().anyMatch(m
                -> "do not remember".equals(m.content())),
                "an explicit false must suppress restored context on the wire");
    }

    @Test
    void tokenThresholdChangeBetweenTurnsIsPickedUpOnTheNextTurn() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("first answer", List.of(), "stop"),
                new ChatResult("second answer", List.of(), "stop")));

        AiSession s = newSession();
        ((OllamaSessionSettings) s.settings()).setContextTokenThreshold(9000);
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        manager.setCurrentSession(s);
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        awaitIdle(manager);
        assertEquals(9000, manager.brokerSettingsForTest().tokenThreshold());

        ((OllamaSessionSettings) s.settings()).setContextTokenThreshold(4321);
        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);

        assertEquals(4321, manager.brokerSettingsForTest().tokenThreshold(),
                "a threshold change must take effect on the very next turn, without "
                + "restarting the session");
    }

    @Test
    void loweringThresholdBetweenTurnsCausesTheBrokerToActuallyTrimOnTheNextTurn() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("first answer", List.of(), "stop"),
                new ChatResult("second answer", List.of(), "stop")));

        AiSession s = newSession();
        ((OllamaSessionSettings) s.settings()).setContextTrimStrategy(ContextTrimStrategyEnum.DROP);
        ((OllamaSessionSettings) s.settings()).setContextTokenThreshold(1_000_000);
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        manager.setCurrentSession(s);
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        awaitIdle(manager);
        int entriesAfterTurnOne = manager.broker.entryCount();
        assertEquals(2, entriesAfterTurnOne, "turn one commits with no trimming under a huge threshold");

        ((OllamaSessionSettings) s.settings()).setContextTokenThreshold(1);
        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);

        assertTrue(manager.broker.entryCount() < entriesAfterTurnOne + 2,
                "lowering the threshold between turns must cause the very next turn's trim to "
                + "actually evict, not merely record the new number with no effect");
    }

    @Test
    void reasoningEffortIsOmittedFromTheRequestWhenNotSet() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(new ChatResult("answer", List.of(), "stop")));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertNull(fakeClient.requests.get(0).reasoningEffort());
    }

    @Test
    void reasoningEffortFromSessionSettingsIsSentWithTheExactValue() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(new ChatResult("answer", List.of(), "stop")));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        AiSession s = newSession();
        ((OllamaSessionSettings) s.settings()).setReasoningEffort("high");
        manager.setCurrentSession(s);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertEquals("high", fakeClient.requests.get(0).reasoningEffort());
    }

    /**
     * The spec's Ollama section is explicit that the field name is unverified against a real server: a 4xx
     * carrying reasoning_effort must be retried exactly once without it, the user told exactly once, and the
     * field must never be sent again for the rest of the session — not even on a later turn.
     */
    @Test
    void reasoningEffortRejectedWith4xxIsRetriedOnceAndNeverSentAgainThisSession() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        RejectReasoningEffortHttpClient client
                = new RejectReasoningEffortHttpClient(new ChatResult("answer", List.of(), "stop"));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        AiSession s = newSession();
        ((OllamaSessionSettings) s.settings()).setReasoningEffort("high");
        manager.setCurrentSession(s);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertEquals(2, client.requests.size(), "must retry exactly once");
        assertEquals("high", client.requests.get(0).reasoningEffort());
        assertNull(client.requests.get(1).reasoningEffort(), "the retry must omit the field");
        long infoCount = events.stream().filter(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.INFO
                && se.text() != null && se.text().contains(OpenAiJsonKeyEnum.REASONING_EFFORT.key())).count();
        assertEquals(1, infoCount, "exactly one INFO event for the rejection");

        manager.sendPrompt("hi again", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertEquals(3, client.requests.size());
        assertNull(client.requests.get(2).reasoningEffort(),
                "once disabled for the session, a later turn must not resend it or retry again");
    }

    /**
     * Regression test for a review finding: the barren-rounds exit (two consecutive tool-call rounds that
     * make no progress) used to pass a STALE per-iteration reasoning-effort snapshot into answerWithoutTools
     * instead of re-resolving it. A 4xx rejection earlier in the SAME turn sets the sticky disabled flag
     * mid-loop, so that stale snapshot could put the field back on the wire for the fallback no-tools request
     * — a second failed request and a second INFO event in the same turn, breaking both "never resend once
     * disabled" and "exactly one INFO".
     *
     * <p>
     * Expected request sequence (0-based indices into
     * {@link RejectReasoningEffortThenBarrenLoopHttpClient#requests}, traced against
     * {@code runTurn}/{@code chatWithReasoningEffortRetry}/{@code answerWithoutTools}; Ollama is
     * {@code TOOL_CALLS_VIA_SCHEMA}, so {@code requestTools} is {@code List.of()} for every request in the
     * turn — {@code toolSchemas()} cannot be used to distinguish the tool-call rounds from the fallback
     * round, unlike a request's ordinal):
     * <ul>
     * <li>0: iteration 0's original attempt, {@code reasoningEffort="high"} — rejected with HTTP 400, firing
     * the one INFO event and setting the sticky disabled flag.</li>
     * <li>1: iteration 0's retry (same {@code chatWithReasoningEffortRetry} call),
     * {@code reasoningEffort=null} — returns a tool call, executed for the first time (madeProgress=true,
     * barrenRounds stays 0).</li>
     * <li>2: iteration 1, {@code reasoningEffort=null} (the flag already suppresses it) — the SAME tool call
     * again, deduplicated by {@code executedCalls} (no invoke, madeProgress=false, barrenRounds=1).</li>
     * <li>3: iteration 2, {@code reasoningEffort=null} — the SAME tool call again, deduplicated again
     * (barrenRounds=2), which triggers the barren-rounds exit and calls {@code answerWithoutTools}.</li>
     * <li>4: {@code answerWithoutTools}'s no-tools fallback request — must carry {@code reasoningEffort=null}
     * (the bug under test); returns a real text answer so the turn completes normally.</li>
     * </ul>
     */
    @Test
    void barrenRoundsExitAfterAMidTurnRejectionNeverResendsReasoningEffortToTheFallbackRequest() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        // The request sequence documented above depends on TWO unproductive rounds before the fallback,
        // so this test raises the unproductive bound rather than using the class-wide pin of 1.
        PluginSettings.setOllamaMaxUnproductiveRounds(2);
        RejectReasoningEffortThenBarrenLoopHttpClient client = new RejectReasoningEffortThenBarrenLoopHttpClient();
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        AiSession s = newSession();
        ((OllamaSessionSettings) s.settings()).setReasoningEffort("high");
        manager.setCurrentSession(s);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertEquals(5, client.requests.size(),
                "expected exactly 5 requests: reject, retry, two repeated barren rounds, then the fallback — "
                + "a different count means the barren-rounds exit was not reached the way this test assumes");
        assertEquals("high", client.requests.get(0).reasoningEffort());
        assertNull(client.requests.get(4).reasoningEffort(),
                "the barren-rounds fallback request must never carry a reasoning effort the session has "
                + "already been told the server rejects");

        long infoCount = events.stream().filter(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.INFO
                && se.text() != null && se.text().contains(OpenAiJsonKeyEnum.REASONING_EFFORT.key())).count();
        assertEquals(1, infoCount,
                "exactly one INFO event for the whole turn — not a second one from the fallback request");
    }

    /**
     * Confirms the interaction between pre-flight capability discovery and the 4xx retry backstop, per the
     * review round: with discovery in place, a session-pinned level on a model discovery has POSITIVELY
     * confirmed cannot think must be stripped BEFORE the request is ever built, so it never reaches
     * {@code chatWithReasoningEffortRetry} and the 4xx path never fires for it — exactly one INFO in total
     * (the pre-flight one), not two.
     */
    @Test
    void sessionPinnedLevelOnAKnownNonThinkingModelFiresExactlyOneInfoAndNeverReachesTheWire() throws Exception {
        HttpServer discoveryServer = startFakeOllamaDiscoveryServer(
                "{\"models\":[{\"name\":\"qwen2.5-coder:7b\",\"capabilities\":[\"completion\",\"tools\"]}]}");
        try {
            String baseUrl = "http://" + discoveryServer.getAddress().getHostString() + ":" + discoveryServer.getAddress().getPort();
            CountDownLatch discovered = new CountDownLatch(1);
            OllamaModelDiscovery.discoverAsync(baseUrl, models -> discovered.countDown(), hint -> {
            });
            assertTrue(discovered.await(5, TimeUnit.SECONDS), "discovery did not complete");

            List<AiProcessEvent> events = new ArrayList<>();
            AiProcessEventListener listener = events::add;
            // A CONTRACT-FOLLOWING reply: the model ends its turn with EndTurn rather than bare prose.
            // Bare prose is narration under the EndTurn contract, so it would hit the narration bound and
            // fire a second INFO ("answered without signalling it had finished") — correct behaviour, but
            // noise for a test that is about the reasoning-effort pre-flight clear.
            FakeHttpClient fakeClient = new FakeHttpClient(List.of(new ChatResult(
                    "{\"message\":\"answer\",\"tool_name\":\"EndTurn\",\"tool_arguments\":{}}",
                    List.of(), "stop")));
            TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
            AiSession s = newSession();
            ((OllamaSessionSettings) s.settings()).setBaseUrl(baseUrl);
            ((OllamaSessionSettings) s.settings()).setReasoningEffort("high");
            manager.setCurrentSession(s);
            manager.start(null, "qwen2.5-coder:7b");
            manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
            awaitIdle(manager);

            assertEquals(1, fakeClient.requests.size(),
                    "the pre-flight clear must strip the field before the request is even built — one clean "
                    + "request, not a reject-then-retry pair");
            assertNull(fakeClient.requests.get(0).reasoningEffort());

            long infoCount = events.stream().filter(e -> e instanceof StatusEvent se
                    && se.type() == StatusEventTypeEnum.INFO).count();
            assertEquals(1, infoCount, "exactly one INFO in total for the whole turn — the pre-flight clear, "
                    + "with no second one from the (never-reached) 4xx retry path");
            assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se && se.text() != null
                    && se.text().contains("Thinking") && se.text().contains("not supported")),
                    "the one INFO must be the pre-flight clear's own message");
        } finally {
            discoveryServer.stop(0);
        }
    }

    /**
     * Rejects only the FIRST request (ordinal 0, the one still carrying {@code reasoningEffort="high"}) with
     * a 400, then scripts the SAME tool call for ordinals 1-3 — driving two consecutive barren rounds — and a
     * real text answer from ordinal 4 onward (the no-tools fallback request from {@code answerWithoutTools}).
     * Branches on request ORDINAL rather than {@code toolSchemas()}/emptiness: Ollama is
     * {@code TOOL_CALLS_VIA_SCHEMA} ({@code AiTypeEnum.OLLAMA_LOCAL}), so {@code requestTools} is
     * {@code List.of()} for every request in an Ollama turn ({@code OllamaAiProcessManager.runTurn}:
     * {@code requestTools = schemaMode ? List.of() : tools}) — {@code toolSchemas()} is constant across every
     * request for this session type and cannot tell the tool-call rounds apart from the fallback round.
     */
    private static final class RejectReasoningEffortThenBarrenLoopHttpClient implements HttpAiClient {

        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) throws IOException {
            int ordinal = requests.size();
            requests.add(request);
            if (ordinal == 0) {
                throw new OpenAiHttpStatusException(400, "bad request: unknown field reasoning_effort");
            }
            if (ordinal <= 3) {
                // The retry, then two more rounds of the model repeating the SAME call — drives barrenRounds to 2.
                return new ChatResult("", List.of(new ChatToolCall("call-x", "GetPluginVersion", "{}")), "tool_calls");
            }
            // ordinal 4: the no-tools fallback request from answerWithoutTools.
            String text = "fine, here is your answer";
            onTextDelta.accept(text);
            return new ChatResult(text, List.of(), "stop");
        }
    }

    /**
     * Rejects any request carrying reasoning_effort with a 400, exactly the way a server that only
     * understands Ollama's native {@code think} parameter would behave against the OpenAI-compatible
     * endpoint.
     */
    private static final class RejectReasoningEffortHttpClient implements HttpAiClient {

        private final ChatResult okResult;
        private final List<ChatRequest> requests = new ArrayList<>();

        RejectReasoningEffortHttpClient(ChatResult okResult) {
            this.okResult = okResult;
        }

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) throws IOException {
            requests.add(request);
            if (request.reasoningEffort() != null) {
                throw new OpenAiHttpStatusException(400, "bad request: unknown field reasoning_effort");
            }
            if (okResult.assistantText() != null && !okResult.assistantText().isBlank()) {
                onTextDelta.accept(okResult.assistantText());
            }
            return okResult;
        }
    }

    private static final class FakeHttpClient implements HttpAiClient {

        private final List<ChatResult> scripted;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int index = 0;

        FakeHttpClient(List<ChatResult> scripted) {
            this.scripted = scripted;
        }

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) {
            requests.add(request);
            ChatResult result = scripted.get(index++);
            if (result.assistantText() != null && !result.assistantText().isBlank()) {
                onTextDelta.accept(result.assistantText());
            }
            return result;
        }
    }

    private static final class FailFirstHttpClient implements HttpAiClient {

        private final List<ChatResult> scripted;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int index = 0;

        FailFirstHttpClient(List<ChatResult> scripted) {
            this.scripted = scripted;
        }

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta)
                throws IOException {
            requests.add(request);
            if (index == 0) {
                index++;
                throw new IOException("stream cut");
            }
            ChatResult result = scripted.get(index++ - 1);
            if (result.assistantText() != null && !result.assistantText().isBlank()) {
                onTextDelta.accept(result.assistantText());
            }
            return result;
        }
    }

    /**
     * Serves a scripted {@link ChatResult} per request ordinal, holding that request OPEN on a {@link Gate}
     * until the test opens it. Requests past the last gate fall through immediately and reuse its result.
     * This is what lets a test press Stop while a turn genuinely has a request in flight, instead of racing
     * the fake's near-instant return. {@link #requests} exposes every ChatRequest that was actually sent.
     */
    private static final class GatedHttpClient implements HttpAiClient {

        private final Gate[] gates;
        private final List<ChatRequest> requests = new ArrayList<>();

        GatedHttpClient(Gate... gates) {
            this.gates = gates;
        }

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) throws IOException {
            int ordinal = requests.size();
            requests.add(request);
            Gate gate = gates[Math.min(ordinal, gates.length - 1)];
            gate.awaitUntilOpen();
            ChatResult result = gate.result;
            if (result.assistantText() != null && !result.assistantText().isBlank()) {
                onTextDelta.accept(result.assistantText());
            }
            return result;
        }
    }

    /**
     * One slot of a {@link GatedHttpClient}. {@link #awaitInCall()} signals (once) that the turn has reached
     * this request; {@link #open()} lets it complete. The wait deliberately swallows interrupts so a Stop
     * cannot release the gate early.
     */
    private static final class Gate {

        private final CountDownLatch inCall = new CountDownLatch(1);
        private volatile boolean open;
        private final ChatResult result;

        Gate(ChatResult result, boolean startsOpen) {
            this.result = result;
            this.open = startsOpen;
        }

        void awaitInCall() throws InterruptedException {
            inCall.await();
        }

        void open() {
            open = true;
        }

        private void awaitUntilOpen() throws IOException {
            inCall.countDown();
            while (!open) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ex) {
                    // turnThread.interrupt() can land here while the turn is winding down; a Stop must not
                    // be able to release the gate, or the test's timing assumptions break.
                }
            }
        }
    }

    /**
     * Serves one server-side tool-call parse failure (HTTP 500, Ollama's "error parsing tool call" shape),
     * then a normal schema reply.
     */
    private static final class ToolCallParse500ThenAnswerClient implements HttpAiClient {

        private final List<ChatRequest> requests = new ArrayList<>();
        private int index = 0;

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) throws IOException {
            requests.add(request);
            if (index++ == 0) {
                throw new OpenAiToolCallParseException(500,
                        "HTTP 500 from https://ollama/v1/chat/completions: "
                        + "{\"error\":{\"message\":\"error parsing tool call: raw='hello ? world', "
                        + "err=invalid character '?' after object key:value pair\",\"type\":\"api_error\"}}",
                        "invalid character '?' after object key:value pair");
            }
            return new ChatResult("{\"message\":\"Understood — let me fix that.\",\"tool_name\":\"\"}",
                    List.of(), "stop");
        }
    }

    /**
     * Serves a server-side tool-call parse failure on every request.
     */
    private static final class ToolCallParse500EveryTimeClient implements HttpAiClient {

        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) throws IOException {
            requests.add(request);
            throw new OpenAiToolCallParseException(500,
                    "HTTP 500 from https://ollama/v1/chat/completions: "
                    + "{\"error\":{\"message\":\"error parsing tool call: raw='hello ? world', "
                    + "err=invalid character '?' after object key:value pair\",\"type\":\"api_error\"}}",
                    "invalid character '?' after object key:value pair");
        }
    }

    /**
     * Serves an ordinary 500 with no tool-call parse marker.
     */
    private static final class Plain500Client implements HttpAiClient {

        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) throws IOException {
            requests.add(request);
            throw new OpenAiHttpStatusException(500,
                    "HTTP 500 from https://ollama/v1/chat/completions: {\"error\":\"upstream exploded\"}");
        }
    }

    private static final class TestOllamaProcessManager extends OllamaAiProcessManager {

        private final HttpAiClient fakeClient;
        /**
         * When set, every tool returns this — the "Description updated." case.
         */
        private final String fixedToolResult;
        final List<String> invokedToolNames = new ArrayList<>();
        private volatile Path contextBaseDir = Path.of(System.getProperty("java.io.tmpdir"),
                "ollama-test-" + UUID.randomUUID());

        TestOllamaProcessManager(AiProcessEventListener listener, HttpAiClient fakeClient) {
            this(listener, fakeClient, null);
        }

        TestOllamaProcessManager(AiProcessEventListener listener, HttpAiClient fakeClient,
                String fixedToolResult) {
            super(listener);
            this.fakeClient = fakeClient;
            this.fixedToolResult = fixedToolResult;
        }

        /**
         * Only needed by tests that enable contextPersistOnClose.
         */
        void setContextBaseDir(Path contextBaseDir) {
            this.contextBaseDir = contextBaseDir;
        }

        @Override
        ContextPersistenceManager createContextPersistenceManager() {
            return new ContextPersistenceManager(contextBaseDir);
        }

        @Override
        boolean registerMcp(OllamaMcpRegistrar reg) {
            return true;
        }

        @Override
        HttpAiClient createHttpAiClient() {
            return fakeClient;
        }

        @Override
        OllamaMcpBridge createBridge(OllamaAiSession session) {
            return new OllamaMcpBridge(session) {
                @Override
                protected String executeTool(String toolName, JsonObject argsWithAuth) {
                    invokedToolNames.add(toolName);
                    // Distinct per call by default: a repeated result now means
                    // "no progress" and ends the turn, tested separately.
                    return fixedToolResult != null ? fixedToolResult : "ok " + invokedToolNames.size();
                }
            };
        }

        @Override
        AbstractChatContextBroker createContextBroker(String sessionId, ContextBrokerSettings settings) {
            return new OllamaChatContextBroker(sessionId, settings);
        }

        @Override
        Map<McpToolEnum, McpToolInterface> buildToolHandlers(OllamaAiSession session) {
            return Map.of(McpToolEnum.GET_PLUGIN_VERSION, new McpToolInterface() {
                @Override
                public McpSectionEnum section() {
                    return McpSectionEnum.SYSTEM;
                }

                @Override
                public String instruction(Set<McpInstructionOptionEnum> options) {
                    return "GetPluginVersion -> fake";
                }

                @Override
                public boolean isMutating() {
                    return false;
                }

                @Override
                public JsonObject schema(Set<McpInstructionOptionEnum> options) {
                    JsonObject tool = new JsonObject();
                    tool.addProperty("name", McpToolEnum.GET_PLUGIN_VERSION.toolName());
                    JsonObject inputSchema = new JsonObject();
                    inputSchema.addProperty("type", "object");
                    inputSchema.add("properties", new JsonObject());
                    tool.add("inputSchema", inputSchema);
                    return tool;
                }

                @Override
                public String handle(ToolRequestArguments args,
                        kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession session) {
                    return "ok";
                }
            });
        }
    }
}

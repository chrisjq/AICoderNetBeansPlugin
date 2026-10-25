package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SummariserTest {

    private static final String VALID = """
            ## Goal
            Ship it.
            ## Constraints
            none
            ## Progress
            half way
            ## Decisions
            none
            ## Next steps
            finish
            ## Relevant files
            none
            ## Critical context
            none""";

    private static ChatMessage user(String text) {
        return new ChatMessage(ChatRole.USER, text, List.of(), null);
    }

    private static ClientResult complete(String text, String finishReason) {
        return new ClientResult(new AssembledReply(text, List.of(), finishReason, null, null, true, true),
                                ClientResult.End.COMPLETE, null, false);
    }

    private static Summariser summariser(ChatClient client) {
        return new Summariser(client, new ContextBudget(ContextBudgetSettings.defaults()),
                              OpenAiCompatibleLimits.defaults());
    }

    private static Summariser.SummaryRequest request(List<ChatMessage> messages, String previous,
                                                     RequestHandle handle) {
        return new Summariser.SummaryRequest("http://localhost:1234/v1", "summary-model", null, List.of(),
                                             new CompatibilityFlags(), ErrorMapper.KeySituation.NONE, 1000, null,
                                             previous, messages, Set.<McpToolEnum>of(), handle, null);
    }

    /**
     * A fake that records every spec and returns scripted replies, repeating the last one once the script runs out.
     */
    private static final class ScriptedClient implements ChatClient {

        final List<ChatRequestSpec> requests = new ArrayList<>();
        DebugSink seenDebug;
        private final List<ClientResult> script;
        private int index;

        ScriptedClient(ClientResult... steps) {
            this.script = List.of(steps);
        }

        @Override
        public CompletableFuture<ClientResult> send(ChatRequestSpec request, CompatibilityFlags flags, char[] apiKey,
                                                    List<ExtraHeader> headers, OpenAiCompatibleLimits limits,
                                                    RequestHandle handle, Consumer<String> onTextDelta, DebugSink debug,
                                                    ErrorMapper.KeySituation keySituation) {
            requests.add(request);
            seenDebug = debug;
            ClientResult result = script.get(Math.min(index, script.size() - 1));
            index++;
            return CompletableFuture.completedFuture(result);
        }
    }

    private static String firstPrompt(ChatRequestSpec spec) {
        return spec.messages().get(0).content();
    }

    /**
     * The last message sent: the repair nudge once a repair has happened.
     */
    private static String lastMessage(ChatRequestSpec spec) {
        List<ChatMessage> messages = spec.messages();
        return messages.get(messages.size() - 1).content();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void successOnASingleFreshChunk() {
        ScriptedClient client = new ScriptedClient(complete(VALID, "stop"));
        Summariser.Result result = summariser(client).summarise(request(List.of(user("short")), null, null));

        assertTrue(result.success());
        assertEquals(VALID, result.summary());
        assertEquals(1, client.requests.size());
        assertTrue(firstPrompt(client.requests.get(0)).startsWith("Summarise the conversation"));
        assertEquals(new JsonArray(), client.requests.get(0).tools(), "no tools are sent");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void summaryUsesTheConfiguredDebugSink() {
        ScriptedClient client = new ScriptedClient(complete(VALID, "stop"));
        List<String> sent = new ArrayList<>();
        DebugSink sink = new DebugSink() {
            @Override
            public void sent(String json) {
                sent.add(json);
            }

            @Override
            public void received(String line) {
            }

            @Override
            public void event(String text) {
            }
        };
        Summariser summariser = new Summariser(client, new ContextBudget(ContextBudgetSettings.defaults()),
                                               OpenAiCompatibleLimits.defaults(), 1,
                                               TranscriptSerialiser.TOOL_RESULT_MAX_CHARS, sink);

        assertTrue(summariser.summarise(request(List.of(user("short")), null, null)).success());
        assertEquals(sink, client.seenDebug);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void updateModeUsesThePreviousSummary() {
        ScriptedClient client = new ScriptedClient(complete(VALID, "stop"));
        Summariser.Result result = summariser(client).summarise(
                request(List.of(user("short")), "PREVIOUS SUMMARY BODY", null));

        assertTrue(result.success());
        String prompt = firstPrompt(client.requests.get(0));
        assertTrue(prompt.startsWith("Update the summary"));
        assertTrue(prompt.contains("PREVIOUS SUMMARY BODY"), "previous summary is folded in");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void fromPluginSettingsUsesTheConfiguredRepairNudges() {
        int configured = OpenAiCompatiblePluginSettings.getSummaryRepairNudges();
        try {
            OpenAiCompatiblePluginSettings.setSummaryRepairNudges(2);
            ScriptedClient client = new ScriptedClient(complete("not a summary", "stop"));
            Summariser summariser = Summariser.fromPluginSettings(client,
                                                                  new ContextBudget(ContextBudgetSettings.defaults()), OpenAiCompatibleLimits.defaults());

            Summariser.Result result = summariser.summarise(request(List.of(user("short")), null, null));

            assertFalse(result.success());
            assertEquals(3, client.requests.size(), "one attempt plus two repair nudges");
        }
        finally {
            OpenAiCompatiblePluginSettings.setSummaryRepairNudges(configured);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void longInputIsChunkedAndFoldedInUpdateMode() {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            messages.add(user("message " + i + " " + "x".repeat(200)));
        }
        ScriptedClient client = new ScriptedClient(complete(VALID, "stop"));
        Summariser.Result result = summariser(client).summarise(request(messages, null, null));

        assertTrue(result.success());
        assertTrue(client.requests.size() > 1, "long input must be chunked");
        assertTrue(firstPrompt(client.requests.get(0)).startsWith("Summarise the conversation"));
        for (int i = 1; i < client.requests.size(); i++) {
            assertTrue(firstPrompt(client.requests.get(i)).startsWith("Update the summary"),
                       "later chunks fold with update mode: " + i);
            assertTrue(firstPrompt(client.requests.get(i)).contains(VALID),
                       "the running summary is folded in: " + i);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void oneRepairThenSuccess() {
        ScriptedClient client = new ScriptedClient(
                complete("no headings here", "stop"),
                complete(VALID, "stop"));
        Summariser.Result result = summariser(client).summarise(request(List.of(user("short")), null, null));

        assertTrue(result.success());
        assertEquals(VALID, result.summary());
        assertEquals(2, client.requests.size(), "one repair needs a second send");
        assertTrue(lastMessage(client.requests.get(1)).contains("did not contain every required heading"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void lengthFinishReasonTriggersRepair() {
        ScriptedClient client = new ScriptedClient(
                complete(VALID, "length"),
                complete(VALID, "stop"));
        Summariser.Result result = summariser(client).summarise(request(List.of(user("short")), null, null));

        assertTrue(result.success());
        assertEquals(2, client.requests.size());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void twoFailuresFallBack() {
        ScriptedClient client = new ScriptedClient(
                complete("still no headings", "stop"),
                complete("nope", "stop"));
        Summariser.Result result = summariser(client).summarise(request(List.of(user("short")), null, null));

        assertFalse(result.success());
        assertEquals(2, client.requests.size(), "one repair only");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void cancelledBeforeStartSendsNothing() {
        ScriptedClient client = new ScriptedClient(complete(VALID, "stop"));
        RequestHandle handle = new RequestHandle();
        handle.cancel();

        Summariser.Result result = summariser(client).summarise(request(List.of(user("short")), null, handle));

        assertFalse(result.success());
        assertTrue(client.requests.isEmpty(), "a cancelled handle must not send");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void returnedSummaryIsFilteredForHiddenTools() {
        ScriptedClient client = new ScriptedClient(complete(VALID, "stop"));
        Summariser.Result result = summariser(client).summarise(request(List.of(user("short")), null, null));

        assertTrue(result.success());
        assertNotNull(result.summary());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void nonCompleteEndFails() {
        ScriptedClient client = new ScriptedClient(
                new ClientResult(null, ClientResult.End.TIMEOUT, null, false, ClientResult.TimeoutKind.TOTAL));
        Summariser.Result result = summariser(client).summarise(request(List.of(user("short")), null, null));

        assertFalse(result.success());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void emptyMessagesFailWithoutSending() {
        ScriptedClient client = new ScriptedClient(complete(VALID, "stop"));
        Summariser.Result result = summariser(client).summarise(request(List.of(), null, null));

        assertFalse(result.success());
        assertTrue(client.requests.isEmpty());
    }
}

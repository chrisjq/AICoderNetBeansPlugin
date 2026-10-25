package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http.OllamaChatRequest;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http.OllamaChatResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http.OllamaHttpAiClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class OllamaContextSummariserTest {

    private static List<ChatMessage> span() {
        return List.of(
                new ChatMessage(ChatRole.USER, "how do I open a file?", List.of(), null),
                new ChatMessage(ChatRole.ASSISTANT, "use GetFileContent", List.of(), null));
    }

    @Test
    void returnsTheModelsSummary() throws Exception {
        StubClient client = new StubClient(
                new OllamaChatResult("The user asked how to open files.", List.of(), "stop"));
        OllamaContextSummariser s
                = new OllamaContextSummariser(client, "http://localhost:11434", null, "m");

        assertEquals("The user asked how to open files.", s.summarise(span()));
    }

    @Test
    void sendsNoToolsSoTheModelCannotDoAnythingButSummarise() throws Exception {
        StubClient client = new StubClient(new OllamaChatResult("summary", List.of(), "stop"));
        OllamaContextSummariser s
                = new OllamaContextSummariser(client, "http://localhost:11434", null, "m");

        s.summarise(span());

        assertTrue(client.requests.get(0).toolSchemas().isEmpty(),
                   "offering tools during summarisation invites the model to call one instead");
    }

    @Test
    void aBlankSummaryIsReportedAsNullNotAsEmptyText() throws Exception {
        StubClient client = new StubClient(new OllamaChatResult("   ", List.of(), "stop"));
        OllamaContextSummariser s
                = new OllamaContextSummariser(client, "http://localhost:11434", null, "m");

        assertNull(s.summarise(span()),
                   "a blank summary must trigger the caller's fallback, not replace history "
                   + "with whitespace");
    }

    @Test
    void anEmptySpanIsNotSentToTheModelAtAll() throws Exception {
        StubClient client = new StubClient(new OllamaChatResult("summary", List.of(), "stop"));
        OllamaContextSummariser s
                = new OllamaContextSummariser(client, "http://localhost:11434", null, "m");

        assertNull(s.summarise(List.of()));
        assertTrue(client.requests.isEmpty(), "no span, no request");
    }

    private static final class StubClient implements OllamaHttpAiClient {

        private final OllamaChatResult result;
        private final IOException failure;
        final List<OllamaChatRequest> requests = new ArrayList<>();

        StubClient(OllamaChatResult result) {
            this(result, null);
        }

        StubClient(OllamaChatResult result, IOException failure) {
            this.result = result;
            this.failure = failure;
        }

        @Override
        public OllamaChatResult chat(OllamaChatRequest request, Consumer<String> onTextDelta)
                throws IOException {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}

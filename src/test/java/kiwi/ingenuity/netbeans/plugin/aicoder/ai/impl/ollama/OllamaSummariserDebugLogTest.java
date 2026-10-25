package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaSummariser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The summariser wrapper logs each summary request (SENT) and reply (RECEIVED) to the backend debug log. A fake
 * summariser stands in for the HTTP one, so the test checks only the wrapper.
 */
@ResourceLock("aicoder-debuglog")
class OllamaSummariserDebugLogTest {

    @Test
    @Timeout(10)
    void aSummaryRequestAndReplyAreLoggedInBothDirections(@TempDir Path dir) throws Exception {
        OllamaAiProcessManager manager = new OllamaAiProcessManager(event -> {
        });
        manager.setSessionConfigDir(dir);
        manager.setBackendDebugLog(true);
        OllamaSummariser fake = span -> "SUMMARY-OK";

        String summary = manager.loggingSummariser(fake)
                .summarise(List.of(new ChatMessage(ChatRole.USER, "hello there", List.of(), null)));
        manager.closeBackendDebugLog(false).get();

        assertEquals("SUMMARY-OK", summary);
        String text = Files.readString(dir.resolve("backend-debug.log"));
        assertTrue(text.contains(" SENT summary.request USER: hello there"), text);
        assertTrue(text.contains(" RECEIVED summary.response SUMMARY-OK"), text);
    }
}

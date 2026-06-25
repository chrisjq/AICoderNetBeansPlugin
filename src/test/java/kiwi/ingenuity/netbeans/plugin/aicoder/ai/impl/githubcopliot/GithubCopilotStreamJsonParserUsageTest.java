package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.GithubCopilotStreamJsonParser;
import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.events.GithubCopilotTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;

class GithubCopilotStreamJsonParserUsageTest {

    private static GithubCopilotTokenUsageEvent firstUsage(List<AiProcessEvent> events) {
        for (AiProcessEvent e : events) {
            if (e instanceof GithubCopilotTokenUsageEvent te) {
                return te;
            }
        }
        return null;
    }

    @Test
    void sessionShutdown_emitsCurrentTokensWithUnknownMax() {
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotStreamJsonParser parser = new GithubCopilotStreamJsonParser(events::add);

        parser.parseLine("{\"type\":\"session.shutdown\",\"data\":"
                + "{\"currentTokens\":17555,\"currentModel\":\"claude-sonnet-4.5\"}}");

        GithubCopilotTokenUsageEvent te = firstUsage(events);
        assertNotNull(te, "session.shutdown should emit a token usage event");
        assertEquals(17555, te.currentTokens());
        // No context-window field exists in Copilot output → report 0 (unknown)
        // so the info bar keeps its model-based default instead of a bogus value.
        assertEquals(0, te.maxTokens());
        // The actual model is carried so the info bar can size the window
        // (per the doc, the model determines the total context budget).
        assertEquals("claude-sonnet-4.5", te.model());
    }

    @Test
    void result_withTokensButNoMax_reportsZeroMaxNotFallback() {
        List<AiProcessEvent> events = new ArrayList<>();
        GithubCopilotStreamJsonParser parser = new GithubCopilotStreamJsonParser(events::add);

        parser.parseLine("{\"type\":\"result\",\"usage\":"
                + "{\"inputTokens\":5000,\"outputTokens\":200}}");

        GithubCopilotTokenUsageEvent te = firstUsage(events);
        assertNotNull(te, "result with token usage should emit a token usage event");
        assertEquals(0, te.maxTokens(), "must not substitute a bogus 8192 max");
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.GithubCopilotStreamJsonParser;
import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class GithubCopilotStreamJsonParserErrorTest {

    @Test
    void sessionError_extractsNestedHumanMessage() {
        AtomicReference<String> err = new AtomicReference<>();
        GithubCopilotStreamJsonParser parser = new GithubCopilotStreamJsonParser((AiProcessEvent e) -> {
        });
        parser.setOnError(err::set);

        parser.parseLine("{\"type\":\"session.error\",\"data\":{\"message\":"
                + "\"402 {\\\"error\\\":{\\\"message\\\":\\\"You have exceeded your monthly quota\\\","
                + "\\\"code\\\":\\\"quota_exceeded\\\"}}\",\"statusCode\":402}}");

        assertEquals("You have exceeded your monthly quota", err.get());
    }

    @Test
    void sessionError_extractsMessageWithTrailingRequestId() {
        // Real Copilot format: JSON body followed by " (Request ID: ...)".
        AtomicReference<String> err = new AtomicReference<>();
        GithubCopilotStreamJsonParser parser = new GithubCopilotStreamJsonParser((AiProcessEvent e) -> {
        });
        parser.setOnError(err::set);

        parser.parseLine("{\"type\":\"session.error\",\"data\":{\"message\":"
                + "\"402 {\\\"error\\\":{\\\"message\\\":\\\"You have exceeded your monthly quota\\\","
                + "\\\"code\\\":\\\"quota_exceeded\\\"}} (Request ID: E798:3FDEC1)\",\"statusCode\":402}}");

        assertEquals("You have exceeded your monthly quota", err.get());
    }

    @Test
    void sessionError_plainMessagePassedThrough() {
        AtomicReference<String> err = new AtomicReference<>();
        GithubCopilotStreamJsonParser parser = new GithubCopilotStreamJsonParser((AiProcessEvent e) -> {
        });
        parser.setOnError(err::set);

        parser.parseLine("{\"type\":\"session.error\",\"data\":{\"message\":\"Something broke\"}}");

        assertEquals("Something broke", err.get());
    }

    @Test
    void modelCallFailure_reportsErrorMessage() {
        AtomicReference<String> err = new AtomicReference<>();
        GithubCopilotStreamJsonParser parser = new GithubCopilotStreamJsonParser((AiProcessEvent e) -> {
        });
        parser.setOnError(err::set);

        parser.parseLine("{\"type\":\"model.call_failure\",\"data\":"
                + "{\"errorMessage\":\"upstream timeout\",\"model\":\"gpt-4o\"}}");

        assertEquals("upstream timeout", err.get());
    }
}

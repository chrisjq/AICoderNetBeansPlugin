package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SessionMetadata;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class GithubCopilotProcessManagerTest {

    @Test
    void buildCreateConfigPinsStableSessionId() {
        SessionConfig config = GithubCopilotProcessManager.buildCreateConfig(
                "plugin-session-123", "gpt-5.4", Map.of(), new GithubCopilotPermissionHandler(event -> {
        }, "plugin-session-123"));

        assertEquals("plugin-session-123", config.getSessionId());
        assertEquals("gpt-5.4", config.getModel());
        assertTrue(config.getExcludedTools().contains("edit"));
        assertTrue(config.getExcludedTools().contains("create"));
    }

    @Test
    void sessionListContainsMatchesOnlyKnownSessionIds() {
        SessionMetadata kept = new SessionMetadata();
        kept.setSessionId("keep-me");
        SessionMetadata other = new SessionMetadata();
        other.setSessionId("other");

        assertTrue(GithubCopilotProcessManager.sessionListContains(List.of(kept, other), "keep-me"));
        assertFalse(GithubCopilotProcessManager.sessionListContains(List.of(kept, other), "missing"));
        assertFalse(GithubCopilotProcessManager.sessionListContains(List.of(kept, other), " "));
    }

    @Test
    void isSessionNotFoundFailureDetectsNestedCopilotRpcErrors() {
        Throwable failure = new CompletionException(
                new IllegalStateException("Request session.resume failed with message: Session not found: abc"));

        assertTrue(GithubCopilotProcessManager.isSessionNotFoundFailure(failure));
        assertFalse(GithubCopilotProcessManager.isSessionNotFoundFailure(
                new CompletionException(new IllegalStateException("authentication required"))));
    }
}

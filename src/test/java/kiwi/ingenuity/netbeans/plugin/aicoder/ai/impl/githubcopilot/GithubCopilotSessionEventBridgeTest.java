package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.GithubCopilotSessionEventBridge;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantTurnEndEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.SessionIdleEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * The Copilot CLI's agentic loop emits one assistant.turn_end per internal step
 * (e.g. a tool-only step with no text output), not one per user-visible
 * response. The SDK's own sendAndWait() treats only session.idle as "the
 * response is actually finished" (see CopilotSession#sendAndWait). The bridge
 * must match that: AssistantTurnEndEvent alone must not surface
 * TurnCompleteEvent, or the send button re-enables mid-response.
 */
class GithubCopilotSessionEventBridgeTest {

    @SuppressWarnings("unchecked")
    private static CopilotSession newDetachedSession() throws Exception {
        // JsonRpcClient is package-private, so it can't be named in this test's
        // source; find the (String, JsonRpcClient, String) constructor by shape.
        Constructor<CopilotSession> ctor = (Constructor<CopilotSession>) Arrays
                .stream(CopilotSession.class.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == 3 && c.getParameterTypes()[0] == String.class)
                .findFirst().orElseThrow();
        ctor.setAccessible(true);
        return ctor.newInstance("test-session", null, null);
    }

    private static void dispatch(CopilotSession session, SessionEvent event) throws Exception {
        Method m = CopilotSession.class.getDeclaredMethod("dispatchEvent", SessionEvent.class);
        m.setAccessible(true);
        m.invoke(session, event);
    }

    @Test
    void assistantTurnEndAlone_doesNotFireTurnComplete() throws Exception {
        CopilotSession session = newDetachedSession();
        List<Object> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        new GithubCopilotSessionEventBridge(listener).attach(session);

        dispatch(session, new AssistantTurnEndEvent());

        assertEquals(List.of(), events.stream().filter(TurnCompleteEvent.class::isInstance).toList());
    }

    @Test
    void sessionIdle_firesTurnComplete() throws Exception {
        CopilotSession session = newDetachedSession();
        List<Object> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        new GithubCopilotSessionEventBridge(listener).attach(session);

        dispatch(session, new SessionIdleEvent());

        assertEquals(1, events.stream().filter(TurnCompleteEvent.class::isInstance).count());
    }
}

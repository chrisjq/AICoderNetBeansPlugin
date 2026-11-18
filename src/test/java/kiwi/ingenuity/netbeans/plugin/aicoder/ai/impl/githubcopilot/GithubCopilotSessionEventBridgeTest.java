package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotCompaction;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotEventSink;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class GithubCopilotSessionEventBridgeTest {

    @Test
    @Timeout(10)
    void assistantTurnEndAlone_doesNotFireTurnComplete() {
        List<Object> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        GithubCopilotSessionEventBridge bridge = new GithubCopilotSessionEventBridge(listener);

        bridge.onTurnEnd();

        assertEquals(0, events.stream().filter(TurnCompleteEvent.class::isInstance).count());
    }

    @Test
    @Timeout(10)
    void sessionIdle_firesTurnComplete() {
        List<Object> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        GithubCopilotSessionEventBridge bridge = new GithubCopilotSessionEventBridge(listener);

        bridge.onIdle();

        assertEquals(1, events.stream().filter(TurnCompleteEvent.class::isInstance).count());
    }

    @Test
    @Timeout(10)
    void reattachingClosesThePreviousSubscriptionFirst() {
        List<String> log = new ArrayList<>();
        GithubCopilotSessionEventBridge bridge = new GithubCopilotSessionEventBridge(event -> {
        });
        RecordingSession first = new RecordingSession("first", log);
        RecordingSession second = new RecordingSession("second", log);

        bridge.attach(first);
        bridge.attach(second);

        assertEquals(List.of("first:subscribe", "first:unsubscribe", "second:subscribe"), log,
                     "a model change re-attaches; the replaced session's events must stop reaching the bridge");
        assertSame(bridge, second.sink);
        assertNull(first.sink);
    }

    @Test
    @Timeout(10)
    void detachClosesTheSubscriptionOnceAndIsSafeToRepeat() {
        List<String> log = new ArrayList<>();
        GithubCopilotSessionEventBridge bridge = new GithubCopilotSessionEventBridge(event -> {
        });
        RecordingSession session = new RecordingSession("only", log);
        bridge.attach(session);

        bridge.detach();
        bridge.detach();

        assertEquals(List.of("only:subscribe", "only:unsubscribe"), log);
        assertNull(session.sink);
    }

    @Test
    @Timeout(10)
    void detachWithoutAnAttachDoesNothing() {
        GithubCopilotSessionEventBridge bridge = new GithubCopilotSessionEventBridge(event -> {
        });

        bridge.detach();
    }

    /**
     * Records subscribe and unsubscribe calls, in order, into a shared log.
     */
    private static final class RecordingSession implements CopilotSdkSession {

        private final String name;
        private final List<String> log;
        private CopilotEventSink sink;

        RecordingSession(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public String sessionId() {
            return name;
        }

        @Override
        public CompletableFuture<String> send(String prompt) {
            return CompletableFuture.completedFuture("message");
        }

        @Override
        public CompletableFuture<String> sendImmediate(String prompt) {
            return CompletableFuture.completedFuture("message");
        }

        @Override
        public CompletableFuture<Void> abort() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<CopilotCompaction> compact(String customInstructions) {
            return CompletableFuture.completedFuture(new CopilotCompaction(Boolean.TRUE, 0L, 0L));
        }

        @Override
        public void abortManualCompaction() {
        }

        @Override
        public AutoCloseable subscribe(CopilotEventSink eventSink) {
            log.add(name + ":subscribe");
            sink = eventSink;
            return () -> {
                log.add(name + ":unsubscribe");
                sink = null;
            };
        }

        @Override
        public void close() {
        }
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.EventErrorHandler;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.AssistantReasoningEvent;
import com.github.copilot.generated.AssistantStreamingDeltaEvent;
import com.github.copilot.generated.AssistantTurnEndEvent;
import com.github.copilot.generated.ModelCallFailureEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.generated.SessionUsageInfoEvent;
import com.github.copilot.generated.ToolExecutionStartEvent;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotEventSink;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class V1EventsTest {

    @Test
    void registeredHandlersTranslateRealSdkEvents() throws Exception {
        FakeRegistrar registrar = new FakeRegistrar();
        List<String> calls = new ArrayList<>();
        List<String> types = new ArrayList<>();
        CopilotEventSink sink = new CopilotEventSink() {
            @Override
            public void onAnyEvent(String type) {
                types.add(type);
            }

            @Override
            public void onToolStart(String name, String server, Object args) {
                assertEquals(Map.of("path", "file"), args);
                calls.add("tool:" + name + ":" + server);
            }

            @Override
            public void onDelta(String id, String text) {
                calls.add("delta:" + id + ":" + text);
            }

            @Override
            public void onMessage(String id, String text) {
                calls.add("message:" + id + ":" + text);
            }

            @Override
            public void onReasoning(String content) {
                calls.add("reason:" + content);
            }

            @Override
            public void onStreamingDelta(Object data) {
                calls.add("stream");
            }

            @Override
            public void onTurnEnd() {
                calls.add("turn");
            }

            @Override
            public void onIdle() {
                calls.add("idle");
            }

            @Override
            public void onUsage(Long current, Long limit) {
                calls.add("usage:" + current + ":" + limit);
            }

            @Override
            public void onSessionError(String message) {
                calls.add("error:" + message);
            }

            @Override
            public void onModelCallFailure(String message, String kind, String code) {
                calls.add("failure:" + message + ":" + kind + ":" + code);
            }
        };
        AutoCloseable subscription = V1Events.subscribe(registrar, sink);
        assertEquals(10, registrar.handlers.size());
        registrar.fire(event(ToolExecutionStartEvent.class, """
            {"toolName":"read","mcpServerName":"ide","arguments":{"path":"file"}}
            """));
        registrar.fire(event(AssistantMessageDeltaEvent.class, """
            {"messageId":"m1","deltaContent":"hello"}
            """));
        registrar.fire(event(AssistantMessageEvent.class, """
            {"messageId":"m1","content":"complete"}
            """));
        registrar.fire(event(AssistantReasoningEvent.class, """
            {"content":"thinking"}
            """));
        registrar.fire(new AssistantStreamingDeltaEvent());
        registrar.fire(new AssistantTurnEndEvent());
        registrar.fire(new SessionIdleEvent());
        registrar.fire(event(SessionUsageInfoEvent.class, """
            {"currentTokens":12,"tokenLimit":100}
            """));
        registrar.fire(event(SessionErrorEvent.class, """
            {"message":"bad request"}
            """));
        registrar.fire(event(ModelCallFailureEvent.class, """
            {"errorMessage":"failed","errorCode":"E1"}
            """));
        assertEquals(List.of("tool:read:ide", "delta:m1:hello", "message:m1:complete",
                             "reason:thinking", "stream", "turn", "idle", "usage:12:100", "error:bad request",
                             "failure:failed:null:E1"), calls);
        assertEquals(10, types.size());
        assertEquals(new AssistantMessageDeltaEvent().getType(), types.get(1));
        subscription.close();
        subscription.close();
        assertEquals(11, registrar.closed);
        registrar.fire(new SessionIdleEvent());
        assertEquals(10, calls.size());
    }

    @Test
    void forwardsHandlerErrorsAndClosesAllRegistrationsEvenIfOneFails() throws Exception {
        FakeRegistrar registrar = new FakeRegistrar();
        List<Throwable> errors = new ArrayList<>();
        List<String> types = new ArrayList<>();
        AutoCloseable subscription = V1Events.subscribe(registrar, new CopilotEventSink() {
                                                    @Override
                                                    public void onHandlerError(String type, Throwable error) {
                                                        types.add(type);
                                                        errors.add(error);
                                                    }
                                                });
        IllegalStateException failure = new IllegalStateException("handler");
        SessionIdleEvent event = new SessionIdleEvent();
        registrar.errorHandler.handleError(event, failure);
        assertEquals(List.of(event.getType()), types);
        assertSame(failure, errors.get(0));
        registrar.failFirstClose = true;
        assertThrows(IOException.class, subscription::close);
        assertEquals(11, registrar.closed);
        registrar.errorHandler.handleError(event, failure);
        assertEquals(1, errors.size());
    }

    private static <T extends SessionEvent> T event(Class<T> type, String data) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String eventType = type.getDeclaredConstructor().newInstance().getType();
        return mapper.readValue("{\"type\":" + mapper.writeValueAsString(eventType) + ",\"data\":" + data + "}", type);
    }

    private static final class FakeRegistrar implements V1Events.Registrar {

        final Map<Class<?>, Consumer<SessionEvent>> handlers = new LinkedHashMap<>();
        Consumer<SessionEvent> any;
        EventErrorHandler errorHandler;
        int closed;
        boolean failFirstClose;

        @Override
        public <T extends SessionEvent> Closeable on(Class<T> type, Consumer<T> handler) {
            handlers.put(type, event -> handler.accept(type.cast(event)));
            return () -> {
                handlers.remove(type);
                closeOne();
            };
        }

        @Override
        public Closeable on(Consumer<SessionEvent> handler) {
            any = handler;
            return () -> {
                any = null;
                closeOne();
            };
        }

        @Override
        public void onError(EventErrorHandler handler) {
            errorHandler = handler;
        }

        void closeOne() throws IOException {
            closed++;
            if (failFirstClose && closed == 1) {
                throw new IOException("close");
            }
        }

        void fire(SessionEvent event) {
            if (any != null) {
                any.accept(event);
            }
            Consumer<SessionEvent> handler = handlers.get(event.getClass());
            if (handler != null) {
                handler.accept(event);
            }
        }
    }
}

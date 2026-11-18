package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v2;

import com.microsoft.copilot.CopilotSession;
import com.microsoft.copilot.EventErrorHandler;
import com.microsoft.copilot.generated.AssistantMessageDeltaEvent;
import com.microsoft.copilot.generated.AssistantMessageEvent;
import com.microsoft.copilot.generated.AssistantReasoningEvent;
import com.microsoft.copilot.generated.AssistantStreamingDeltaEvent;
import com.microsoft.copilot.generated.AssistantTurnEndEvent;
import com.microsoft.copilot.generated.ModelCallFailureEvent;
import com.microsoft.copilot.generated.SessionErrorEvent;
import com.microsoft.copilot.generated.SessionEvent;
import com.microsoft.copilot.generated.SessionIdleEvent;
import com.microsoft.copilot.generated.SessionUsageInfoEvent;
import com.microsoft.copilot.generated.ToolExecutionStartEvent;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotEventSink;

/**
 * SDK-specific registrations; transcript policy stays in the neutral bridge.
 */
final class V2Events {

    private V2Events() {
    }

    interface Registrar {

        <T extends SessionEvent> Closeable on(Class<T> type, Consumer<T> handler);

        Closeable on(Consumer<SessionEvent> handler);

        void onError(EventErrorHandler handler);
    }

    static AutoCloseable subscribe(CopilotSession session, CopilotEventSink sink) {
        return subscribe(new Registrar() {
            @Override
            public <T extends SessionEvent> Closeable on(Class<T> type, Consumer<T> handler) {
                return session.on(type, handler);
            }

            @Override
            public Closeable on(Consumer<SessionEvent> handler) {
                return session.on(handler);
            }

            @Override
            public void onError(EventErrorHandler handler) {
                session.setEventErrorHandler(handler);
            }
        }, sink);
    }

    static AutoCloseable subscribe(Registrar registrar, CopilotEventSink sink) {
        AtomicBoolean closed = new AtomicBoolean();
        List<Closeable> registrations = new ArrayList<>();
        registrar.onError((event, error) -> {
            if (!closed.get()) {
                sink.onHandlerError(event == null ? null : event.getType(), error);
            }
        });
        registrations.add(registrar.on(event -> sink.onAnyEvent(event == null ? null : event.getType())));
        registrations.add(registrar.on(ToolExecutionStartEvent.class, event -> {
                                   var data = event.getData();
                                   if (data != null) {
                                       sink.onToolStart(data.toolName(), data.mcpServerName(), data.arguments());
                                   }
                               }));
        registrations.add(registrar.on(AssistantMessageDeltaEvent.class, event -> {
                                   var data = event.getData();
                                   if (data != null) {
                                       sink.onDelta(data.messageId(), data.deltaContent());
                                   }
                               }));
        registrations.add(registrar.on(AssistantMessageEvent.class, event -> {
                                   var data = event.getData();
                                   if (data != null) {
                                       sink.onMessage(data.messageId(), data.content());
                                   }
                               }));
        registrations.add(registrar.on(AssistantReasoningEvent.class,
                                       event -> sink.onReasoning(event.getData() == null ? null : event.getData().content())));
        registrations.add(registrar.on(AssistantStreamingDeltaEvent.class,
                                       event -> sink.onStreamingDelta(event.getData())));
        registrations.add(registrar.on(AssistantTurnEndEvent.class, event -> sink.onTurnEnd()));
        registrations.add(registrar.on(SessionIdleEvent.class, event -> sink.onIdle()));
        registrations.add(registrar.on(SessionUsageInfoEvent.class, event -> {
                                   var data = event.getData();
                                   if (data != null) {
                                       sink.onUsage(data.currentTokens(), data.tokenLimit());
                                   }
                               }));
        registrations.add(registrar.on(SessionErrorEvent.class, event -> {
                                   var data = event.getData();
                                   if (data != null) {
                                       sink.onSessionError(data.message());
                                   }
                               }));
        registrations.add(registrar.on(ModelCallFailureEvent.class, event -> {
                                   var data = event.getData();
                                   if (data != null) {
                                       sink.onModelCallFailure(data.errorMessage(),
                                                               data.failureKind() == null ? null : data.failureKind().toString(), data.errorCode());
                                   }
                               }));
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Exception failure = null;
            for (Closeable registration : registrations) {
                try {
                    registration.close();
                }
                catch (Exception ex) {
                    if (failure == null) {
                        failure = ex;
                    }
                    else {
                        failure.addSuppressed(ex);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        };
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotEventSink;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;

/**
 * Translates protocol-neutral Copilot events into plugin process events.
 */
public final class GithubCopilotSessionEventBridge implements CopilotEventSink {

    private static final Logger LOG = Logger.getLogger(GithubCopilotSessionEventBridge.class.getName());

    private final AiProcessEventListener listener;
    private Consumer<String> debugLogSink;
    private volatile BiConsumer<String, String> eventSink;
    private Consumer<String> onError;
    private Supplier<String> sessionName = () -> null;
    private String lastMessageId;
    private final Set<String> streamedMessageIds = new HashSet<>();
    private boolean errorReportedThisTurn;
    private final Object subscriptionLock = new Object();
    /**
     * The registrations on the session this bridge is attached to, or null when it is attached to none.
     */
    private AutoCloseable subscription;

    public GithubCopilotSessionEventBridge(AiProcessEventListener listener) {
        this.listener = listener;
    }

    public void setDebugLogSink(Consumer<String> sink) {
        this.debugLogSink = sink;
    }

    public void setEventSink(BiConsumer<String, String> sink) {
        this.eventSink = sink;
    }

    public void setOnError(Consumer<String> cb) {
        this.onError = cb;
    }

    public void setSessionNameSupplier(Supplier<String> supplier) {
        if (supplier != null) {
            this.sessionName = supplier;
        }
    }

    /**
     * Subscribes to {@code session}. A previous subscription (the session a model change replaced) is closed first, so
     * events from the old session never reach this bridge again.
     *
     * <p>
     * A failed subscribe propagates. A session this bridge cannot listen to never reports its turn's end, so the caller
     * must not use it: a start fails, and a re-establish closes the session and reports the turn as failed.
     */
    public void attach(CopilotSdkSession session) {
        if (session == null) {
            return;
        }
        detach();
        AutoCloseable created = session.subscribe(this);
        synchronized (subscriptionLock) {
            subscription = created;
        }
    }

    /**
     * Closes the current subscription, if any. Safe to call when not attached, and more than once.
     */
    public void detach() {
        AutoCloseable previous;
        synchronized (subscriptionLock) {
            previous = subscription;
            subscription = null;
        }
        if (previous == null) {
            return;
        }
        try {
            previous.close();
        }
        catch (Exception ex) {
            LOG.log(Level.FINE, "Ignoring error closing a Copilot event subscription", ex);
        }
    }

    private void logRaw(String kind, Object detail) {
        String value = detail == null ? "null" : String.valueOf(detail);
        Consumer<String> sink = debugLogSink;
        if (sink != null) {
            sink.accept(kind + ": " + value);
        }
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "copilot event [{0}]: {1}", new Object[]{kind, value});
        }
    }

    private void recordEvent(String kind, String payload) {
        BiConsumer<String, String> sink = eventSink;
        if (sink != null) {
            sink.accept(kind, payload);
        }
    }

    private static JsonObject toArgsObject(Object arguments) {
        JsonObject obj = new JsonObject();
        if (arguments instanceof Map<?, ?> map) {
            map.forEach((key, value) -> obj.addProperty(String.valueOf(key), String.valueOf(value)));
        }
        else if (arguments != null) {
            obj.addProperty("arguments", String.valueOf(arguments));
        }
        return obj;
    }

    @Override
    public void onAnyEvent(String type) {
        logRaw("event", type);
    }

    @Override
    public void onToolStart(String toolName, String mcpServerName, Object arguments) {
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "copilot event [tool.start]: toolName={0} mcpServerName={1}",
                    new Object[]{toolName, mcpServerName});
        }
        if (!PluginSettings.isLogToolUse() || mcpServerName != null && !mcpServerName.isBlank()
                || toolName == null || toolName.isBlank()) {
            return;
        }
        McpHookServerUtil.logToolUse(sessionName.get(), toolName, toArgsObject(arguments));
    }

    @Override
    public void onDelta(String messageId, String deltaContent) {
        logRaw("assistant.delta", deltaContent);
        if (deltaContent == null || deltaContent.isEmpty()) {
            return;
        }
        if (messageId != null && lastMessageId != null && !messageId.equals(lastMessageId)) {
            listener.onAiProcessEvent(new TextDeltaEvent("\n\n", null));
        }
        if (messageId != null) {
            lastMessageId = messageId;
            streamedMessageIds.add(messageId);
        }
        listener.onAiProcessEvent(new TextDeltaEvent(deltaContent, null));
    }

    @Override
    public void onMessage(String messageId, String content) {
        logRaw("assistant.message", content);
        if (content == null || content.isBlank()
                || messageId != null && streamedMessageIds.contains(messageId)) {
            return;
        }
        listener.onAiProcessEvent(new TextDeltaEvent(content, null));
    }

    @Override
    public void onReasoning(String content) {
        logRaw("assistant.reasoning", content);
    }

    @Override
    public void onStreamingDelta(Object data) {
        logRaw("assistant.streaming_delta", data);
    }

    @Override
    public void onTurnEnd() {
        logRaw("assistant.turn_end", null);
        lastMessageId = null;
        errorReportedThisTurn = false;
    }

    @Override
    public void onIdle() {
        logRaw("session.idle", null);
        errorReportedThisTurn = false;
        recordEvent("turn-end", "");
        listener.onAiProcessEvent(new TurnCompleteEvent());
    }

    @Override
    public void onUsage(Long currentTokens, Long tokenLimit) {
        logRaw("session.usage", currentTokens + "/" + tokenLimit);
        if (currentTokens == null) {
            return;
        }
        listener.onAiProcessEvent(new GithubCopilotTokenUsageEvent(currentTokens.intValue(),
                                                                   tokenLimit == null ? 0 : tokenLimit.intValue()));
    }

    @Override
    public void onSessionError(String message) {
        logRaw("session.error", message);
        if (onError == null || errorReportedThisTurn || message == null || message.isBlank()) {
            return;
        }
        String msg = message;
        String messageKey = GithubCopilotJsonKeyEnum.MESSAGE.key();
        if (msg.trim().startsWith("{") && msg.contains("\"" + messageKey + "\"")) {
            try {
                JsonObject obj = com.google.gson.JsonParser.parseString(msg).getAsJsonObject();
                if (obj.has(messageKey) && !obj.get(messageKey).isJsonNull()) {
                    msg = obj.get(messageKey).getAsString();
                }
            }
            catch (Exception ignored) {
            }
        }
        errorReportedThisTurn = true;
        onError.accept(msg);
    }

    @Override
    public void onModelCallFailure(String errorMessage, String failureKind, String errorCode) {
        logRaw("model.call_failure", errorMessage);
        if (onError == null || errorReportedThisTurn) {
            return;
        }
        String msg = errorMessage;
        if (msg == null || msg.isBlank()) {
            msg = "model call failed"
                    + (failureKind == null ? "" : " (" + failureKind + ")")
                    + (errorCode == null ? "" : " [" + errorCode + "]");
        }
        errorReportedThisTurn = true;
        onError.accept(msg);
    }

    @Override
    public void onHandlerError(String eventType, Throwable error) {
        LOG.log(Level.WARNING, "copilot event handler threw for " + eventType, error);
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.AssistantReasoningEvent;
import com.github.copilot.generated.AssistantStreamingDeltaEvent;
import com.github.copilot.generated.AssistantTurnEndEvent;
import com.github.copilot.generated.ModelCallFailureEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.generated.SessionUsageInfoEvent;
import com.github.copilot.generated.ToolExecutionStartEvent;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;

/**
 * Registers typed SDK event listeners on a live CopilotSession and translates
 * them into the plugin's AiProcessEvent types. Replaces
 * GithubCopilotStreamJsonParser (which parsed copilot -p's JSON-line stdout) —
 * the SDK delivers typed events directly over the persistent session, so there
 * is no line parsing left to do. Registered once per session, not per turn.
 */
public final class GithubCopilotSessionEventBridge {

    private static final Logger LOG = Logger.getLogger(GithubCopilotSessionEventBridge.class.getName());

    /**
     * Raw SDK-event logging, gated on the same debug flag Claude and Ollama
     * use. The Copilot path had none, so a turn that produced no output left
     * nothing to inspect. Logs the event type and its data on every event.
     */
    /**
     * Expands a tool's arguments into one log property per key, so the line
     * reads {@code path[/x/README.md]} like the MCP tools rather than a single
     * {@code arguments[{path=...}]} blob. The SDK hands arguments back as an
     * Object that is a String-keyed Map in practice; anything else is logged
     * whole under a single key.
     */
    private static JsonObject toArgsObject(Object arguments) {
        JsonObject obj = new JsonObject();
        if (arguments instanceof java.util.Map<?, ?> map) {
            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                obj.addProperty(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        else if (arguments != null) {
            obj.addProperty("arguments", String.valueOf(arguments));
        }
        return obj;
    }

    private static void logRaw(String kind, Object detail) {
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.WARNING, "copilot event [{0}]: {1}",
                    new Object[]{kind, detail == null ? "null" : String.valueOf(detail)});
        }
    }

    private final AiProcessEventListener listener;
    private Consumer<String> onError;
    private Supplier<String> sessionName = () -> null;
    private String lastMessageId = null;
    // Message ids that produced at least one streamed delta this session. A
    // final AssistantMessageEvent for the same id would repeat text already
    // shown, so it is emitted only for messages that never streamed.
    private final Set<String> streamedMessageIds = new HashSet<>();

    private boolean errorReportedThisTurn = false;

    public GithubCopilotSessionEventBridge(AiProcessEventListener listener) {
        this.listener = listener;
    }

    /**
     * Registers a callback for a human-readable error reported mid-session
     * (e.g. quota exceeded, rate limited). The plugin surfaces this in the
     * turn's exit message so the user sees why a turn produced no output.
     */
    public void setOnError(Consumer<String> cb) {
        this.onError = cb;
    }

    /**
     * Session name used to prefix tool-use log lines, matching the other
     * backends.
     */
    public void setSessionNameSupplier(Supplier<String> supplier) {
        if (supplier != null) {
            this.sessionName = supplier;
        }
    }

    public void attach(CopilotSession session) {
        // Under the SDK's default EventErrorPolicy, an exception thrown by any
        // one of our listeners STOPS dispatch of that event to the remaining
        // listeners and is logged only through the SDK's own logger — invisible
        // here. That is exactly the silent-turn-death class of bug: a throwing
        // handler could swallow the assistant's message. Surface it in our log.
        session.setEventErrorHandler((event, error) -> LOG.log(Level.WARNING,
                "copilot event handler threw for " + (event == null ? "?" : event.getType()), error));

        // Catch-all raw logger (debug-gated). The turn that produced no output
        // did so because the content arrived on an event type we did not listen
        // to; logging every event's type by name means the next unhandled
        // carrier is visible immediately rather than after hours of inference.
        session.on(e -> logRaw("event", e == null ? null : e.getType()));

        // Tool-use logging (the "Log tool use" setting). Copilot's own tools —
        // its native file/shell/search — are run by the agent and never reach
        // our MCP server, so McpToolInvoker never logs them; only the plugin's
        // own MCP tools were appearing. Log every tool the agent starts here.
        // MCP tools carry mcpServerName and are already logged by McpToolInvoker
        // when the agent calls them over HTTP, so they are skipped to avoid
        // double lines.
        session.on(ToolExecutionStartEvent.class, e -> {
            ToolExecutionStartEvent.ToolExecutionStartEventData data = e.getData();
            logRaw("tool.start", data);
            if (data == null || !PluginSettings.isLogToolUse()) {
                return;
            }
            String mcpServer = data.mcpServerName();
            if (mcpServer != null && !mcpServer.isBlank()) {
                return; // an MCP tool — McpToolInvoker already logged it
            }
            String toolName = data.toolName();
            if (toolName == null || toolName.isBlank()) {
                return;
            }
            McpHookServerUtil.logToolUse(sessionName.get(), toolName, toArgsObject(data.arguments()));
        });

        session.on(AssistantMessageDeltaEvent.class, e -> {
            AssistantMessageDeltaEvent.AssistantMessageDeltaEventData data = e.getData();
            logRaw("assistant.delta", data == null ? null : data.deltaContent());
            if (data == null) {
                return;
            }
            String deltaContent = data.deltaContent();
            if (deltaContent == null || deltaContent.isEmpty()) {
                return;
            }
            // Copilot streams each assistant message as a run of deltas. When a
            // new message begins mid-turn the chunks otherwise concatenate
            // ("...this.Waking.."). Insert a blank line at the boundary,
            // keyed on messageId (mirrors the old parser's heuristic, now with
            // an authoritative id on every delta instead of a fallback).
            String messageId = data.messageId();
            if (messageId != null && lastMessageId != null && !messageId.equals(lastMessageId)) {
                listener.onAiProcessEvent(new TextDeltaEvent("\n\n", null));
            }
            if (messageId != null) {
                lastMessageId = messageId;
                streamedMessageIds.add(messageId);
            }
            listener.onAiProcessEvent(new TextDeltaEvent(deltaContent, null));
        });
        // A complete (non-streamed) assistant message. Reasoning models such as
        // gpt-5-mini deliver their answer this way and fire no deltas at all, so
        // without this the whole turn was dropped — the model "thought and
        // exited" with nothing shown. Emitted only when the id did not stream.
        session.on(AssistantMessageEvent.class, e -> {
            AssistantMessageEvent.AssistantMessageEventData data = e.getData();
            logRaw("assistant.message", data == null ? null : data.content());
            if (data == null) {
                return;
            }
            String content = data.content();
            String messageId = data.messageId();
            if (content == null || content.isBlank()
                    || (messageId != null && streamedMessageIds.contains(messageId))) {
                return;
            }
            listener.onAiProcessEvent(new TextDeltaEvent(content, null));
        });
        // Not yet surfaced to the user, but logged so the next unexplained empty
        // turn shows whether the model spoke through one of these instead.
        session.on(AssistantReasoningEvent.class,
                e -> logRaw("assistant.reasoning", e.getData() == null ? null : e.getData().content()));
        session.on(AssistantStreamingDeltaEvent.class,
                e -> logRaw("assistant.streaming_delta", e.getData()));
        // assistant.turn_end fires once per internal agentic-loop step (e.g. a
        // tool-only step with no text output), not once per user-visible
        // response — a multi-step response fires it several times before the
        // session actually goes idle. The SDK's own sendAndWait() treats only
        // session.idle as "the response is finished" (see
        // CopilotSession#sendAndWait), so only session.idle may surface
        // TurnCompleteEvent; mapping turn_end to it re-enables the send button
        // mid-response whenever a step produces no text.
        session.on(AssistantTurnEndEvent.class, e -> {
            logRaw("assistant.turn_end", e.getData());
            lastMessageId = null;
            errorReportedThisTurn = false;
        });
        session.on(SessionIdleEvent.class, e -> {
            logRaw("session.idle", e.getData());
            errorReportedThisTurn = false;
            listener.onAiProcessEvent(new TurnCompleteEvent());
        });
        session.on(SessionUsageInfoEvent.class, e -> {
            SessionUsageInfoEvent.SessionUsageInfoEventData data = e.getData();
            logRaw("session.usage", data);
            if (data == null || data.currentTokens() == null) {
                return;
            }
            int current = data.currentTokens().intValue();
            int limit = data.tokenLimit() != null ? data.tokenLimit().intValue() : 0;
            listener.onAiProcessEvent(new GithubCopilotTokenUsageEvent(current, limit));
        });
        session.on(SessionErrorEvent.class, e -> {
            SessionErrorEvent.SessionErrorEventData data = e.getData();
            logRaw("session.error", data);
            if (data == null || onError == null || errorReportedThisTurn) {
                return;
            }
            String msg = data.message();
            if (msg != null && !msg.isBlank()) {
                if (msg.trim().startsWith("{") && msg.contains("\"message\"")) {
                    try {
                        JsonObject obj = com.google.gson.JsonParser.parseString(msg).getAsJsonObject();
                        if (obj.has("message") && !obj.get("message").isJsonNull()) {
                            msg = obj.get("message").getAsString();
                        }
                    } catch (Exception ignored) {
                    }
                }
                errorReportedThisTurn = true;
                onError.accept(msg);
            }
        });
        // A model-level call failure (bad request, quota, transport). Previously
        // unhandled, so a failed call left the user with a turn that ended with
        // no output and no explanation. Surface it like any other error.
        session.on(ModelCallFailureEvent.class, e -> {
            ModelCallFailureEvent.ModelCallFailureEventData data = e.getData();
            logRaw("model.call_failure", data);
            if (data == null || onError == null || errorReportedThisTurn) {
                return;
            }
            String msg = data.errorMessage();
            if (msg == null || msg.isBlank()) {
                msg = "model call failed"
                        + (data.failureKind() != null ? " (" + data.failureKind() + ")" : "")
                        + (data.errorCode() != null ? " [" + data.errorCode() + "]" : "");
            }
            errorReportedThisTurn = true;
            onError.accept(msg);
        });
    }
}

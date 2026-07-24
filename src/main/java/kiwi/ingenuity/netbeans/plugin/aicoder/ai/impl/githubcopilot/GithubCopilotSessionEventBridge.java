package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantTurnEndEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.generated.SessionUsageInfoEvent;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

/**
 * Registers typed SDK event listeners on a live CopilotSession and translates
 * them into the plugin's AiProcessEvent types. Replaces
 * GithubCopilotStreamJsonParser (which parsed copilot -p's JSON-line stdout) —
 * the SDK delivers typed events directly over the persistent session, so there
 * is no line parsing left to do. Registered once per session, not per turn.
 */
public final class GithubCopilotSessionEventBridge {

    private static final Logger LOG = Logger.getLogger(GithubCopilotSessionEventBridge.class.getName());

    private final AiProcessEventListener listener;
    private Consumer<String> onError;
    private String lastMessageId = null;

    public GithubCopilotSessionEventBridge(AiProcessEventListener listener) {
        this.listener = listener;
    }

    /**
     * Raw SDK-event logging, gated on the same debug flag Claude and Ollama use.
     * The Copilot path had none, so a turn that produced no output left nothing
     * to inspect. Logs the event type and its data on every event.
     */
    private static void logRaw(String kind, Object detail) {
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.WARNING, "copilot event [{0}]: {1}",
                    new Object[]{kind, detail == null ? "null" : String.valueOf(detail)});
        }
    }

    /**
     * Registers a callback for a human-readable error reported mid-session
     * (e.g. quota exceeded, rate limited). The plugin surfaces this in the
     * turn's exit message so the user sees why a turn produced no output.
     */
    public void setOnError(Consumer<String> cb) {
        this.onError = cb;
    }

    public void attach(CopilotSession session) {
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
            }
            listener.onAiProcessEvent(new TextDeltaEvent(deltaContent, null));
        });
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
        });
        session.on(SessionIdleEvent.class, e -> {
            logRaw("session.idle", e.getData());
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
            // Logged unconditionally at WARNING (not only under the debug flag):
            // an error here is the likeliest reason a turn ends with no output,
            // and it was previously dropped whenever onError was unset or the
            // message blank, leaving no trace at all.
            LOG.log(Level.WARNING, "copilot session error: {0}", data == null ? "null" : data);
            if (data == null || onError == null) {
                return;
            }
            String msg = data.message();
            if (msg != null && !msg.isBlank()) {
                onError.accept(msg);
            }
        });
    }
}

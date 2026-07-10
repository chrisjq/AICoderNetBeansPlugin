package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot;

import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantTurnEndEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.generated.SessionUsageInfoEvent;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.events.GithubCopilotTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

/**
 * Registers typed SDK event listeners on a live CopilotSession and translates
 * them into the plugin's AiProcessEvent types. Replaces
 * GithubCopilotStreamJsonParser (which parsed copilot -p's JSON-line stdout) —
 * the SDK delivers typed events directly over the persistent session, so there
 * is no line parsing left to do. Registered once per session, not per turn.
 */
public final class GithubCopilotSessionEventBridge {

    private final AiProcessEventListener listener;
    private Consumer<String> onError;
    private String lastMessageId = null;

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

    public void attach(CopilotSession session) {
        session.on(AssistantMessageDeltaEvent.class, e -> {
            AssistantMessageDeltaEvent.AssistantMessageDeltaEventData data = e.getData();
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
        session.on(AssistantTurnEndEvent.class, e -> lastMessageId = null);
        session.on(SessionIdleEvent.class, e -> listener.onAiProcessEvent(new TurnCompleteEvent()));
        session.on(SessionUsageInfoEvent.class, e -> {
            SessionUsageInfoEvent.SessionUsageInfoEventData data = e.getData();
            if (data == null || data.currentTokens() == null) {
                return;
            }
            int current = data.currentTokens().intValue();
            int limit = data.tokenLimit() != null ? data.tokenLimit().intValue() : 0;
            listener.onAiProcessEvent(new GithubCopilotTokenUsageEvent(current, limit));
        });
        session.on(SessionErrorEvent.class, e -> {
            SessionErrorEvent.SessionErrorEventData data = e.getData();
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

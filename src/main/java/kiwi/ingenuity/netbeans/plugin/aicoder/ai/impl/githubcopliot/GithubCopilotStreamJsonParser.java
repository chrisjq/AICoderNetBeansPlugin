package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.events.GithubCopilotTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

public class GithubCopilotStreamJsonParser {

    private static final Logger LOG = Logger.getLogger(GithubCopilotStreamJsonParser.class.getName());
    private static final Gson GSON = new Gson();

    /**
     * Returns the first balanced <code>{ ... }</code> substring of {@code s},
     * or null if none. Copilot error strings embed a JSON body with trailing
     * text such as " (Request ID: ...)", which a plain substring-from-brace
     * would not parse; this isolates just the object.
     */
    private static String balancedJsonObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                }
                else if (c == '\\') {
                    esc = true;
                }
                else if (c == '"') {
                    inStr = false;
                }
            }
            else if (c == '"') {
                inStr = true;
            }
            else if (c == '{') {
                depth++;
            }
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private final AiProcessEventListener listener;
    private Consumer<String> onSessionId;
    private Consumer<String> onError;
    // Tracks the in-progress assistant message so separate thoughts streamed in
    // the same turn are kept apart instead of running together. Reset each turn.
    private String lastMessageId = null;
    private String lastDeltaContent = null;

    public GithubCopilotStreamJsonParser(AiProcessEventListener listener) {
        this.listener = listener;
    }

    public void setOnSessionId(Consumer<String> cb) {
        this.onSessionId = cb;
    }

    /**
     * Registers a callback for a human-readable error reported by the CLI (e.g.
     * a quota or upstream failure). The plugin surfaces this in the exit
     * message so the user sees why a turn produced no output.
     */
    public void setOnError(Consumer<String> cb) {
        this.onError = cb;
    }

    public void parseLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            JsonObject obj = GSON.fromJson(line, JsonObject.class);

            String type = getString(obj, GithubCopilotJsonKeyEnum.TYPE.key());

            switch (type == null ? "" : type) {
                case "assistant.message_delta" -> {
                    JsonObject data = obj.has(GithubCopilotJsonKeyEnum.DATA.key())
                            ? obj.getAsJsonObject(GithubCopilotJsonKeyEnum.DATA.key()) : null;
                    if (data != null) {
                        String deltaContent = getString(data, GithubCopilotJsonKeyEnum.DELTA_CONTENT.key());
                        if (deltaContent != null && !deltaContent.isEmpty()) {
                            // Copilot streams each assistant message as a run of
                            // deltas. When a new message begins mid-turn the
                            // chunks otherwise concatenate ("...this.Waking..").
                            // Insert a blank line at the boundary: prefer a change
                            // of messageId (authoritative); if the stream carries
                            // no messageId, fall back to a whitespace heuristic — a
                            // boundary with no surrounding whitespace is a new block.
                            String messageId = getString(data, GithubCopilotJsonKeyEnum.MESSAGE_ID.key());
                            boolean newBlock;
                            if (messageId != null && lastMessageId != null) {
                                newBlock = !messageId.equals(lastMessageId);
                            }
                            else {
                                newBlock = lastDeltaContent != null
                                        && !endsWithWhitespace(lastDeltaContent)
                                        && !startsWithWhitespace(deltaContent);
                            }
                            if (newBlock) {
                                listener.onAiProcessEvent(new TextDeltaEvent("\n\n", null));
                            }
                            if (messageId != null) {
                                lastMessageId = messageId;
                            }
                            lastDeltaContent = deltaContent;
                            listener.onAiProcessEvent(new TextDeltaEvent(deltaContent, null));
                        }
                    }
                }
                case "result" -> {
                    // End of turn — clear per-message separator state so it does
                    // not leak into the next turn.
                    lastMessageId = null;
                    lastDeltaContent = null;
                    String sessionId = getString(obj, GithubCopilotJsonKeyEnum.SESSION_ID.key());
                    if (sessionId != null && onSessionId != null) {
                        onSessionId.accept(sessionId);
                        onSessionId = null;
                    }
                    JsonObject usage = obj.has(GithubCopilotJsonKeyEnum.USAGE.key())
                            ? obj.getAsJsonObject(GithubCopilotJsonKeyEnum.USAGE.key()) : null;
                    if (usage != null) {
                        int inputTokens = getInt(usage, GithubCopilotJsonKeyEnum.INPUT_TOKENS.key());
                        if (inputTokens == 0) {
                            inputTokens = getInt(usage, GithubCopilotJsonKeyEnum.TOTAL_INPUT_TOKENS.key());
                        }
                        int outputTokens = getInt(usage, GithubCopilotJsonKeyEnum.OUTPUT_TOKENS.key());
                        if (outputTokens == 0) {
                            outputTokens = getInt(usage, GithubCopilotJsonKeyEnum.TOTAL_OUTPUT_TOKENS.key());
                        }
                        // Copilot does not report a context-window size, so pass
                        // maxTok (0 = unknown) and let the info bar keep its
                        // model-based default rather than substituting a bogus value.
                        int maxTok = getInt(usage, GithubCopilotJsonKeyEnum.MAX_TOKENS.key());
                        int currentTok = inputTokens + outputTokens;
                        if (currentTok > 0) {
                            listener.onAiProcessEvent(new GithubCopilotTokenUsageEvent(currentTok, maxTok));
                        }
                    }
                    listener.onAiProcessEvent(new TurnCompleteEvent());
                }
                case "session.shutdown" -> {
                    // The end-of-turn shutdown event carries the live context size
                    // in data.currentTokens — the authoritative number for the
                    // context bar (the result event's usage has no token counts).
                    JsonObject data = obj.has(GithubCopilotJsonKeyEnum.DATA.key())
                            ? obj.getAsJsonObject(GithubCopilotJsonKeyEnum.DATA.key()) : null;
                    if (data != null) {
                        int currentTok = getInt(data, GithubCopilotJsonKeyEnum.CURRENT_TOKENS.key());
                        if (currentTok > 0) {
                            // 0 max = unknown; the model sizes the window in the info bar.
                            String model = getString(data, GithubCopilotJsonKeyEnum.CURRENT_MODEL.key());
                            listener.onAiProcessEvent(new GithubCopilotTokenUsageEvent(currentTok, 0, model));
                        }
                    }
                }
                case "session.error" -> {
                    JsonObject data = obj.has(GithubCopilotJsonKeyEnum.DATA.key())
                            ? obj.getAsJsonObject(GithubCopilotJsonKeyEnum.DATA.key()) : null;
                    String msg = data != null
                            ? getString(data, GithubCopilotJsonKeyEnum.MESSAGE.key()) : null;
                    reportError(extractApiError(msg));
                }
                case "model.call_failure" -> {
                    JsonObject data = obj.has(GithubCopilotJsonKeyEnum.DATA.key())
                            ? obj.getAsJsonObject(GithubCopilotJsonKeyEnum.DATA.key()) : null;
                    String msg = data != null
                            ? getString(data, GithubCopilotJsonKeyEnum.ERROR_MESSAGE.key()) : null;
                    reportError(extractApiError(msg));
                }
                default ->
                    LOG.log(Level.FINE, "Unhandled GitHub Copilot event type: {0}", type);
            }
        }
        catch (RuntimeException e) {
            LOG.log(Level.FINE, "Skipping unparseable line: {0}", line);
        }
    }

    private static boolean endsWithWhitespace(String s) {
        return !s.isEmpty() && Character.isWhitespace(s.charAt(s.length() - 1));
    }

    private static boolean startsWithWhitespace(String s) {
        return !s.isEmpty() && Character.isWhitespace(s.charAt(0));
    }

    private void reportError(String msg) {
        if (onError != null && msg != null && !msg.isBlank()) {
            onError.accept(msg);
        }
    }

    /**
     * Pulls the human-readable text out of a CLI error string. Copilot wraps
     * API errors as "&lt;status&gt; {\"error\":{\"message\":\"...\"}}"; if a
     * JSON body is present its error.message is returned, otherwise the raw
     * string is passed through unchanged.
     */
    private String extractApiError(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String json = balancedJsonObject(raw);
        if (json != null) {
            try {
                JsonObject body = GSON.fromJson(json, JsonObject.class);
                if (body != null && body.has("error")
                        && body.get("error").isJsonObject()) {
                    String inner = getString(body.getAsJsonObject("error"),
                            GithubCopilotJsonKeyEnum.MESSAGE.key());
                    if (inner != null && !inner.isBlank()) {
                        return inner;
                    }
                }
            }
            catch (RuntimeException ignored) {
                // Not the shape we expected — fall through to the raw string.
            }
        }
        return raw.strip();
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsString();
        }
        catch (Exception e) {
            return null;
        }
    }

    private int getInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return obj.get(key).getAsInt();
        }
        catch (Exception e) {
            return 0;
        }
    }
}

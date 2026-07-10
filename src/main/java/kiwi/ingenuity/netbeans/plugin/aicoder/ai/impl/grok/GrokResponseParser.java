package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.JsonUtils;

/**
 * Parses the grok CLI's {@code --output-format json} turn output (a single JSON
 * object printed once the turn finishes, per
 * https://docs.x.ai/build/cli/headless-scripting).
 *
 * <p>
 * Unlike Claude's {@code stream-json} mode, grok headless mode is one-shot per
 * turn (no persistent stdin conversation and no publicly documented incremental
 * event schema), so this parser emits a single {@link TextDeltaEvent} with the
 * full assistant text followed by a {@link TurnCompleteEvent}, rather than
 * incremental deltas. Native Grok Edit/Write tool calls are intercepted
 * separately via the PreToolUse HTTP hook registered by
 * {@link GrokAiMcpRegistrar} (the diff panel is shown there, not from this
 * stream), so this parser only needs to surface the assistant's final text.
 *
 * <p>
 * This JSON output never includes usage/token data (empirically confirmed) —
 * the context usage event is instead built by {@link GrokUsageSignalsReader}
 * from the CLI's on-disk {@code signals.json}, driven by
 * {@link GrokAiProcessManager} once it knows the turn's working directory.
 */
public class GrokResponseParser {

    private static final Logger LOG = Logger.getLogger(GrokResponseParser.class.getName());
    private static final Gson GSON = new Gson();

    private final AiProcessEventListener listener;

    public GrokResponseParser(AiProcessEventListener listener) {
        this.listener = listener;
    }

    /**
     * Parses the full stdout captured for one turn. {@code rawOutput} may be a
     * single JSON object, or (defensively) newline-delimited JSON objects —
     * only the last parseable JSON object on the stream is used as the turn's
     * result.
     */
    public void parse(String rawOutput) {
        JsonObject obj = extractLastJsonObject(rawOutput);
        if (obj == null) {
            // Could not find any JSON — fall back to showing the raw text so the
            // user still sees *something* rather than a silently empty turn.
            String fallback = rawOutput == null ? "" : rawOutput.strip();
            if (!fallback.isEmpty()) {
                listener.onAiProcessEvent(new TextDeltaEvent(fallback, null));
            }
            listener.onAiProcessEvent(new TurnCompleteEvent());
            return;
        }

        String error = JsonUtils.getString(obj, GrokJsonKeyEnum.ERROR.key());
        if (error != null && !error.isBlank()) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, error));
            listener.onAiProcessEvent(new TurnCompleteEvent());
            return;
        }

        String text = firstNonBlank(
                JsonUtils.getString(obj, GrokJsonKeyEnum.RESULT.key()),
                JsonUtils.getString(obj, GrokJsonKeyEnum.RESPONSE.key()),
                JsonUtils.getString(obj, GrokJsonKeyEnum.TEXT.key()),
                JsonUtils.getString(obj, GrokJsonKeyEnum.CONTENT.key()),
                JsonUtils.getString(obj, GrokJsonKeyEnum.MESSAGE.key()));
        if (text == null) {
            text = "";
        }
        listener.onAiProcessEvent(new TextDeltaEvent(text, null));
        listener.onAiProcessEvent(new TurnCompleteEvent());
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private JsonObject extractLastJsonObject(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return null;
        }
        JsonObject last = null;
        for (String line : rawOutput.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
                continue;
            }
            try {
                JsonElement el = GSON.fromJson(trimmed, JsonElement.class);
                if (el != null && el.isJsonObject()) {
                    last = el.getAsJsonObject();
                }
            }
            catch (RuntimeException e) {
                LOG.log(Level.FINE, "Skipping unparseable grok output line: {0}", trimmed);
            }
        }
        if (last != null) {
            return last;
        }
        // Whole-output fallback: some builds may pretty-print a single JSON
        // object across multiple lines, which the line-by-line pass above misses.
        try {
            JsonElement el = GSON.fromJson(rawOutput.strip(), JsonElement.class);
            if (el != null && el.isJsonObject()) {
                return el.getAsJsonObject();
            }
        }
        catch (RuntimeException e) {
            LOG.log(Level.FINE, "grok output is not a single JSON object", e);
        }
        return null;
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.events.ClaudeSessionInfoEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.JsonUtils;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

public class ClaudeStreamJsonParser {

    private static final Logger LOG = Logger.getLogger(ClaudeStreamJsonParser.class.getName());
    private static final Gson GSON = new Gson();

    private static String readFileQuietly(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            java.nio.file.Path p = java.nio.file.Path.of(path);
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        }
        catch (Exception e) {
            return null;
        }
    }

    private static String apiErrorMessage(JsonObject obj) {
        for (String key : new String[]{ClaudeJsonKeyEnum.RESULT.key(), ClaudeJsonKeyEnum.ERROR.key(), ClaudeJsonKeyEnum.MESSAGE.key()}) {
            String v = JsonUtils.getString(obj, key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "Claude API error — you may have reached your usage/plan limit.";
    }

    private final AiProcessEventListener listener;
    private final Map<String, String> fileContents = new ConcurrentHashMap<>();
    private long cachedContextWindow = 0;
    private Consumer<String> onFirstSessionId;
    private java.util.function.Predicate<String> fileAllowed;

    public ClaudeStreamJsonParser(AiProcessEventListener listener) {
        this.listener = listener;
    }

    public void setOnFirstSessionId(Consumer<String> consumer) {
        this.onFirstSessionId = consumer;
    }

    public void setFileAllowed(java.util.function.Predicate<String> pred) {
        this.fileAllowed = pred;
    }

    public long getCachedContextWindow() {
        return cachedContextWindow;
    }

    public void initCachedContextWindow(long cw) {
        if (cw > 0) {
            cachedContextWindow = cw;
        }
    }

    /**
     * Call before parsing lines so Edit patches can be applied.
     */
    public void setCurrentFileContent(String path, String content) {
        fileContents.put(path, content);
    }

    public void parseLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }

        // Parse/convert in isolated try-catch to avoid misclassifying listener exceptions
        AiProcessEvent event = null;
        boolean parseFailure = false;

        try {
            JsonObject obj = GSON.fromJson(line, JsonObject.class);
            Consumer<String> cb = onFirstSessionId;
            if (cb != null) {
                String claudeId = JsonUtils.getString(obj, ClaudeJsonKeyEnum.SESSION_ID.key());
                if (claudeId != null && !claudeId.isBlank()) {
                    onFirstSessionId = null;
                    cb.accept(claudeId);
                }
            }
            event = toEvent(obj);
        }
        catch (RuntimeException e) {
            // RuntimeException (not just JsonSyntaxException) so a ClassCastException
            // from an unexpected-type JSON field can't kill the stream reader thread.
            // NOTE: cannot multi-catch (JsonSyntaxException | RuntimeException) — they are
            // related by subclassing, which is a compile error; RuntimeException covers both.
            parseFailure = true;
            LOG.log(Level.WARNING, "Skipping unparseable line: {0}", line);

            // If the unparseable line is a result event (even if malformed), emit FAILED
            // so the turn doesn't stay in processing state. String check is the only option
            // since the JSON didn't parse.
            if (line.contains("\"" + ClaudeJsonKeyEnum.TYPE.key() + "\":\"result\"")) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        "Claude response could not be parsed. This may indicate an incomplete or corrupted response."));
            }
        }

        // Listener call outside catch block so listener exceptions aren't misclassified
        if (!parseFailure && event != null) {
            listener.onAiProcessEvent(event);
        }
    }

    private AiProcessEvent toEvent(JsonObject obj) {
        String type = JsonUtils.getString(obj, ClaudeJsonKeyEnum.TYPE.key());
        AiProcessEvent event = switch (type == null ? "" : type) {
            case "assistant" ->
                parseAssistant(obj);
            case "result" ->
                parseResult(obj);
            case "system" ->
                parseSystem(obj);
            default -> {
                LOG.log(Level.FINE, "Unhandled claude event type: {0}", type);
                yield null;
            }
        };
        if (event != null) {
            LOG.log(Level.FINE, "Parsed event: {0}", event.getClass().getSimpleName());
        }
        return event;
    }

    private AiProcessEvent parseAssistant(JsonObject obj) {
        JsonObject msg = obj.has(ClaudeJsonKeyEnum.MESSAGE.key()) ? obj.getAsJsonObject(ClaudeJsonKeyEnum.MESSAGE.key()) : null;
        if (msg == null) {
            return null;
        }
        JsonArray content = msg.has(ClaudeJsonKeyEnum.CONTENT.key()) ? msg.getAsJsonArray(ClaudeJsonKeyEnum.CONTENT.key()) : null;
        if (content == null || content.isEmpty()) {
            return null;
        }

        // An assistant message can have both text and tool_use blocks (e.g. "I'll edit…" + Edit tool).
        // Iterate all blocks so we never miss a tool_use at index 1+.
        String turnId = JsonUtils.getString(msg, ClaudeJsonKeyEnum.TURN_ID.key());
        for (JsonElement el : content) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject block = el.getAsJsonObject();
            String blockType = JsonUtils.getString(block, ClaudeJsonKeyEnum.TYPE.key());
            if ("text".equals(blockType)) {
                String text = JsonUtils.getString(block, ClaudeJsonKeyEnum.TEXT.key());
                listener.onAiProcessEvent(new TextDeltaEvent(text == null ? "" : text, turnId));
            }
            else if ("tool_use".equals(blockType)) {
                AiProcessEvent ev = parseToolUse(block);
                if (ev != null) {
                    listener.onAiProcessEvent(ev);
                }
            }
        }
        return null; // events emitted directly above
    }

    private AiProcessEvent parseToolUse(JsonObject block) {
        String toolName = JsonUtils.getString(block, ClaudeJsonKeyEnum.TOOL_NAME.key());
        JsonObject input = block.has(ClaudeJsonKeyEnum.INPUT.key()) ? block.getAsJsonObject(ClaudeJsonKeyEnum.INPUT.key()) : null;
        if (input == null) {
            return null;
        }

        String path = JsonUtils.getString(input, ClaudeJsonKeyEnum.PATH.key());

        return switch (toolName == null ? "" : toolName) {
            case "Write" -> {
                String content = JsonUtils.getString(input, ClaudeJsonKeyEnum.WRITE_CONTENT.key());
                String disk = (fileAllowed == null || fileAllowed.test(path)) ? readFileQuietly(path) : null;
                String proposed = content != null ? content : (disk != null ? disk : "");
                // If disk already matches proposed the write already ran; no prior content to show
                String original = (disk != null && !disk.equals(proposed)) ? disk : "";
                yield new ToolUseEvent(toolName, path, proposed, original, ToolUseEvent.Kind.WRITE);
            }
            case "Edit" -> {
                String oldStr = JsonUtils.getString(input, ClaudeJsonKeyEnum.OLD_STRING.key());
                String newStr = JsonUtils.getString(input, ClaudeJsonKeyEnum.NEW_STRING.key());
                String disk = (fileAllowed == null || fileAllowed.test(path)) ? readFileQuietly(path) : null;

                // The pipe buffer is small enough that claude almost always executes the edit
                // before our reader thread reaches here. Use old_string/new_string to determine
                // whether the edit has already been applied, then reconstruct accordingly so the
                // diff always shows correct before/after regardless of who won the race.
                String original, proposed;
                if (disk != null && oldStr != null && newStr != null && !oldStr.equals(newStr)) {
                    if (disk.contains(newStr)) {
                        // new_string found in file → edit already applied; reconstruct original.
                        // Check newStr FIRST because for additive edits (e.g. adding a comment
                        // before a method) newStr contains oldStr, so the post-edit file also
                        // passes disk.contains(oldStr) — checking newStr first avoids that trap.
                        int idx = disk.indexOf(newStr);
                        original = disk.substring(0, idx) + oldStr + disk.substring(idx + newStr.length());
                        proposed = disk;
                    }
                    else if (disk.contains(oldStr)) {
                        // old_string found but new_string absent → edit not yet applied
                        int idx = disk.indexOf(oldStr);
                        original = disk;
                        proposed = disk.substring(0, idx) + newStr + disk.substring(idx + oldStr.length());
                    }
                    else {
                        // Neither string found — fall back to cache
                        original = fileContents.getOrDefault(path, disk);
                        proposed = disk;
                    }
                }
                else {
                    String base = disk != null ? disk : fileContents.getOrDefault(path, "");
                    original = base;
                    proposed = (oldStr != null && newStr != null) ? base.replace(oldStr, newStr) : base;
                }
                yield new ToolUseEvent(toolName, path, proposed, original, ToolUseEvent.Kind.EDIT);
            }
            default ->
                new ToolUseEvent(toolName, path, "", null, ToolUseEvent.Kind.OTHER);
        };
    }

    private AiProcessEvent parseResult(JsonObject obj) {
        long total = 0;
        JsonObject usage = obj.has(ClaudeJsonKeyEnum.USAGE.key()) ? obj.getAsJsonObject(ClaudeJsonKeyEnum.USAGE.key()) : null;
        if (usage != null) {
            // Input context window usage — output_tokens are generated tokens and don't
            // consume the input context window, so exclude them to avoid inflating the %.
            total += JsonUtils.getLong(usage, ClaudeJsonKeyEnum.INPUT_TOKENS.key())
                    + JsonUtils.getLong(usage, ClaudeJsonKeyEnum.CACHE_READ_INPUT_TOKENS.key())
                    + JsonUtils.getLong(usage, ClaudeJsonKeyEnum.CACHE_CREATION_INPUT_TOKENS.key());
        }

        // Cache context window whenever modelUsage is present so later result events
        // (which sometimes omit modelUsage) can still compute a session percentage.
        if (obj.has(ClaudeJsonKeyEnum.MODEL_USAGE.key()) && obj.get(ClaudeJsonKeyEnum.MODEL_USAGE.key()).isJsonObject()) {
            JsonObject modelUsage = obj.getAsJsonObject(ClaudeJsonKeyEnum.MODEL_USAGE.key());
            for (Map.Entry<String, JsonElement> entry : modelUsage.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    long cw = JsonUtils.getLong(entry.getValue().getAsJsonObject(), ClaudeJsonKeyEnum.CONTEXT_WINDOW.key());
                    if (cw > 0) {
                        cachedContextWindow = cw;
                    }
                    break;
                }
            }
        }

        if (cachedContextWindow > 0 && total > 0) {
            double sessionPct = Math.min((double) total / cachedContextWindow * 100.0, 100.0);
            listener.onAiProcessEvent(new ClaudeSessionInfoEvent(-1, sessionPct, null));
        }

        // A successful run reports subtype "success"; anything else (e.g.
        // "error_during_execution" from an aborted_streaming turn) means the
        // response ended before completing. Surface a visible notice so the user
        // isn't left staring at silent no-output, then still complete the turn.
        String subtype = JsonUtils.getString(obj, ClaudeJsonKeyEnum.SUBTYPE.key());
        if ("api_error".equals(subtype)) {
            // Hard API error (e.g. usage/plan limit). The persistent process stays
            // alive, so no process-exit EXITED fires — surface FAILED so the tab
            // goes red like other backends, and do NOT emit TurnCompleteEvent (that
            // would reset the tab back to green/Ready).
            LOG.log(Level.WARNING, "Claude turn ended with api_error");
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, apiErrorMessage(obj)));
            return null;
        }
        if (subtype != null && !"success".equals(subtype)) {
            LOG.log(Level.WARNING, "Claude turn ended abnormally: subtype={0}", subtype);
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INTERRUPTED,
                    StatusMessageUtil.formatTurnInterrupted(subtype)));
        }

        return new TurnCompleteEvent();
    }

    private AiProcessEvent parseSystem(JsonObject obj) {
        String subtype = JsonUtils.getString(obj, ClaudeJsonKeyEnum.SUBTYPE.key());
        LOG.log(Level.FINE, "claude system event fields: {0}", obj.keySet());

        if ("thinking_tokens".equals(subtype)) {
            long tokens = JsonUtils.getLong(obj, ClaudeJsonKeyEnum.ESTIMATED_TOKENS.key());
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.THINKING, "Thinking… (" + tokens + " tokens)"));
            return null;
        }

        if ("init".equals(subtype)) {
            String model = JsonUtils.getString(obj, ClaudeJsonKeyEnum.MODEL.key());
            // usage_pct/session_pct not present in init — session% comes from result event
            return new ClaudeSessionInfoEvent(-1, -1, model);
        }

        if ("task_notification".equals(subtype)) {
            String summary = JsonUtils.getString(obj, ClaudeJsonKeyEnum.SUMMARY.key());
            String msg = (summary != null && !summary.isBlank()) ? summary : "Background task completed";
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, msg));
            return null;
        }

        if ("api_error".equals(subtype)) {
            LOG.log(Level.WARNING, "Claude system api_error");
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, apiErrorMessage(obj)));
            return null;
        }

        return null;
    }

}

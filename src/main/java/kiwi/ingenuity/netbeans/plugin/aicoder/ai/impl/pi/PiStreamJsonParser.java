package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiThinkingLevelChangedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiToolResultEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.JsonUtils;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Turns the {@code pi --mode rpc} stdout stream into the shared {@link AiProcessEvent} feed. The session forwards every
 * well-formed line here, so this class sees event frames, correlated {@code response} frames, and
 * {@code extension_ui_request} frames alike.
 *
 * <p>
 * Turn lifecycle maps as follows: {@code agent_start} and the first {@code thinking_delta} surface THINKING;
 * {@code agent_settled} closes the turn with TurnCompleteEvent and READY; a {@code message_update} carries
 * {@code assistantMessageEvent} text deltas; tool activity arrives as
 * {@code tool_execution_start}/{@code tool_execution_end} pairs (the {@code toolcall_*} subtypes inside
 * assistantMessageEvent are ignored); {@code message_end} with {@code stopReason:"error"} surfaces FAILED (a
 * {@code stopReason:"aborted"} is left to the run manager, which owns Stop; nothing is shown here for it).
 *
 * <p>
 * {@code extension_ui_request}{@code :confirm} becomes a {@link ConfirmEvent}; once the caller resolves its
 * {@code PermissionDecision}, the reply is written through {@link #setUiResponseSender} as
 * {@code {type:"extension_ui_response", id, confirmed|cancelled:true}}. Other request methods — select/input/editor —
 * are not supported yet and are answered {@code cancelled:true} immediately. {@code notify} requests surface as INFO.
 *
 * <p>
 * Any field path not pinned by live verification is read best-effort with the camelCase spelling from pi's TypeScript
 * types. Successful command responses ({@code get_state}, {@code get_available_models}, {@code get_session_stats}, ...)
 * are read directly by {@code PiAiProcessManager} via the id-correlated future in {@code PiPersistentSession} — this
 * class does not act on them itself.
 */
public final class PiStreamJsonParser {

    private static final Logger LOG = Logger.getLogger(PiStreamJsonParser.class.getName());
    private static final Gson GSON = new Gson();
    private static final String ACCEPTED_MARKER = "SUCCESS — the user accepted";

    private final AiProcessEventListener listener;
    private Consumer<String> uiResponseSender;
    private boolean thinkingStarted = false;

    public PiStreamJsonParser(AiProcessEventListener listener) {
        this.listener = listener;
    }

    /**
     * Writer for {@code extension_ui_response} replies ({@code {type, id, confirmed|cancelled}} without a trailing
     * newline). Wire it to the session's control channel.
     */
    public void setUiResponseSender(Consumer<String> uiResponseSender) {
        this.uiResponseSender = uiResponseSender;
    }

    public void parseLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }

        // Parse/convert in isolated try-catch so a bad frame can't be misclassified as a listener exception.
        AiProcessEvent event = null;
        boolean parseFailure = false;
        try {
            JsonObject obj = GSON.fromJson(line, JsonObject.class);
            event = toEvent(obj);
        }
        catch (RuntimeException e) {
            // RuntimeException (not just JsonSyntaxException) so a ClassCastException from an unexpected-type field
            // can't kill the stream reader thread.
            parseFailure = true;
            LOG.log(Level.WARNING, "Skipping unparseable pi line: {0}", line);
            // Textual fallback ONLY for message_end, not agent_settled or any other frame — a corrupted
            // agent_settled line has no recovery here and THINKING would stay up with no FAILED backstop.
            // Accepted as a documented residual gap: pipes do not tear lines apart in normal
            // operation (each pi stdout write is one complete JSONL line), so byte-level corruption of a
            // WELL-FORMED frame is not a realistic failure mode to design around; the case this really guards is
            // pi dying mid-write and leaving a truncated final line, and message_end is specifically where that
            // matters most (the last frame before a turn otherwise appears to hang). A mid-stream process death is
            // separately covered by PiPersistentSession's EOF handling and PiAiProcessManager's process-exit path,
            // which do not depend on this fallback at all.
            if (line.contains("\"type\":\"" + PiEventTypeEnum.MESSAGE_END.type() + "\"")) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                                                          "Pi response could not be parsed. This may indicate an incomplete or corrupted response."));
            }
        }

        // Listener call outside catch block so listener exceptions aren't misclassified.
        if (!parseFailure && event != null) {
            listener.onAiProcessEvent(event);
        }
    }

    private AiProcessEvent toEvent(JsonObject obj) {
        PiEventTypeEnum type = PiEventTypeEnum.of(JsonUtils.getString(obj, PiJsonKeyEnum.TYPE.key()));
        if (type == null) {
            LOG.log(Level.FINE, "Unhandled pi event type: {0}", JsonUtils.getString(obj, PiJsonKeyEnum.TYPE.key()));
            return null;
        }
        return switch (type) {
            case AGENT_START -> {
                thinkingStarted = false;
                emit(new StatusEvent(StatusEventTypeEnum.THINKING, "Thinking…"));
                yield null;
            }
            case AGENT_SETTLED -> {
                emit(new TurnCompleteEvent());
                emit(new StatusEvent(StatusEventTypeEnum.READY, StatusMessageUtil.formatReady("Pi")));
                yield null;
            }
            case MESSAGE_UPDATE ->
                parseMessageUpdate(obj);
            case TOOL_EXECUTION_START -> {
                parseToolExecutionStart(obj);
                yield null;
            }
            case TOOL_EXECUTION_END ->
                parseToolExecutionEnd(obj);
            case AGENT_END -> {
                parseAgentEnd(obj);
                yield null;
            }
            case AUTO_RETRY_START -> {
                parseAutoRetryStart(obj);
                yield null;
            }
            case AUTO_RETRY_END -> {
                parseAutoRetryEnd(obj);
                yield null;
            }
            case MESSAGE_END -> {
                parseMessageEnd(obj);
                yield null;
            }
            case COMPACTION_START -> {
                parseCompactionStart(obj);
                yield null;
            }
            case COMPACTION_END -> {
                parseCompactionEnd(obj);
                yield null;
            }
            case THINKING_LEVEL_CHANGED ->
                parseThinkingLevelChanged(obj);
            case RESPONSE ->
                parseResponse(obj);
            case EXTENSION_UI_REQUEST ->
                parseExtensionUiRequest(obj);
            case EXTENSION_UI_RESPONSE, TURN_START, TURN_END, MESSAGE_START, QUEUE_UPDATE, TOOL_EXECUTION_UPDATE -> {
                LOG.log(Level.FINE, "Ignoring pi event type: {0}", type.type());
                yield null;
            }
        };
    }

    private AiProcessEvent parseMessageUpdate(JsonObject obj) {
        JsonObject ame = obj.has(PiJsonKeyEnum.ASSISTANT_MESSAGE_EVENT.key())
                && obj.get(PiJsonKeyEnum.ASSISTANT_MESSAGE_EVENT.key()).isJsonObject()
                         ? obj.getAsJsonObject(PiJsonKeyEnum.ASSISTANT_MESSAGE_EVENT.key()) : null;
        if (ame == null) {
            return null;
        }
        String subtype = JsonUtils.getString(ame, PiJsonKeyEnum.TYPE.key());
        return switch (subtype == null ? "" : subtype) {
            case "text_delta" -> {
                String delta = JsonUtils.getString(ame, PiJsonKeyEnum.DELTA.key());
                yield new TextDeltaEvent(delta == null ? "" : delta, null);
            }
            case "thinking_delta" -> {
                if (!thinkingStarted) {
                    thinkingStarted = true;
                    yield new StatusEvent(StatusEventTypeEnum.THINKING, "Thinking…");
                }
                yield null;
            }
            case "toolcall_start", "toolcall_delta", "toolcall_end" ->
                null;
            default -> {
                LOG.log(Level.FINE, "Unhandled assistantMessageEvent subtype: {0}", subtype);
                yield null;
            }
        };
    }

    private void parseToolExecutionStart(JsonObject obj) {
        String toolName = JsonUtils.getString(obj, PiJsonKeyEnum.TOOL_NAME.key());
        JsonObject args = obj.has(PiJsonKeyEnum.ARGS.key()) && obj.get(PiJsonKeyEnum.ARGS.key()).isJsonObject()
                          ? obj.getAsJsonObject(PiJsonKeyEnum.ARGS.key()) : null;
        String path = args == null ? null : JsonUtils.getString(args, PiJsonKeyEnum.PATH.key());
        emit(new ToolUseEvent(toolName, path, "", null, ToolUseEvent.Kind.OTHER));
    }

    private AiProcessEvent parseToolExecutionEnd(JsonObject obj) {
        String toolCallId = JsonUtils.getString(obj, PiJsonKeyEnum.TOOL_CALL_ID.key());
        String toolName = JsonUtils.getString(obj, PiJsonKeyEnum.TOOL_NAME.key());
        String resultText = extractResultText(obj);
        boolean isError = getBoolean(obj, PiJsonKeyEnum.IS_ERROR.key());
        // A write/edit whose proposal the user accepted through the review gate reports isError=true with this
        // marker inside the pending text — a rejection, not a failure, so it must not render red.
        if (resultText != null && resultText.startsWith(ACCEPTED_MARKER)) {
            isError = false;
        }
        return new PiToolResultEvent(toolCallId, toolName, resultText, isError);
    }

    private void parseAgentEnd(JsonObject obj) {
        if (getBoolean(obj, PiJsonKeyEnum.WILL_RETRY.key())) {
            emit(new StatusEvent(StatusEventTypeEnum.INFO, "pi will retry this turn automatically."));
        }
    }

    private void parseAutoRetryStart(JsonObject obj) {
        long attempt = JsonUtils.getLong(obj, PiJsonKeyEnum.ATTEMPT.key());
        long max = JsonUtils.getLong(obj, PiJsonKeyEnum.MAX_ATTEMPTS.key());
        long delayMs = JsonUtils.getLong(obj, PiJsonKeyEnum.DELAY_MS.key());
        String errorMessage = JsonUtils.getString(obj, PiJsonKeyEnum.ERROR_MESSAGE.key());
        String msg = "Retrying (" + attempt + "/" + max + ") in " + delayMs + " ms"
                + (errorMessage == null || errorMessage.isBlank() ? "" : ": " + errorMessage);
        emit(new StatusEvent(StatusEventTypeEnum.INFO, msg));
    }

    private void parseAutoRetryEnd(JsonObject obj) {
        if (!getBoolean(obj, PiJsonKeyEnum.SUCCESS.key())) {
            emit(new StatusEvent(StatusEventTypeEnum.INFO, "Automatic retry exhausted; the turn is ending without a result."));
        }
    }

    /**
     * {@code stopReason}/{@code errorMessage} live on {@code message_end.message} (pi's {@code AssistantMessage}), NOT
     * top-level on the event itself — confirmed live against a real pi 0.85.1 process:
     * {@code {"type":"message_end","message":{"role":"assistant",...,"stopReason":"stop",...}}}. A {@code message_end}
     * fires for BOTH the user's own echoed message and the assistant's — the user one has no {@code stopReason} field
     * at all (not an {@code AssistantMessage}), so this is naturally a no-op for it.
     */
    private void parseMessageEnd(JsonObject obj) {
        JsonObject message = obj.has(PiJsonKeyEnum.MESSAGE.key()) && obj.get(PiJsonKeyEnum.MESSAGE.key()).isJsonObject()
                             ? obj.getAsJsonObject(PiJsonKeyEnum.MESSAGE.key()) : null;
        if (message == null) {
            return;
        }
        String stopReason = JsonUtils.getString(message, PiJsonKeyEnum.STOP_REASON.key());
        if ("error".equals(stopReason)) {
            String errorMessage = JsonUtils.getString(message, PiJsonKeyEnum.ERROR_MESSAGE.key());
            emit(new StatusEvent(StatusEventTypeEnum.FAILED,
                                 errorMessage == null || errorMessage.isBlank() ? "Pi turn failed." : errorMessage));
        }
        // stopReason "aborted" is owned by the run manager (Stop); nothing is rendered here.
    }

    /**
     * {@code compaction_start} — fires for BOTH the plugin's own explicit {@code compact} command
     * ({@code reason:"manual"}) and pi's own automatic compaction ({@code reason:"threshold"|"overflow"}, never
     * requested by the plugin) — verified against the shipped {@code agent-session.d.ts}. Surfaced as INFO regardless
     * of reason so an automatic compaction is never a silent context-gauge jump.
     */
    private void parseCompactionStart(JsonObject obj) {
        emit(new StatusEvent(StatusEventTypeEnum.INFO, "Compacting conversation…"));
    }

    /**
     * {@code compaction_end} — {@code {reason, result, aborted, willRetry, errorMessage?}} per {@code
     * agent-session.d.ts}. Reports whichever of the three real outcomes applies; {@code willRetry}/{@code result} are
     * not surfaced individually to keep this to the same "one INFO line" level of detail as {@link
     * #parseCompactionStart}.
     */
    private void parseCompactionEnd(JsonObject obj) {
        String text;
        if (getBoolean(obj, PiJsonKeyEnum.ABORTED.key())) {
            text = "Compaction aborted.";
        }
        else {
            String errorMessage = JsonUtils.getString(obj, PiJsonKeyEnum.ERROR_MESSAGE.key());
            text = errorMessage != null && !errorMessage.isBlank() ? "Compaction failed: " + errorMessage
                   : "Conversation compacted.";
        }
        emit(new StatusEvent(StatusEventTypeEnum.INFO, text));
    }

    /**
     * {@code thinking_level_changed} — {@code {type, level}} per {@code agent-session.d.ts}. Fires when the level
     * changes by any means OTHER than the plugin's own {@code set_thinking_level} RPC. Produces
     * {@link PiThinkingLevelChangedEvent} rather than acting on it directly — see that class's own doc comment for why
     * (the info-bar-facing {@code PiSessionControl.Listener} hop lives in {@code
     * PiAiProcessManager}, a different class than this parser).
     */
    private AiProcessEvent parseThinkingLevelChanged(JsonObject obj) {
        String level = JsonUtils.getString(obj, PiJsonKeyEnum.LEVEL.key());
        return level != null && !level.isBlank() ? new PiThinkingLevelChangedEvent(level) : null;
    }

    /**
     * A failed {@code prompt}/{@code steer}/{@code abort} response is the direct result of a user action with no other
     * owner, so it is shown as FAILED here. {@code compact} is deliberately EXCLUDED even though it is also
     * user-facing: {@code PiAiImplementation.compact()}'s own {@code whenComplete} already emits "Compact failed: …"
     * for a rejected response, and it must stay the single owner of that error surface because it also has to report
     * the no-session and failed-send cases this parser never sees at all — including COMPACT here too double-reported a
     * rejected compact. Every other command (get_state, get_available_models, get_session_stats, set_model,
     * get_available_thinking_levels, set_thinking_level) is background/housekeeping that {@code PiAiProcessManager}
     * already treats as best-effort and silently swallows on failure (see e.g. its
     * refreshThinkingLevels/refreshContextUsage, which just return on a failed response) — showing the user a FAILED
     * banner for one of THOSE, e.g. a transient get_session_stats hiccup right after a perfectly good turn, would
     * report an error that has nothing to do with anything the user did. Log-only for every command in this second
     * group, compact included.
     *
     * <p>
     * A response that lacks the {@code command} field real pi always echoes (test fakes, and real pi on some paths)
     * lands on {@code null} here too: {@code PiRpcCommandEnum.of(null)} returns {@code null}, and the {@code
     * Set.of(...)} {@code contains} check NPEs on Java 21 — so a well-formed failed response with no command is treated
     * as the background/ignored case (FINE), never as unparseable WARNING.
     */
    private static final Set<PiRpcCommandEnum> USER_FACING_COMMANDS = Set.of(
            PiRpcCommandEnum.PROMPT, PiRpcCommandEnum.STEER, PiRpcCommandEnum.ABORT);

    private AiProcessEvent parseResponse(JsonObject obj) {
        String command = JsonUtils.getString(obj, PiJsonKeyEnum.COMMAND.key());
        if (!getBoolean(obj, PiJsonKeyEnum.SUCCESS.key())) {
            String error = JsonUtils.getString(obj, PiJsonKeyEnum.ERROR.key());
            String m = JsonUtils.getString(obj, PiJsonKeyEnum.MESSAGE.key());
            String reason = error != null && !error.isBlank() ? error : (m != null && !m.isBlank() ? m : "Pi command failed.");
            PiRpcCommandEnum commandEnum = PiRpcCommandEnum.of(command);
            if (commandEnum != null && USER_FACING_COMMANDS.contains(commandEnum)) {
                emit(new StatusEvent(StatusEventTypeEnum.FAILED, reason));
            }
            else if (command == null) {
                LOG.log(Level.FINE, "Pi failed response carried no command field: {0}", reason);
            }
            else {
                LOG.log(Level.FINE, "Pi background command {0} failed: {1}", new Object[]{command, reason});
            }
            return null;
        }

        // Successful command responses (get_state, get_available_models, get_session_stats, set_model, ...) are all
        // read directly by PiAiProcessManager via the id-correlated future in PiPersistentSession, so there is
        // nothing left for this listener path to act on.
        LOG.log(Level.FINE, "Pi response for command: {0}", command);
        return null;
    }

    private AiProcessEvent parseExtensionUiRequest(JsonObject obj) {
        String method = JsonUtils.getString(obj, PiJsonKeyEnum.METHOD.key());
        String id = JsonUtils.getString(obj, PiJsonKeyEnum.ID.key());
        switch (method == null ? "" : method) {
            case "confirm" -> {
                String title = JsonUtils.getString(obj, PiJsonKeyEnum.TITLE.key());
                String message = JsonUtils.getString(obj, PiJsonKeyEnum.MESSAGE.key());
                String displayText = (title != null && !title.isBlank()) ? title
                                     : ((message != null && !message.isBlank()) ? message : "Approve this action?");
                CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
                future.whenComplete((decision, err) -> {
                    if (uiResponseSender == null || decision == null) {
                        return;
                    }
                    JsonObject resp = new JsonObject();
                    resp.addProperty(PiJsonKeyEnum.TYPE.key(), PiEventTypeEnum.EXTENSION_UI_RESPONSE.type());
                    resp.addProperty(PiJsonKeyEnum.ID.key(), id);
                    resp.addProperty(decision.allow() ? PiJsonKeyEnum.CONFIRMED.key() : PiJsonKeyEnum.CANCELLED.key(), true);
                    uiResponseSender.accept(GSON.toJson(resp));
                });
                return new ConfirmEvent("extension_ui", displayText, null, null, future);
            }
            case "select", "input", "editor" -> {
                writeExtensionUiReply(id, false);
                LOG.log(Level.FINE, "Auto-cancelling unsupported extension_ui_request method: {0}", method);
                return null;
            }
            case "notify" -> {
                String title = JsonUtils.getString(obj, PiJsonKeyEnum.TITLE.key());
                String message = JsonUtils.getString(obj, PiJsonKeyEnum.MESSAGE.key());
                String text = (title != null && !title.isBlank()) ? title
                              : ((message != null && !message.isBlank()) ? message : "Pi notification");
                emit(new StatusEvent(StatusEventTypeEnum.INFO, text));
                return null;
            }
            default -> {
                LOG.log(Level.FINE, "Unhandled extension_ui_request method: {0}", method);
                return null;
            }
        }
    }

    private void writeExtensionUiReply(String id, boolean confirmed) {
        if (uiResponseSender == null) {
            return;
        }
        JsonObject resp = new JsonObject();
        resp.addProperty(PiJsonKeyEnum.TYPE.key(), PiEventTypeEnum.EXTENSION_UI_RESPONSE.type());
        resp.addProperty(PiJsonKeyEnum.ID.key(), id);
        resp.addProperty(confirmed ? PiJsonKeyEnum.CONFIRMED.key() : PiJsonKeyEnum.CANCELLED.key(), true);
        uiResponseSender.accept(GSON.toJson(resp));
    }

    /**
     * A {@code result.content} array can carry more than one text part (e.g. a multi-file tool result) — every
     * non-blank part is joined with newlines rather than returning only the first and silently dropping the rest.
     */
    private static String extractResultText(JsonObject obj) {
        if (!obj.has(PiJsonKeyEnum.RESULT.key()) || !obj.get(PiJsonKeyEnum.RESULT.key()).isJsonObject()) {
            return null;
        }
        JsonObject result = obj.getAsJsonObject(PiJsonKeyEnum.RESULT.key());
        if (result.has(PiJsonKeyEnum.CONTENT.key()) && result.get(PiJsonKeyEnum.CONTENT.key()).isJsonArray()) {
            StringBuilder combined = new StringBuilder();
            for (JsonElement el : result.getAsJsonArray(PiJsonKeyEnum.CONTENT.key())) {
                if (!el.isJsonObject()) {
                    continue;
                }
                String text = JsonUtils.getString(el.getAsJsonObject(), PiJsonKeyEnum.TEXT.key());
                if (text != null && !text.isBlank()) {
                    if (combined.length() > 0) {
                        combined.append('\n');
                    }
                    combined.append(text);
                }
            }
            return combined.length() > 0 ? combined.toString() : null;
        }
        return JsonUtils.getString(result, PiJsonKeyEnum.TEXT.key());
    }

    private static boolean getBoolean(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive()
                && o.get(key).getAsBoolean();
    }

    private void emit(AiProcessEvent event) {
        if (event != null) {
            listener.onAiProcessEvent(event);
        }
    }
}

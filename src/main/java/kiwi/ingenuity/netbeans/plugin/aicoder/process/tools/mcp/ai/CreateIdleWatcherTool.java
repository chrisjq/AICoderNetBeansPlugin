package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch.IdleWatcherRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch.IdleWatcherStatus;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.DateUtil;

public class CreateIdleWatcherTool extends AbstractActionTool {

    private static final int NOTE_MAX_LENGTH = 500;
    private static final String DESCRIPTION
            = "Watch another AI session and be told when it has been idle (between turns) for timeoutMinutes. "
            + "The clock starts when its turn ends and resets whenever it starts another turn. oneshot (default) fires "
            + "once and is removed; recurring fires once per idle period and re-arms after the target's next turn. "
            + "The notice says when the target became idle. Use it while waiting on another AI so you wake up if it "
            + "stops. It never interrupts your turn by default.";

    public CreateIdleWatcherTool() {
        super(McpSectionEnum.PLUGIN,
              McpToolEnum.CREATE_IDLE_WATCHER.toolName(),
              DESCRIPTION,
              McpToolEnum.CREATE_IDLE_WATCHER.toolName() + " -> creates an idle watcher on the target session; you are "
              + "notified when the target stays idle past the timeout");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.CREATE_IDLE_WATCHER.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), DESCRIPTION);
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject tid = new JsonObject();
        tid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        tid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                        "Target session ID from " + McpToolEnum.LIST_AI_SESSIONS.toolName() + " (not your own).");
        props.add(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key(), tid);
        JsonObject timeout = new JsonObject();
        timeout.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        timeout.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                            "Idle timeout in whole minutes. Default " + IdleWatcherRegistry.DEFAULT_TIMEOUT.toMinutes()
                            + ", minimum " + IdleWatcherRegistry.MIN_TIMEOUT.toMinutes() + ".");
        props.add(CreateIdleWatcherParamEnum.TIMEOUT_MINUTES.key(), timeout);
        JsonObject recurring = new JsonObject();
        recurring.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        recurring.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                              "true = fire once per idle period and re-arm after the target's next turn; false (default) = fire once and remove.");
        props.add(CreateIdleWatcherParamEnum.RECURRING.key(), recurring);
        JsonObject interrupt = new JsonObject();
        interrupt.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        interrupt.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                              "true = interrupt your turn when the watcher fires, if your session allows important "
                              + "messages; false (default) = the notice waits until your current turn ends.");
        props.add(CreateIdleWatcherParamEnum.INTERRUPT.key(), interrupt);
        JsonObject note = new JsonObject();
        note.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        note.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                         "Optional note (max " + NOTE_MAX_LENGTH + " chars) attached to the watcher.");
        props.add(CreateIdleWatcherParamEnum.NOTE.key(), note);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        if (session == null || !session.getAiSession().allowsIdleWatcherTimer()) {
            return IdleWatcherTools.DISABLED_MESSAGE;
        }
        String typeError = args.requireStringIfPresent(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key());
        if (typeError != null) {
            return "Error: " + typeError;
        }
        typeError = args.requireStringIfPresent(CreateIdleWatcherParamEnum.NOTE.key());
        if (typeError != null) {
            return "Error: " + typeError;
        }
        typeError = args.requireBooleanIfPresent(CreateIdleWatcherParamEnum.RECURRING.key());
        if (typeError != null) {
            return "Error: " + typeError;
        }
        typeError = args.requireBooleanIfPresent(CreateIdleWatcherParamEnum.INTERRUPT.key());
        if (typeError != null) {
            return "Error: " + typeError;
        }
        String targetSessionId = args.str(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key());
        if (targetSessionId == null || targetSessionId.isBlank()) {
            return "Error: " + CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key() + " is required";
        }
        String rawTimeout = args.str(CreateIdleWatcherParamEnum.TIMEOUT_MINUTES.key());
        int minutes;
        if (rawTimeout == null) {
            if (args.has(CreateIdleWatcherParamEnum.TIMEOUT_MINUTES.key())) {
                return "Error: " + CreateIdleWatcherParamEnum.TIMEOUT_MINUTES.key()
                        + " must be a whole number of minutes";
            }
            minutes = (int) IdleWatcherRegistry.DEFAULT_TIMEOUT.toMinutes();
        }
        else if (!rawTimeout.matches("[0-9]+")) {
            return "Error: " + CreateIdleWatcherParamEnum.TIMEOUT_MINUTES.key()
                    + " must be a whole number of minutes";
        }
        else {
            try {
                minutes = Integer.parseInt(rawTimeout);
            }
            catch (NumberFormatException e) {
                return "Error: " + CreateIdleWatcherParamEnum.TIMEOUT_MINUTES.key()
                        + " must be a whole number of minutes";
            }
        }
        long minimum = IdleWatcherRegistry.MIN_TIMEOUT.toMinutes();
        if (minutes < minimum) {
            return "Error: " + CreateIdleWatcherParamEnum.TIMEOUT_MINUTES.key()
                    + " must be at least " + minimum
                    + " minutes (the minimum allowed idle watcher timeout).";
        }
        boolean recurring = args.bool(CreateIdleWatcherParamEnum.RECURRING.key());
        boolean interrupt = args.bool(CreateIdleWatcherParamEnum.INTERRUPT.key());
        String note = args.str(CreateIdleWatcherParamEnum.NOTE.key());
        if (note != null && note.length() > NOTE_MAX_LENGTH) {
            return "Error: " + CreateIdleWatcherParamEnum.NOTE.key()
                    + " must be at most " + NOTE_MAX_LENGTH + " characters.";
        }
        String callerSessionId = session.getId();
        String watcherId;
        try {
            watcherId = IdleWatcherRegistry.getInstance()
                    .create(callerSessionId, targetSessionId, Duration.ofMinutes(minutes), recurring, interrupt, note)
                    .id();
        }
        catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        String targetName = IdleWatcherTools.sessionName(targetSessionId);
        IdleWatcherStatus status = IdleWatcherRegistry.getInstance().list(callerSessionId).stream()
                .filter(s -> s.watcher().id().equals(watcherId))
                .findFirst().orElse(null);
        String kind = recurring ? "recurring" : "oneshot";
        String interruptNote = interrupt ? " It will interrupt your turn when it fires."
                               : " It will not interrupt you — the notice arrives at the end of your turn.";
        if (status != null && status.idleSince() != null) {
            return "Idle watcher " + watcherId + " created on " + targetName + " (" + targetSessionId + "): "
                    + kind + ", " + minutes + " minutes. " + targetName + " has been idle since "
                    + DateUtil.format(status.idleSince()) + " — you will be told if it is still idle at "
                    + DateUtil.format(status.dueAt()) + "." + interruptNote;
        }
        return "Idle watcher " + watcherId + " created on " + targetName + " (" + targetSessionId + "): "
                + kind + ", " + minutes + " minutes. " + targetName
                + " is currently busy — the clock starts when its turn ends." + interruptNote;
    }
}

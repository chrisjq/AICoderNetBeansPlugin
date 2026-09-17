package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiInboxMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

public class DeleteAiMessageTool extends AbstractActionTool {

    public DeleteAiMessageTool() {
        super(McpSectionEnum.PLUGIN,
              McpToolEnum.DELETE_AI_MESSAGE.toolName(),
              "Delete one or more inbox messages by id. Pass " + DeleteAiMessageParamEnum.MESSAGE_ID.key() + " for a single message or " + DeleteAiMessageParamEnum.MESSAGE_IDS.key() + " array for bulk delete. At least one of the two is required; if both are given they are combined and all are deleted.",
              McpToolEnum.DELETE_AI_MESSAGE.toolName() + " -> delete one or more inbox messages once processed; pass " + DeleteAiMessageParamEnum.MESSAGE_IDS.key() + " array for bulk delete");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.DELETE_AI_MESSAGE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                         // "Exactly one" was wrong: handle() below merges both into a single
                         // id list, so a caller obeying the schema avoided a call the tool
                         // supports. The schema is the only description a model sees, so it
                         // has to describe what the handler actually accepts.
                         "Delete inbox messages by ID. Provide " + DeleteAiMessageParamEnum.MESSAGE_ID.key() + ", " + DeleteAiMessageParamEnum.MESSAGE_IDS.key() + ", or both; both are combined.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        // Caller credentials are declared here rather than by
        // applyCredentialsIfRequested so they can carry richer descriptions.
        // Callers without CREDENTIALS reach this tool through a bridge that
        // injects both values server-side, so they must not be asked for them.
        if (options.contains(McpInstructionOptionEnum.CREDENTIALS)) {
            JsonObject sid = new JsonObject();
            sid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            sid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your own session ID from the session identity block.");
            props.add(DeleteAiMessageParamEnum.SESSION_ID.key(), sid);
            JsonObject sk = new JsonObject();
            sk.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            sk.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your secret key from the session identity block.");
            props.add(DeleteAiMessageParamEnum.SECRET_KEY.key(), sk);
            required.add(DeleteAiMessageParamEnum.SESSION_ID.key());
            required.add(DeleteAiMessageParamEnum.SECRET_KEY.key());
        }
        JsonObject mid = new JsonObject();
        mid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        mid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Single message ID to delete.");
        props.add(DeleteAiMessageParamEnum.MESSAGE_ID.key(), mid);
        JsonObject mids = new JsonObject();
        mids.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject items = new JsonObject();
        items.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        mids.add(ToolSchemaKeyEnum.ITEMS.key(), items);
        mids.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Message IDs to delete.");
        props.add(DeleteAiMessageParamEnum.MESSAGE_IDS.key(), mids);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public boolean requiresGlobalMutationLock() {
        // In-memory broker state has its own synchronisation.
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String sessionId = args.str(DeleteAiMessageParamEnum.SESSION_ID.key());
        if (sessionId == null) {
            return "Error: " + DeleteAiMessageParamEnum.SESSION_ID.key() + " is required";
        }
        String secretKey = args.str(DeleteAiMessageParamEnum.SECRET_KEY.key());
        if (secretKey == null) {
            return "Error: " + DeleteAiMessageParamEnum.SECRET_KEY.key() + " is required";
        }
        List<String> ids = new ArrayList<>();
        String single = args.str(DeleteAiMessageParamEnum.MESSAGE_ID.key());
        if (single != null && !single.isBlank()) {
            ids.add(single);
        }
        JsonArray arr = args.array(DeleteAiMessageParamEnum.MESSAGE_IDS.key());
        if (arr != null) {
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive()) {
                    ids.add(el.getAsString());
                }
            }
        }
        if (ids.isEmpty()) {
            return "Error: provide " + DeleteAiMessageParamEnum.MESSAGE_ID.key() + " or a non-empty " + DeleteAiMessageParamEnum.MESSAGE_IDS.key() + " array";
        }
        if (!AiSessionInboxBroker.getInstance().validateSecret(sessionId, secretKey)) {
            return "Error: authentication failed — check that " + DeleteAiMessageParamEnum.SESSION_ID.key() + " and " + DeleteAiMessageParamEnum.SECRET_KEY.key() + " match your session identity";
        }
        List<String> distinctIds = ids.stream().distinct().toList();
        // Snapshotted BEFORE deleting: once a message is gone, listOwedReplies can no longer see it to report on it,
        // and a message owed by this session is necessarily already in its own inbox, so anything found here is
        // exactly what deleteMessages is about to remove.
        List<AiInboxMessage> owedAmongRequested = AiSessionInboxBroker.getInstance().listOwedReplies(sessionId)
                .stream()
                .filter(m -> distinctIds.contains(m.id()))
                .toList();
        int deleted = AiSessionInboxBroker.getInstance().deleteMessages(sessionId, secretKey, distinctIds);
        return deleteResultMessage(deleted, distinctIds.size()) + owedDeletionWarning(owedAmongRequested);
    }

    /**
     * Warns when a delete silently discarded an unanswered reply obligation: deleting a message does not mark it
     * replied or notify its sender, so without this the sender is left waiting on a message that no longer exists to
     * answer.
     */
    private static String owedDeletionWarning(List<AiInboxMessage> owed) {
        if (owed.isEmpty()) {
            return "";
        }
        String entries = owed.stream()
                .map(m -> "id=" + m.id() + " from " + senderName(m.fromSessionId()) + " \""
                        + (m.subject() != null && !m.subject().isBlank() ? m.subject() : "(no subject)") + "\"")
                .collect(Collectors.joining("; "));
        return "\nWarning: you deleted " + owed.size()
                + " message(s) that asked for a reply and were never answered — they are not marked replied and "
                + "their sender(s) will not be told: " + entries;
    }

    /**
     * The sender's display name, falling back to its session id when that session has since closed — same resolution
     * and fallback ContextProvider.senderName() uses for the equivalent case.
     */
    private static String senderName(String sessionId) {
        var abs = SessionRegistry.get(sessionId);
        return abs != null ? abs.getAiSession().name() : sessionId;
    }

    /**
     * Says when requested IDs matched nothing. Live v1.4.15: a wrong ID returned "Deleted 0 message(s)." as a success,
     * while ReadAiMessage reports the same ID as an error.
     */
    static String deleteResultMessage(int deleted, int requested) {
        if (deleted >= requested) {
            return "Deleted " + deleted + " message(s).";
        }
        String unmatched = (requested - deleted) + " ID(s) matched no message — the ID is incorrect or the message has "
                + "expired or was already deleted.";
        return deleted == 0
               ? "Error: nothing deleted. " + unmatched
               : "Deleted " + deleted + " of " + requested + " message(s). " + unmatched;
    }
}

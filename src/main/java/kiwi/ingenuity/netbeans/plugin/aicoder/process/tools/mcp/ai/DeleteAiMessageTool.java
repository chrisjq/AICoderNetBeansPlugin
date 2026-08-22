package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
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
                "Delete one or more inbox messages by id. Pass " + DeleteAiMessageParamEnum.MESSAGE_ID.key() + " for a single message or " + DeleteAiMessageParamEnum.MESSAGE_IDS.key() + " array for bulk delete. At least one of " + DeleteAiMessageParamEnum.MESSAGE_ID.key() + " or " + DeleteAiMessageParamEnum.MESSAGE_IDS.key() + " is required; if both are supplied they are combined.");
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
            sid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your own session ID (from your session identity block).");
            props.add(DeleteAiMessageParamEnum.SESSION_ID.key(), sid);
            JsonObject sk = new JsonObject();
            sk.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            sk.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your secret key (from your session identity block). Authenticates that you own this session. Retain this value for the entire session — it does not change unless a new identity block is explicitly sent.");
            props.add(DeleteAiMessageParamEnum.SECRET_KEY.key(), sk);
            required.add(DeleteAiMessageParamEnum.SESSION_ID.key());
            required.add(DeleteAiMessageParamEnum.SECRET_KEY.key());
        }
        JsonObject mid = new JsonObject();
        mid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        mid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "A single message ID to delete.");
        props.add(DeleteAiMessageParamEnum.MESSAGE_ID.key(), mid);
        JsonObject mids = new JsonObject();
        mids.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject items = new JsonObject();
        items.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        mids.add(ToolSchemaKeyEnum.ITEMS.key(), items);
        mids.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "A list of message IDs to delete.");
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
        int deleted = AiSessionInboxBroker.getInstance().deleteMessages(sessionId, secretKey, ids);
        return "Deleted " + deleted + " message(s).";
    }
}

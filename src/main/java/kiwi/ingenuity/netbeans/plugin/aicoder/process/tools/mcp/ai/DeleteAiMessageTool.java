package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

public class DeleteAiMessageTool extends AbstractActionTool {

    public DeleteAiMessageTool() {
        super(McpSectionEnum.PLUGIN,
                McpToolEnum.DELETE_AI_MESSAGE.toolName(),
                "Delete one or more inbox messages by id. Pass messageId for a single message or messageIds array for bulk delete. Exactly one of messageId or messageIds must be provided.",
                "DeleteAiMessage -> delete one or more inbox messages once processed; pass messageIds array for bulk delete");
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.DELETE_AI_MESSAGE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Delete one or more inbox messages by id. Pass messageId for a single message or messageIds array for bulk delete. Exactly one of messageId or messageIds must be provided.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject sid = new JsonObject();
        sid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        sid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your own session ID (from your session identity block).");
        props.add(DeleteAiMessageParamEnum.SESSION_ID.key(), sid);
        JsonObject sk = new JsonObject();
        sk.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        sk.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your secret key (from your session identity block). Authenticates that you own this session. Retain this value for the entire session — it does not change unless a new identity block is explicitly sent.");
        props.add(DeleteAiMessageParamEnum.SECRET_KEY.key(), sk);
        JsonObject mid = new JsonObject();
        mid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        mid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "A single message ID to delete.");
        props.add(DeleteAiMessageParamEnum.MESSAGE_ID.key(), mid);
        JsonObject mids = new JsonObject();
        mids.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject items = new JsonObject();
        items.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        mids.add("items", items);
        mids.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "A list of message IDs to delete.");
        props.add(DeleteAiMessageParamEnum.MESSAGE_IDS.key(), mids);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(DeleteAiMessageParamEnum.SESSION_ID.key());
        required.add(DeleteAiMessageParamEnum.SECRET_KEY.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String sessionId = args.str(DeleteAiMessageParamEnum.SESSION_ID.key());
        if (sessionId == null) {
            return "Error: sessionId is required";
        }
        String secretKey = args.str(DeleteAiMessageParamEnum.SECRET_KEY.key());
        if (secretKey == null) {
            return "Error: secretKey is required";
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
            return "Error: provide messageId or a non-empty messageIds array";
        }
        if (!AiSessionInboxBroker.getInstance().validateSecret(sessionId, secretKey)) {
            return "Error: authentication failed — check that sessionId and secretKey match your session identity";
        }
        int deleted = AiSessionInboxBroker.getInstance().deleteMessages(sessionId, secretKey, ids);
        return "Deleted " + deleted + " message(s).";
    }
}

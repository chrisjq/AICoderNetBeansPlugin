package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class UpdateSessionDescriptionTool extends AbstractActionTool {

    public UpdateSessionDescriptionTool() {
        super(McpSectionEnum.PLUGIN,
                McpToolEnum.UPDATE_SESSION_DESCRIPTION.toolName(),
                "Update your session's description visible to peer sessions. Pass your sessionId and secretKey from your session identity.",
                "UpdateSessionDescription -> call at session start to identify your role to peer sessions (visible in ListAiSessions)");
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.UPDATE_SESSION_DESCRIPTION.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Update your session's description visible to peer sessions. Pass your sessionId and secretKey from your session identity.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject sid = new JsonObject();
        sid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        sid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your own session ID (from your session identity block).");
        props.add(UpdateSessionDescriptionParamEnum.SESSION_ID.key(), sid);
        JsonObject sk = new JsonObject();
        sk.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        sk.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your secret key (from your session identity block). Authenticates that you own this session. Retain this value for the entire session — it does not change unless a new identity block is explicitly sent.");
        props.add(UpdateSessionDescriptionParamEnum.SECRET_KEY.key(), sk);
        JsonObject desc = new JsonObject();
        desc.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        desc.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The new description to set for your session (visible to other AI sessions via ListAiSessions).");
        props.add(UpdateSessionDescriptionParamEnum.DESCRIPTION.key(), desc);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(UpdateSessionDescriptionParamEnum.SESSION_ID.key());
        required.add(UpdateSessionDescriptionParamEnum.SECRET_KEY.key());
        required.add(UpdateSessionDescriptionParamEnum.DESCRIPTION.key());
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
        String sessionId = args.str(UpdateSessionDescriptionParamEnum.SESSION_ID.key());
        if (sessionId == null) {
            return "Error: sessionId is required (pass your own session ID from the session identity block)";
        }
        String secretKey = args.str(UpdateSessionDescriptionParamEnum.SECRET_KEY.key());
        if (secretKey == null) {
            return "Error: secretKey is required (pass your secret key from the session identity block)";
        }
        String description = args.str(UpdateSessionDescriptionParamEnum.DESCRIPTION.key());
        if (description == null) {
            return "Error: description is required";
        }
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        if (!broker.validateSecret(sessionId, secretKey)) {
            return "Error: authentication failed — check that sessionId and secretKey match your session identity";
        }
        broker.updateDescription(sessionId, description, secretKey);
        return "Description updated.";
    }
}

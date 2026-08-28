package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

public class UpdateSessionDescriptionTool extends AbstractActionTool {

    public UpdateSessionDescriptionTool() {
        super(McpSectionEnum.PLUGIN,
                McpToolEnum.UPDATE_SESSION_DESCRIPTION.toolName(),
                "Update the session description visible to peer sessions.",
                McpToolEnum.UPDATE_SESSION_DESCRIPTION.toolName() + " -> call at session start to identify your role to peer sessions (visible in " + McpToolEnum.LIST_AI_SESSIONS.toolName() + ")");
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (options.contains(McpInstructionOptionEnum.SOFTEN_TOOL_DIRECTIVES)
                && options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            // "call at session start" makes literal-minded models fire this on
            // the user's first message, whatever the message actually was.
            return McpToolEnum.UPDATE_SESSION_DESCRIPTION.toolName() + " - set your session's description so peer sessions can see your role";
        }
        return super.instruction(options);
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.UPDATE_SESSION_DESCRIPTION.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                options.contains(McpInstructionOptionEnum.CREDENTIALS)
                ? "Update the session description visible to peer sessions."
                : "Update your session's description visible to peer sessions.");
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
            props.add(UpdateSessionDescriptionParamEnum.SESSION_ID.key(), sid);
            JsonObject sk = new JsonObject();
            sk.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            sk.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your secret key from the session identity block.");
            props.add(UpdateSessionDescriptionParamEnum.SECRET_KEY.key(), sk);
            required.add(UpdateSessionDescriptionParamEnum.SESSION_ID.key());
            required.add(UpdateSessionDescriptionParamEnum.SECRET_KEY.key());
        }
        JsonObject desc = new JsonObject();
        desc.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        desc.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Required description visible to peers via " + McpToolEnum.LIST_AI_SESSIONS.toolName() + ".");
        props.add(UpdateSessionDescriptionParamEnum.DESCRIPTION.key(), desc);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        required.add(UpdateSessionDescriptionParamEnum.DESCRIPTION.key());
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
        String sessionId = args.str(UpdateSessionDescriptionParamEnum.SESSION_ID.key());
        if (sessionId == null) {
            return "Error: " + UpdateSessionDescriptionParamEnum.SESSION_ID.key() + " is required (pass your own session ID from the session identity block)";
        }
        String secretKey = args.str(UpdateSessionDescriptionParamEnum.SECRET_KEY.key());
        if (secretKey == null) {
            return "Error: " + UpdateSessionDescriptionParamEnum.SECRET_KEY.key() + " is required (pass your secret key from the session identity block)";
        }
        String description = args.str(UpdateSessionDescriptionParamEnum.DESCRIPTION.key());
        if (description == null) {
            return "Error: " + UpdateSessionDescriptionParamEnum.DESCRIPTION.key() + " is required";
        }
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        if (!broker.validateSecret(sessionId, secretKey)) {
            return "Error: authentication failed — check that " + UpdateSessionDescriptionParamEnum.SESSION_ID.key() + " and " + UpdateSessionDescriptionParamEnum.SECRET_KEY.key() + " match your session identity";
        }
        broker.updateDescription(sessionId, description, secretKey);
        // Echo the stored value. The identity block is built once per turn, so a
        // caller that only sees "Description updated." cannot observe its own
        // change and may conclude the write failed and retry with a new value.
        return "Session description is now: " + description
                + "\nThis is already in effect — do not call this tool again this turn.";
    }
}

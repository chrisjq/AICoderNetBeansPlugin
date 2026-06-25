package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiInboxMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

public class GetAiMessagesTool extends AbstractActionTool {

    public GetAiMessagesTool() {
        super(McpSectionEnum.PLUGIN,
                McpToolEnum.GET_AI_MESSAGES.toolName(),
                "List inbox messages (summaries only — id, subject, from). Non-destructive. Use ReadAiMessage to fetch the full body of a specific message.",
                "GetAiMessages -> list inbox summaries; call at session start and after interrupts to check for messages");
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GET_AI_MESSAGES.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "List inbox messages (summaries only — id, subject, from). Non-destructive. Use ReadAiMessage to fetch the full body of a specific message.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject sid = new JsonObject();
        sid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        sid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your own session ID (from your session identity block).");
        props.add(GetAiMessagesParamEnum.SESSION_ID.key(), sid);
        JsonObject sk = new JsonObject();
        sk.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        sk.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your secret key (from your session identity block). Authenticates that you own this session. Retain this value for the entire session — it does not change unless a new identity block is explicitly sent.");
        props.add(GetAiMessagesParamEnum.SECRET_KEY.key(), sk);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(GetAiMessagesParamEnum.SESSION_ID.key());
        required.add(GetAiMessagesParamEnum.SECRET_KEY.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String sessionId = args.str(GetAiMessagesParamEnum.SESSION_ID.key());
        if (sessionId == null) {
            return "Error: sessionId is required (pass your own session ID from the session identity block)";
        }
        String secretKey = args.str(GetAiMessagesParamEnum.SECRET_KEY.key());
        if (secretKey == null) {
            return "Error: secretKey is required (pass your secret key from the session identity block)";
        }
        List<AiInboxMessage> messages = AiSessionInboxBroker.getInstance().listInbox(sessionId, secretKey);
        if (messages == null) {
            return "Error: authentication failed — check that sessionId and secretKey match your session identity";
        }
        if (messages.isEmpty()) {
            return "Server time: " + Instant.now() + "\nInbox is empty.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Server time: ").append(Instant.now()).append("\n");
        sb.append(messages.size()).append(" message(s):\n\n");
        for (AiInboxMessage msg : messages) {
            sb.append(msg.formatSummary()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }
}

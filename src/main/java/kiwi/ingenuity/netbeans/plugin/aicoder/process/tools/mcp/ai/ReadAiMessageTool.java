package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiInboxMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

public class ReadAiMessageTool extends AbstractActionTool {

    public ReadAiMessageTool() {
        super(McpSectionEnum.PLUGIN,
                McpToolEnum.READ_AI_MESSAGE.toolName(),
                "Read the full body of a specific inbox message by ID and mark it read. The message stays in your inbox until you " + McpToolEnum.DELETE_AI_MESSAGE.toolName() + " it or it expires.",
                McpToolEnum.READ_AI_MESSAGE.toolName() + " -> read full body of an inbox message by ID (message stays in inbox until deleted)");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.READ_AI_MESSAGE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Read the full body of a specific inbox message by ID and mark it read. The message stays in your inbox until you DeleteAiMessage it or it expires.");
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
            props.add(ReadAiMessageParamEnum.SESSION_ID.key(), sid);
            JsonObject sk = new JsonObject();
            sk.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            sk.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your secret key (from your session identity block). Authenticates that you own this session. Retain this value for the entire session — it does not change unless a new identity block is explicitly sent.");
            props.add(ReadAiMessageParamEnum.SECRET_KEY.key(), sk);
            required.add(ReadAiMessageParamEnum.SESSION_ID.key());
            required.add(ReadAiMessageParamEnum.SECRET_KEY.key());
        }
        JsonObject mid = new JsonObject();
        mid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        mid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The message ID to read (from " + McpToolEnum.GET_AI_MESSAGES.toolName() + ").");
        props.add(ReadAiMessageParamEnum.MESSAGE_ID.key(), mid);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        required.add(ReadAiMessageParamEnum.MESSAGE_ID.key());
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
        String sessionId = args.str(ReadAiMessageParamEnum.SESSION_ID.key());
        if (sessionId == null) {
            return "Error: " + ReadAiMessageParamEnum.SESSION_ID.key() + " is required";
        }
        String secretKey = args.str(ReadAiMessageParamEnum.SECRET_KEY.key());
        if (secretKey == null) {
            return "Error: " + ReadAiMessageParamEnum.SECRET_KEY.key() + " is required";
        }
        String messageId = args.str(ReadAiMessageParamEnum.MESSAGE_ID.key());
        if (messageId == null) {
            return "Error: " + ReadAiMessageParamEnum.MESSAGE_ID.key() + " is required";
        }
        AiInboxMessage msg = AiSessionInboxBroker.getInstance().readMessage(sessionId, secretKey, messageId);
        if (msg == null) {
            return "Error: message not found — ID is incorrect or the message has expired/been deleted";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Server time: ").append(Instant.now()).append("\n");
        sb.append(msg.formatSummary()).append("\n\n").append(msg.body());
        return sb.toString();
    }
}

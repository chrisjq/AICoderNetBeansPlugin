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

public class MarkAiMessageRepliedTool extends AbstractActionTool {

    public MarkAiMessageRepliedTool() {
        super(McpSectionEnum.PLUGIN,
              McpToolEnum.MARK_AI_MESSAGE_REPLIED.toolName(),
              "Mark a message that expected a reply as answered when you replied some other way (a message without replyToMessageId, a commit, a build, or a message to a third session). Stops its sender getting a false no-reply notice.",
              McpToolEnum.MARK_AI_MESSAGE_REPLIED.toolName() + " -> mark an expected-reply message as answered after replying another way");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.MARK_AI_MESSAGE_REPLIED.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Mark a message that expected a reply as answered when you replied some other way (a message without replyToMessageId, a commit, a build, or a message to a third session). Stops its sender getting a false no-reply notice.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        if (options.contains(McpInstructionOptionEnum.CREDENTIALS)) {
            JsonObject sid = new JsonObject();
            sid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            sid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your own session ID from the session identity block.");
            props.add(MarkAiMessageRepliedParamEnum.SESSION_ID.key(), sid);
            JsonObject sk = new JsonObject();
            sk.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            sk.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your secret key from the session identity block.");
            props.add(MarkAiMessageRepliedParamEnum.SECRET_KEY.key(), sk);
            required.add(MarkAiMessageRepliedParamEnum.SESSION_ID.key());
            required.add(MarkAiMessageRepliedParamEnum.SECRET_KEY.key());
        }
        JsonObject mid = new JsonObject();
        mid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        mid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Message ID from " + McpToolEnum.GET_AI_MESSAGES.toolName() + ".");
        props.add(MarkAiMessageRepliedParamEnum.MESSAGE_ID.key(), mid);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        required.add(MarkAiMessageRepliedParamEnum.MESSAGE_ID.key());
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
        // in-memory broker state with its own synchronisation
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String sessionId = args.str(MarkAiMessageRepliedParamEnum.SESSION_ID.key());
        if (sessionId == null) {
            return "Error: " + MarkAiMessageRepliedParamEnum.SESSION_ID.key() + " is required";
        }
        String secretKey = args.str(MarkAiMessageRepliedParamEnum.SECRET_KEY.key());
        if (secretKey == null) {
            return "Error: " + MarkAiMessageRepliedParamEnum.SECRET_KEY.key() + " is required";
        }
        String messageTypeError = args.requireStringIfPresent(MarkAiMessageRepliedParamEnum.MESSAGE_ID.key());
        if (messageTypeError != null) {
            return "Error: " + messageTypeError;
        }
        String messageId = args.str(MarkAiMessageRepliedParamEnum.MESSAGE_ID.key());
        if (messageId == null || messageId.isBlank()) {
            return "Error: messageId is required";
        }
        if (!AiSessionInboxBroker.getInstance().validateSecret(sessionId, secretKey)) {
            return "Error: authentication failed — check that " + MarkAiMessageRepliedParamEnum.SESSION_ID.key()
                    + " and " + MarkAiMessageRepliedParamEnum.SECRET_KEY.key() + " match your session identity";
        }
        AiSessionInboxBroker.MarkRepliedResultEnum result = AiSessionInboxBroker.getInstance()
                .markReplied(sessionId, messageId);
        return switch (result) {
            case MARKED ->
                "Message " + messageId + " marked replied.";
            case ALREADY_REPLIED ->
                "Message " + messageId + " was already marked replied.";
            case NOT_EXPECTING_REPLY ->
                "Message " + messageId + " did not ask for a reply; nothing to mark.";
            case NOT_FOUND ->
                "Error: no message " + messageId + " in your inbox.";
        };
    }
}

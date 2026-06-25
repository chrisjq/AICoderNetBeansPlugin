package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiInboxMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

public class SendAiMessageTool extends AbstractActionTool {

    public SendAiMessageTool() {
        super(McpSectionEnum.PLUGIN,
                McpToolEnum.SEND_AI_MESSAGE.toolName(),
                "Send a message to another AI session's inbox. Use ListAiSessions to find peer sessionIds.",
                "SendAiMessage -> send to a peer AI session's inbox; use expectsReply+replyImportant to be interrupted when they reply");
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.SEND_AI_MESSAGE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Send a message to another AI session's inbox. Use ListAiSessions to find peer sessionIds.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();

        JsonObject tid = new JsonObject();
        tid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        tid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The sessionId of the target AI session to send the message to (from ListAiSessions).");
        props.add(SendAiMessageParamEnum.TARGET_SESSION_ID.key(), tid);

        JsonObject subj = new JsonObject();
        subj.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        subj.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Short subject line (max " + AiInboxMessage.MAX_SUBJECT_LENGTH + " chars) summarising the message.");
        props.add(SendAiMessageParamEnum.SUBJECT.key(), subj);

        JsonObject msg = new JsonObject();
        msg.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        msg.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The full message body to deliver.");
        props.add(SendAiMessageParamEnum.MESSAGE.key(), msg);

        JsonObject replyTo = new JsonObject();
        replyTo.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        replyTo.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Optional ID of a message this is replying to.");
        props.add(SendAiMessageParamEnum.REPLY_TO_MESSAGE_ID.key(), replyTo);

        JsonObject important = new JsonObject();
        important.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        important.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "If true and the target session supports it, sends a graceful interrupt to the target so it can read this message sooner. Only set this after calling GetPluginVersion to confirm both sessions are on the same plugin version — older versions do not support graceful interrupt. Also requires allowImportantMessages to be enabled on the target session.");
        props.add(SendAiMessageParamEnum.IMPORTANT.key(), important);

        JsonObject expectsReply = new JsonObject();
        expectsReply.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        expectsReply.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "If true, the broker tracks that you expect a reply. If the recipient exits without replying, you will automatically receive a notification. Combine with replyImportant=true if you want to be interrupted when the reply (or the no-reply notification) arrives.");
        props.add(SendAiMessageParamEnum.EXPECTS_REPLY.key(), expectsReply);

        JsonObject replyImportant = new JsonObject();
        replyImportant.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        replyImportant.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Only meaningful when expectsReply=true. If true, any reply to this message is automatically marked important so you receive a graceful interrupt when it arrives — you do not need to set important=true on the reply yourself. The automatic no-reply notification (sent when the recipient exits without responding) is also marked important and will interrupt you.");
        props.add(SendAiMessageParamEnum.REPLY_IMPORTANT.key(), replyImportant);

        JsonObject sessionId = new JsonObject();
        sessionId.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        sessionId.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your sessionId for authentication (from your session identity block).");
        props.add(SendAiMessageParamEnum.SESSION_ID.key(), sessionId);

        JsonObject secretKey = new JsonObject();
        secretKey.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        secretKey.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your secret key for authentication (from your session identity block). Keep this secret.");
        props.add(SendAiMessageParamEnum.SECRET_KEY.key(), secretKey);

        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(SendAiMessageParamEnum.SESSION_ID.key());
        required.add(SendAiMessageParamEnum.SECRET_KEY.key());
        required.add(SendAiMessageParamEnum.TARGET_SESSION_ID.key());
        required.add(SendAiMessageParamEnum.SUBJECT.key());
        required.add(SendAiMessageParamEnum.MESSAGE.key());
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
        String senderId = args.str(SendAiMessageParamEnum.SESSION_ID.key());
        String secretKey = args.str(SendAiMessageParamEnum.SECRET_KEY.key());
        if (senderId == null || senderId.isBlank()) {
            return "Error: sessionId is required";
        }
        if (secretKey == null || secretKey.isBlank()) {
            return "Error: secretKey is required";
        }
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        if (!broker.validateSecret(senderId, secretKey)) {
            return "Error: authentication failed for session '" + senderId + "'";
        }
        String targetSessionId = args.str(SendAiMessageParamEnum.TARGET_SESSION_ID.key());
        String subject = args.str(SendAiMessageParamEnum.SUBJECT.key());
        String message = args.str(SendAiMessageParamEnum.MESSAGE.key());
        if (targetSessionId == null || targetSessionId.isBlank()) {
            return "Error: targetSessionId is required";
        }
        if (subject == null || subject.isBlank()) {
            return "Error: subject is required";
        }
        if (subject.length() > AiInboxMessage.MAX_SUBJECT_LENGTH) {
            return "Error: subject exceeds maximum length of " + AiInboxMessage.MAX_SUBJECT_LENGTH + " characters";
        }
        if (message == null || message.isBlank()) {
            return "Error: message is required";
        }
        if (message.length() > AiInboxMessage.MAX_MESSAGE_LENGTH) {
            return "Error: message body exceeds maximum length of " + AiInboxMessage.MAX_MESSAGE_LENGTH + " characters";
        }
        if (broker.isActive(senderId) && !broker.isInterAiCommsAllowed(senderId)) {
            return "Error: inter-AI communication is disabled for this session";
        }
        if (!broker.isActive(targetSessionId)) {
            return "Error: session '" + targetSessionId + "' is not active";
        }
        if (!broker.isInterAiCommsAllowed(targetSessionId)) {
            return "Error: inter-AI communication is disabled for session '" + targetSessionId + "'";
        }
        String replyToMessageId = args.str(SendAiMessageParamEnum.REPLY_TO_MESSAGE_ID.key());
        boolean important = args.bool(SendAiMessageParamEnum.IMPORTANT.key());
        boolean expectsReply = args.bool(SendAiMessageParamEnum.EXPECTS_REPLY.key());
        boolean replyImportant = args.bool(SendAiMessageParamEnum.REPLY_IMPORTANT.key());
        boolean targetRunning = broker.isSessionRunning(targetSessionId);
        boolean targetAllowsImportant = broker.isImportantMessagesAllowed(targetSessionId);
        String messageId = broker.sendMessage(senderId, targetSessionId, subject, message,
                replyToMessageId, important, expectsReply, replyImportant);
        if (messageId == null) {
            return "Error: session '" + targetSessionId + "' is not active";
        }
        String result = "Message sent to session " + targetSessionId + " (id=" + messageId + ")";
        if (targetRunning) {
            result += " — WARNING: target session is currently processing; message will not be read until its current turn completes";
        }
        if (important && !targetAllowsImportant) {
            result += " — NOTE: important flag ignored; target session has allowImportantMessages disabled";
        }
        return result;
    }
}

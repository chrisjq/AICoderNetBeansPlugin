package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
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

public class SendAiMessageTool extends AbstractActionTool {

    public SendAiMessageTool() {
        super(McpSectionEnum.PLUGIN,
              McpToolEnum.SEND_AI_MESSAGE.toolName(),
              "Send a message to another AI session's inbox. Use " + McpToolEnum.LIST_AI_SESSIONS.toolName() + " to find peer sessionIds.",
              McpToolEnum.SEND_AI_MESSAGE.toolName() + " -> send to a peer AI session's inbox; use " + SendAiMessageParamEnum.EXPECTS_REPLY.key() + "+" + SendAiMessageParamEnum.REPLY_IMPORTANT.key() + " to be interrupted when they reply");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.SEND_AI_MESSAGE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Send a message to another AI session. Use " + McpToolEnum.LIST_AI_SESSIONS.toolName() + " to find the target session ID.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();

        JsonObject tid = new JsonObject();
        tid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        tid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Required target session ID from " + McpToolEnum.LIST_AI_SESSIONS.toolName() + " (not your own).");
        props.add(SendAiMessageParamEnum.TARGET_SESSION_ID.key(), tid);

        JsonObject subj = new JsonObject();
        subj.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        subj.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Short subject line (max "
                         + AiInboxMessage.MAX_SUBJECT_LENGTH + " chars). The recipient sees only this, not the body, "
                         + "when the message is delivered — make it state what you want done.");
        props.add(SendAiMessageParamEnum.SUBJECT.key(), subj);

        JsonObject msg = new JsonObject();
        msg.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        msg.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The full message body to deliver.");
        props.add(SendAiMessageParamEnum.MESSAGE.key(), msg);

        JsonObject replyTo = new JsonObject();
        replyTo.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        replyTo.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "ID of the message you are answering (the id= UUID from GetAiMessages/ReadAiMessage — not a <SYSTEM:…> block tag). Setting it marks that message replied; leaving it out when answering a message that expects a reply means its sender eventually gets a false no-reply notice. An ID that matches no message in your inbox is refused and nothing is sent.");
        props.add(SendAiMessageParamEnum.REPLY_TO_MESSAGE_ID.key(), replyTo);

        JsonObject important = new JsonObject();
        important.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        important.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "If true, request prompt delivery; it interrupts a running target only when mailDelivery supports mid-turn delivery and the target allows important messages. Check mailDelivery in " + McpToolEnum.LIST_AI_SESSIONS.toolName() + " first.");
        props.add(SendAiMessageParamEnum.IMPORTANT.key(), important);

        JsonObject expectsReply = new JsonObject();
        expectsReply.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        expectsReply.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "If true, notify you if the recipient exits without replying.");
        props.add(SendAiMessageParamEnum.EXPECTS_REPLY.key(), expectsReply);

        JsonObject replyImportant = new JsonObject();
        replyImportant.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        replyImportant.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "If true with " + SendAiMessageParamEnum.EXPECTS_REPLY.key() + ", interrupt you when a reply or no-reply notification arrives.");
        props.add(SendAiMessageParamEnum.REPLY_IMPORTANT.key(), replyImportant);

        JsonArray required = new JsonArray();
        // Caller credentials are declared here rather than by
        // applyCredentialsIfRequested so they can carry richer descriptions.
        // Callers without CREDENTIALS reach this tool through a bridge that
        // injects both values server-side, so they must not be asked for them.
        // targetSessionId below is a real argument and is always declared.
        if (options.contains(McpInstructionOptionEnum.CREDENTIALS)) {
            JsonObject sessionId = new JsonObject();
            sessionId.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            sessionId.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your own session ID from the session identity block.");
            props.add(SendAiMessageParamEnum.SESSION_ID.key(), sessionId);

            JsonObject secretKey = new JsonObject();
            secretKey.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            secretKey.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your secret key from the session identity block.");
            props.add(SendAiMessageParamEnum.SECRET_KEY.key(), secretKey);

            required.add(SendAiMessageParamEnum.SESSION_ID.key());
            required.add(SendAiMessageParamEnum.SECRET_KEY.key());
        }

        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        required.add(SendAiMessageParamEnum.TARGET_SESSION_ID.key());
        required.add(SendAiMessageParamEnum.SUBJECT.key());
        required.add(SendAiMessageParamEnum.MESSAGE.key());
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
        String senderId = args.str(SendAiMessageParamEnum.SESSION_ID.key());
        String secretKey = args.str(SendAiMessageParamEnum.SECRET_KEY.key());
        if (senderId == null || senderId.isBlank()) {
            return "Error: " + SendAiMessageParamEnum.SESSION_ID.key() + " is required";
        }
        if (secretKey == null || secretKey.isBlank()) {
            return "Error: " + SendAiMessageParamEnum.SECRET_KEY.key() + " is required";
        }
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        if (!broker.validateSecret(senderId, secretKey)) {
            return "Error: authentication failed for session '" + senderId + "'";
        }
        String targetSessionId = args.str(SendAiMessageParamEnum.TARGET_SESSION_ID.key());
        String subject = args.str(SendAiMessageParamEnum.SUBJECT.key());
        String message = args.str(SendAiMessageParamEnum.MESSAGE.key());
        if (targetSessionId == null || targetSessionId.isBlank()) {
            return "Error: " + SendAiMessageParamEnum.TARGET_SESSION_ID.key() + " is required";
        }
        // A self-message is never intentional — it is an AI picking its own id off the session list. Refused before
        // anything is written so it cannot leave an inbox entry or a pending-reply expectation against itself.
        if (senderId.equals(targetSessionId)) {
            return "Error: cannot send a message to your own session '" + senderId + "'. Call "
                    + McpToolEnum.LIST_AI_SESSIONS.toolName() + " and pick a different "
                    + SendAiMessageParamEnum.TARGET_SESSION_ID.key() + ".";
        }
        if (subject == null || subject.isBlank()) {
            return "Error: " + SendAiMessageParamEnum.SUBJECT.key() + " is required";
        }
        if (subject.length() > AiInboxMessage.MAX_SUBJECT_LENGTH) {
            return "Error: subject exceeds maximum length of " + AiInboxMessage.MAX_SUBJECT_LENGTH + " characters";
        }
        if (message == null || message.isBlank()) {
            return "Error: " + SendAiMessageParamEnum.MESSAGE.key() + " is required";
        }
        if (message.length() > AiInboxMessage.MAX_MESSAGE_LENGTH) {
            return "Error: message body exceeds maximum length of " + AiInboxMessage.MAX_MESSAGE_LENGTH + " characters";
        }
        if (broker.isActive(senderId) && !broker.isInterAiCommsAllowed(senderId)) {
            return "Error: inter-AI communication is disabled for this session";
        }
        if (!broker.isActive(targetSessionId)) {
            // Unlike the sender's credentials, which the MCP server has already
            // validated before this tool runs, the target ID is an ordinary
            // argument nothing has checked. A mistyped one and a genuinely
            // stopped peer both land here needing opposite advice, and the old
            // shared "is not active" gave the mistyped case the wrong one.
            if (!broker.isKnownSession(targetSessionId)) {
                return "Error: no AI session has the ID '" + targetSessionId + "'. Call "
                        + McpToolEnum.LIST_AI_SESSIONS.toolName()
                        + " and copy the recipient's " + SendAiMessageParamEnum.TARGET_SESSION_ID.key()
                        + " verbatim — session IDs are full UUIDs and must match character for character.";
            }
            return "Error: session '" + targetSessionId + "' is not active";
        }
        if (!broker.isInterAiCommsAllowed(targetSessionId)) {
            return "Error: inter-AI communication is disabled for session '" + targetSessionId + "'";
        }
        String replyToMessageId = args.str(SendAiMessageParamEnum.REPLY_TO_MESSAGE_ID.key());
        if (replyToMessageId != null && !replyToMessageId.isBlank()) {
            String refusal = broker.validateReplyTo(senderId, replyToMessageId);
            if (refusal != null) {
                return "Error: " + refusal;
            }
        }
        boolean important = args.bool(SendAiMessageParamEnum.IMPORTANT.key());
        boolean expectsReply = args.bool(SendAiMessageParamEnum.EXPECTS_REPLY.key());
        // Dropped unless expectsReply is set, matching the schema's "Only meaningful when expectsReply=true".
        // The broker only creates the pending-reply bookkeeping this flag rides on when a reply is expected, so
        // carrying it alone would set a flag that nothing could ever act on.
        boolean replyImportant = expectsReply
                && args.bool(SendAiMessageParamEnum.REPLY_IMPORTANT.key());
        boolean targetRunning = broker.isSessionRunning(targetSessionId);
        boolean targetAllowsImportant = broker.isImportantMessagesAllowed(targetSessionId);
        String messageId = broker.sendMessage(senderId, targetSessionId, subject, message,
                                              replyToMessageId, important, expectsReply, replyImportant);
        if (messageId == null) {
            return "Error: session '" + targetSessionId + "' is not active";
        }
        boolean tryInterruptEnabled = targetAllowsImportant && important;
        String result = "Message sent to session " + targetSessionId + " (id=" + messageId + ")";
        if (targetRunning) {
            result += " — WARNING: target session is currently processing";

            if (tryInterruptEnabled) {
                result += ", message will be notified but read by recipient may be delayed.";
            }
            else {
                result += ", message will be delivered when recipient ends current task.";
            }

            if (!targetAllowsImportant) {
                result += " Note: Target currently has mail interruptions disabled.";
            }
        }
        result += owedRepliesBlock(senderId, replyToMessageId);
        return result;
    }

    /**
     * "You still owe replies to:" reminder appended after every successful send, so the reply obligation stays visible
     * right when the sender is already in the mail tool rather than only on the next turn's preamble (see
     * ContextProvider.appendOwedReplies, the equivalent per-turn section). Only messages already read are listed — an
     * unread one has not been seen yet, so surfacing it here would be the same premature-reply nudge
     * NotificationUtilInboxTest guards the auto-delivered notification against. The message just answered by this very
     * call is excluded, though listOwedReplies would already drop it once its respondedAt is set.
     */
    private static String owedRepliesBlock(String senderId, String justAnsweredId) {
        List<AiInboxMessage> owed = AiSessionInboxBroker.getInstance().listOwedReplies(senderId).stream()
                .filter(m -> m.readAt() != null)
                .filter(m -> !m.id().equals(justAnsweredId))
                .toList();
        if (owed.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nYou still owe replies to:\n");
        for (AiInboxMessage m : owed) {
            String subject = m.subject() != null && !m.subject().isBlank() ? m.subject() : "(no subject)";
            sb.append("- id=").append(m.id())
                    .append(" from ").append(senderName(m.fromSessionId()))
                    .append(" \"").append(subject).append("\"")
                    .append(" — reply with replyToMessageId=").append(m.id())
                    .append(", or MarkAiMessageReplied if you answered another way\n");
        }
        return sb.toString();
    }

    /**
     * The sender's display name, falling back to its session id when that session has since closed — same resolution
     * and fallback ContextProvider.senderName() uses for the equivalent case.
     */
    private static String senderName(String sessionId) {
        var abs = SessionRegistry.get(sessionId);
        return abs != null ? abs.getAiSession().name() : sessionId;
    }
}

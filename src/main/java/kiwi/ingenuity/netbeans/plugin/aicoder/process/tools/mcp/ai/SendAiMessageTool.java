package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiInboxMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
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
                McpToolEnum.PEER_MESSAGE_SEND.toolName(),
                "Send a message to the inbox of another AI session open in this IDE — not an internal subagent. Use " + McpToolEnum.PEER_SESSION_LIST.toolName() + " to find peer sessionIds.",
                McpToolEnum.PEER_MESSAGE_SEND.toolName() + " -> send to a peer AI session's inbox; use " + SendAiMessageParamEnum.EXPECTS_REPLY.key() + "+" + SendAiMessageParamEnum.REPLY_IMPORTANT.key() + " to be interrupted when they reply");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.PEER_MESSAGE_SEND.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Send a message to another AI session open in this IDE — not an internal subagent. Use " + McpToolEnum.PEER_SESSION_LIST.toolName()
                                                              + " to find a target session ID or unique name.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();

        JsonObject tid = new JsonObject();
        tid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        tid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Required target session ID or unique session name from "
                                                             + McpToolEnum.PEER_SESSION_LIST.toolName() + " (not your own).");
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
        replyTo.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "ID of a message addressed to you that you are answering (the id= UUID from PeerMessageList/PeerMessageRead). Setting it marks that message replied.");
        props.add(SendAiMessageParamEnum.REPLY_TO_MESSAGE_ID.key(), replyTo);

        JsonObject important = new JsonObject();
        important.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        important.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Request prompt delivery, interrupting if target allows. Check mailDelivery in " + McpToolEnum.PEER_SESSION_LIST.toolName() + " first: where a peer only reads at end of turn, important is ignored for it.");
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
        if (!broker.isKnownSession(targetSessionId)) {
            List<AiSession> nameMatches = broker.findActiveSessionsByName(targetSessionId);
            if (nameMatches.size() == 1) {
                targetSessionId = nameMatches.get(0).id();
            }
            else if (nameMatches.size() > 1) {
                String matches = nameMatches.stream()
                        .map(s -> s.name() + " (" + s.id() + ")")
                        .collect(Collectors.joining(", "));
                return "Error: session name '" + targetSessionId + "' is ambiguous; matching peers: " + matches;
            }
        }
        // A self-message is never intentional — it is an AI picking its own id off the session list. Refused before
        // anything is written so it cannot leave an inbox entry or a pending-reply expectation against itself.
        if (senderId.equals(targetSessionId)) {
            return "Error: cannot send a message to your own session '" + senderId + "'. Call "
                   + McpToolEnum.PEER_SESSION_LIST.toolName() + " and pick a different "
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
                return "Error: no active AI session has the id or name '" + targetSessionId + "'. Call "
                       + McpToolEnum.PEER_SESSION_LIST.toolName() + " and use a listed id or unique name.";
            }
            // A comms-disabled session is never handed an inbox: AiTopComponent registers a session only when
            // effectiveAllowInterAiComms() is on and unregisters it when comms is toggled off, so such a target
            // always arrives here as "not active". isInterAiCommsAllowed reads the registry, not the inbox, so
            // it still answers correctly for it. It must be told the real cause — "is not active" would send the
            // caller back to the session list that hides this very session.
            if (!broker.isInterAiCommsAllowed(targetSessionId)) {
                return "Error: session '" + targetSessionId + "' has inter-AI messaging disabled — enable "
                       + "Allow inter-AI comms in its session settings";
            }
            return "Error: session '" + targetSessionId + "' is not active";
        }
        // Defensive belt-and-braces: unreachable today because inbox registration is keyed on the comms setting
        // (see above). Kept so a future change that decouples registration from the setting cannot silently
        // deliver to a comms-disabled session; same wording as the registry-known refusal above.
        if (!broker.isInterAiCommsAllowed(targetSessionId)) {
            return "Error: session '" + targetSessionId + "' has inter-AI messaging disabled — enable "
                   + "Allow inter-AI comms in its session settings";
        }
        String replyToMessageId = args.str(SendAiMessageParamEnum.REPLY_TO_MESSAGE_ID.key());
        boolean important = args.bool(SendAiMessageParamEnum.IMPORTANT.key());
        boolean expectsReply = args.bool(SendAiMessageParamEnum.EXPECTS_REPLY.key());
        // Dropped unless expectsReply is set, matching the schema's "Only meaningful when expectsReply=true".
        // The broker only creates the pending-reply bookkeeping this flag rides on when a reply is expected, so
        // carrying it alone would set a flag that nothing could ever act on.
        boolean replyImportant = expectsReply
                                 && args.bool(SendAiMessageParamEnum.REPLY_IMPORTANT.key());
        boolean targetRunning = broker.isSessionRunning(targetSessionId);
        boolean targetAllowsImportant = broker.isImportantMessagesAllowed(targetSessionId);
        AiSessionInboxBroker.SendResult sendResult = broker.sendMessageWithResult(
                senderId, targetSessionId, subject, message, replyToMessageId,
                important, expectsReply, replyImportant);
        String messageId = sendResult.messageId();
        if (messageId == null) {
            // The target passed the liveness checks above but vanished before the send completed (a Stop or a
            // session restart in the gap). Distinct from the "not active" refusal up front: that one means the
            // recipient was GONE when we asked; this one means it was HERE and then left mid-send.
            return "Error: session '" + targetSessionId + "' stopped before the message could be delivered — "
                   + "retry once it has finished processing.";
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
        if (sendResult.replyToNote() != null) {
            result += "\nNote: " + sendResult.replyToNote();
        }
        result += owedRepliesBlock(senderId, sendResult.replyToNote() == null ? replyToMessageId : null);
        return result;
    }

    /**
     * "You still owe replies to:" reminder appended after every successful send, so the reply obligation
     * stays visible right when the sender is already in the mail tool rather than only on the next turn's
     * preamble (see ContextProvider.appendOwedReplies, the equivalent per-turn section). Only messages
     * already read are listed — an unread one has not been seen yet, so surfacing it here would be the same
     * premature-reply nudge NotificationUtilInboxTest guards the auto-delivered notification against. The
     * message just answered by this very call is excluded, though listOwedReplies would already drop it once
     * its respondedAt is set.
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
                    .append(", or PeerMessageMarkReplied if you answered another way\n");
        }
        return sb.toString();
    }

    /**
     * The sender's display name, falling back to its session id when that session has since closed — same
     * resolution and fallback ContextProvider.senderName() uses for the equivalent case.
     */
    private static String senderName(String sessionId) {
        var abs = SessionRegistry.get(sessionId);
        return abs != null ? abs.getAiSession().name() : sessionId;
    }

}

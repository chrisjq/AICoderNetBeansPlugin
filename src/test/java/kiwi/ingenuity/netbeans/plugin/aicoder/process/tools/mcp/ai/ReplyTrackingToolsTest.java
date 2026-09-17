package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.AbstractNotification;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSessionCallback;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Covers the F4 reply-tracking behaviour owned by this session: the SendAiMessageTool refusal for an unknown
 * replyToMessageId, the "You still owe replies to:" reminder SendAiMessageTool appends after a successful send, and the
 * DeleteAiMessageTool warning for deleting an unanswered expects-reply message. Setup mirrors InterAiToolHandlersTest's
 * real-session/real-broker pattern rather than mocking, since the behaviour under test lives in how the tools compose
 * real AiSessionInboxBroker state.
 */
class ReplyTrackingToolsTest {

    private static AiSession session(String id, String name, boolean comms) {
        AiSessionSettings settings = new AiSessionSettings(null, null, comms, null, true, null, null, null);
        AiSession result = new AiSession(id, name, null, AiTypeEnum.CLAUDE, null, settings, Instant.now(), Instant.now());
        result.setAiSessionCallback(new AiSessionCallback() {
            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public void requestGracefulInterrupt(InterruptTypeEnum type) {
            }

            @Override
            public void deliverIncomingMessage(String from, AbstractNotification msg) {
            }

            @Override
            public void applyDescriptionUpdate(String desc) {
            }
        });
        AbstractAiSession wrapper = new AbstractAiSession(result) {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getSessionName() {
                return name;
            }

            @Override
            public Map getMcpToolHandlers() {
                return Map.of();
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return null;
            }
        };
        SessionRegistry.register(wrapper);
        AiSessionInboxBroker.getInstance().register(result);
        return result;
    }

    private static ToolRequestArguments sendArgs(AiSession from, AiSession to, String subject, String message,
                                                 String replyToMessageId) {
        JsonObject o = new JsonObject();
        o.addProperty(SendAiMessageParamEnum.SESSION_ID.key(), from.id());
        o.addProperty(SendAiMessageParamEnum.SECRET_KEY.key(), from.secret());
        o.addProperty(SendAiMessageParamEnum.TARGET_SESSION_ID.key(), to.id());
        o.addProperty(SendAiMessageParamEnum.SUBJECT.key(), subject);
        o.addProperty(SendAiMessageParamEnum.MESSAGE.key(), message);
        if (replyToMessageId != null) {
            o.addProperty(SendAiMessageParamEnum.REPLY_TO_MESSAGE_ID.key(), replyToMessageId);
        }
        return new ToolRequestArguments(o);
    }

    // ---- SendAiMessageTool: unknown replyToMessageId ----
    @Test
    void sendWithAnUnknownReplyToMessageIdIsRefusedAndNothingIsDelivered() {
        AiSession sender = session("rtt-refuse-sender", "Sender", true);
        AiSession target = session("rtt-refuse-target", "Target", true);

        String result = new SendAiMessageTool().handle(
                sendArgs(sender, target, "Hello", "body", "does-not-exist-12345"), null);

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("<SYSTEM:"), result);
        assertTrue(AiSessionInboxBroker.getInstance().listInbox(target.id(), target.secret()).isEmpty(),
                   "a refused send must deliver nothing to the target: " + result);
    }

    // ---- SendAiMessageTool: owed-replies block ----
    @Test
    void owedRepliesBlockListsAReadUnansweredMessageButExcludesUnreadAndJustAnswered() {
        AiSession me = session("rtt-owed-me", "Me", true);
        AiSession readPeer = session("rtt-owed-read-peer", "ReadPeer", true);
        AiSession unreadPeer = session("rtt-owed-unread-peer", "UnreadPeer", true);
        AiSession answeredPeer = session("rtt-owed-answered-peer", "AnsweredPeer", true);
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();

        String readOwedId = broker.sendMessage(readPeer.id(), me.id(), "Please review", "body", null,
                                               false, true, false);
        broker.readMessageWithResult(me.id(), me.secret(), readOwedId);

        broker.sendMessage(unreadPeer.id(), me.id(), "Also needs a reply", "body", null, false, true, false);
        // left unread deliberately

        String toBeAnsweredId = broker.sendMessage(answeredPeer.id(), me.id(), "Answering this one now", "body", null,
                                                   false, true, false);
        broker.readMessageWithResult(me.id(), me.secret(), toBeAnsweredId);

        String result = new SendAiMessageTool().handle(
                sendArgs(me, answeredPeer, "Re: Answering this one now", "here you go", toBeAnsweredId), null);

        assertTrue(result.contains("You still owe replies to:"), result);
        assertTrue(result.contains("id=" + readOwedId), result);
        assertTrue(result.contains("Please review"), result);
        assertFalse(result.contains("Also needs a reply"), "unread messages must not be surfaced here: " + result);
        assertFalse(result.contains("id=" + toBeAnsweredId),
                    "the message just answered by this call must be excluded: " + result);
    }

    @Test
    void owedRepliesBlockIsAbsentWhenNothingIsOwed() {
        AiSession me = session("rtt-noowed-me", "Me", true);
        AiSession target = session("rtt-noowed-target", "Target", true);

        String result = new SendAiMessageTool().handle(sendArgs(me, target, "Hi", "body", null), null);

        assertFalse(result.contains("You still owe replies to:"), result);
    }

    // ---- DeleteAiMessageTool: unanswered-reply warning ----
    @Test
    void deleteWarnsWhenAnUnansweredExpectsReplyMessageIsDeletedAndIsSilentOtherwise() {
        AiSession me = session("rtt-del-me", "Me", true);
        AiSession peer = session("rtt-del-peer", "Peer", true);
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();

        String owedId = broker.sendMessage(peer.id(), me.id(), "Needs an answer", "body", null,
                                           false, true, false);
        String fyiId = broker.sendMessage(peer.id(), me.id(), "FYI only", "body", null, false, false, false);

        JsonObject deleteOwed = new JsonObject();
        deleteOwed.addProperty(DeleteAiMessageParamEnum.SESSION_ID.key(), me.id());
        deleteOwed.addProperty(DeleteAiMessageParamEnum.SECRET_KEY.key(), me.secret());
        deleteOwed.addProperty(DeleteAiMessageParamEnum.MESSAGE_ID.key(), owedId);
        String owedResult = new DeleteAiMessageTool().handle(new ToolRequestArguments(deleteOwed), null);

        assertTrue(owedResult.contains("Deleted 1 message(s)."), owedResult);
        assertTrue(owedResult.contains("Warning:"), owedResult);
        assertTrue(owedResult.contains("id=" + owedId), owedResult);
        assertTrue(owedResult.contains("Needs an answer"), owedResult);

        JsonObject deleteFyi = new JsonObject();
        deleteFyi.addProperty(DeleteAiMessageParamEnum.SESSION_ID.key(), me.id());
        deleteFyi.addProperty(DeleteAiMessageParamEnum.SECRET_KEY.key(), me.secret());
        deleteFyi.addProperty(DeleteAiMessageParamEnum.MESSAGE_ID.key(), fyiId);
        String fyiResult = new DeleteAiMessageTool().handle(new ToolRequestArguments(deleteFyi), null);

        assertTrue(fyiResult.contains("Deleted 1 message(s)."), fyiResult);
        assertFalse(fyiResult.contains("Warning:"), fyiResult);
    }

    // ---- global mutation lock / isMutating ----
    @Test
    void sendAndDeleteToolsSkipTheGlobalMutationLockButStillCountAsMutating() {
        SendAiMessageTool send = new SendAiMessageTool();
        DeleteAiMessageTool delete = new DeleteAiMessageTool();

        assertFalse(send.requiresGlobalMutationLock(), "in-memory broker state has its own synchronisation");
        assertTrue(send.isMutating());
        assertFalse(delete.requiresGlobalMutationLock());
        assertTrue(delete.isMutating());
    }
}

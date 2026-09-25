package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiInboxMessage;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai.DeleteAiMessageParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai.DeleteAiMessageTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai.IsAiSessionActiveParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai.IsAiSessionActiveTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai.ListAiSessionsParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai.ListAiSessionsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai.ReadAiMessageParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai.ReadAiMessageTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai.SendAiMessageParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai.SendAiMessageTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai.UpdateSessionDescriptionParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai.UpdateSessionDescriptionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.NotificationUtil;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class InterAiToolHandlersTest {

    private static AiSession session(String id, String name, boolean comms, boolean important, boolean running) {
        AiSessionSettings settings = new AiSessionSettings(null, null, comms, null, important, null, null, null);
        AiSession result = new AiSession(id, name, null, AiTypeEnum.CLAUDE, null, settings, Instant.now(), Instant.now());
        result.setAiSessionCallback(new AiSessionCallback() {
            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public void requestGracefulInterrupt(InterruptTypeEnum type) {
            }

            @Override
            public void deliverIncomingMessage(String from, AbstractNotification msg) {
            }

            @Override
            public void applyDescriptionUpdate(String desc) {
                result.setDescription(desc);
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
        return result;
    }

    private static ToolRequestArguments args(String... values) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < values.length; i += 2) {
            object.addProperty(values[i], values[i + 1]);
        }
        return new ToolRequestArguments(object);
    }

    private static ToolRequestArguments sendArgs(AiSession sender, String target, String subject, String body,
            String replyTo, boolean important, boolean expectsReply, boolean replyImportant) {
        JsonObject object = new JsonObject();
        object.addProperty(SendAiMessageParamEnum.SESSION_ID.key(), sender.id());
        object.addProperty(SendAiMessageParamEnum.SECRET_KEY.key(), sender.secret());
        object.addProperty(SendAiMessageParamEnum.TARGET_SESSION_ID.key(), target);
        object.addProperty(SendAiMessageParamEnum.SUBJECT.key(), subject);
        object.addProperty(SendAiMessageParamEnum.MESSAGE.key(), body);
        if (replyTo != null) {
            object.addProperty(SendAiMessageParamEnum.REPLY_TO_MESSAGE_ID.key(), replyTo);
        }
        object.addProperty(SendAiMessageParamEnum.IMPORTANT.key(), important);
        object.addProperty(SendAiMessageParamEnum.EXPECTS_REPLY.key(), expectsReply);
        object.addProperty(SendAiMessageParamEnum.REPLY_IMPORTANT.key(), replyImportant);
        return new ToolRequestArguments(object);
    }

    private static void register(AiSessionInboxBroker broker, AiSession... sessions) {
        for (AiSession session : sessions) {
            broker.register(session);
        }
    }

    @Test
    void sendToolPassesBoundariesAndReplyFlags() {
        AiSession sender = session("handler-send-sender", "Sender", true, true, false);
        AiSession target = session("handler-send-target", "Target", true, true, false);
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        register(broker, sender, target);
        SendAiMessageTool tool = new SendAiMessageTool();

        String accepted = tool.handle(sendArgs(sender, target.id(), "s".repeat(100), "b".repeat(200_000),
                null, false, true, true), null);
        assertTrue(accepted.startsWith("Message sent"), accepted);
        assertTrue(tool.handle(sendArgs(sender, target.id(), "s".repeat(101), "body", null, false, false, false), null)
                .contains("subject exceeds maximum length"));
        assertTrue(tool.handle(sendArgs(sender, target.id(), "ok", "b".repeat(200_001), null, false, false, false), null)
                .contains("message body exceeds maximum length"));

        AiInboxMessage stored = broker.listInbox(target.id(), target.secret()).get(0);
        assertTrue(stored.expectsReply());
        assertTrue(stored.replyImportant());
    }

    /**
     * FIX 4 (the incident Boss hit: a RUNNING session with allowInterAiComms=false was absent from
     * ListAiSessions) — a comms-disabled target must be told so, and must NOT be told it is "not active": the
     * two failures need opposite remedies, and pasting the wrong one sends the caller back to the session
     * list that hides the real target. Nothing may be written to the inbox.
     * <p>
     * The target is built the way production builds a comms-disabled session: KNOWN to the registry (the
     * session helper registers a wrapper) but never registered with the broker — AiTopComponent registers a
     * session only when allowInterAiComms is on, so registering it with the broker first would test a state
     * that cannot occur and would let the bug survive.
     * <p>
     * Moving the comms check out of the !isActive branch turns this red: the target falls through to "is not
     * active".
     */
    @Test
    void sendToolRefusesATargetWhoseInterAiCommsAreDisabled() {
        AiSession sender = session("fx4-comms-sender", "Sender", true, true, false);
        AiSession target = session("fx4-comms-target", "Target", false, true, false);
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        register(broker, sender);
        SendAiMessageTool tool = new SendAiMessageTool();

        String result = tool.handle(sendArgs(sender, target.id(), "s", "b", null, false, false, false), null);

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("inter-AI messaging disabled"), result);
        assertTrue(result.contains(target.id()), "the refusal must name the id: " + result);
        assertFalse(result.contains("is not active"),
                "a comms-disabled target must not be told it is not active");
        assertTrue(broker.listInbox(target.id(), target.secret()).isEmpty(),
                "a refused send must not write an inbox entry");
    }

    /**
     * A session that EXISTS (registered with a wrapper) but has no inbox is genuinely inactive, and must be
     * told "is not active" — never the unknown-id advice, which would send the caller hunting for an id that
     * is known to the registry.
     */
    @Test
    void sendToolDistinguishesAKnownInactiveTargetFromAnUnknownId() {
        AiSession sender = session("fx4-inactive-sender", "Sender", true, true, false);
        // Known to the registry (the session helper registers the wrapper) but never given an inbox.
        AiSession target = session("fx4-inactive-target", "Target", true, true, false);
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        register(broker, sender);
        SendAiMessageTool tool = new SendAiMessageTool();

        String result = tool.handle(sendArgs(sender, target.id(), "s", "b", null, false, false, false), null);

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("is not active"), result);
        assertFalse(result.contains("no AI session has the ID"),
                "a KNOWN but inactive session must not be told its id is unknown");
    }

    /**
     * A truly unknown id must get the directive verbatim-id advice and must never be told "is not active" —
     * that advice is reserved for sessions the registry can still find.
     */
    @Test
    void sendToolDistinguishesAnUnknownIdFromAnInactiveSession() {
        AiSession sender = session("fx4-unknown-sender", "Sender", true, true, false);
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        register(broker, sender);
        SendAiMessageTool tool = new SendAiMessageTool();

        String result = tool.handle(sendArgs(sender, "fx4-does-not-exist", "s", "b",
                null, false, false, false), null);

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("no AI session has the ID"), result);
        assertTrue(result.contains("verbatim"), result);
        assertFalse(result.contains("is not active"),
                "an unknown id must not get the 'is not active' advice meant for a known session");
    }

    @Test
    void sendToolPassesReplyToIdAndAbsentExpectation() {
        AiSession sender = session("handler-reply-sender", "Sender", true, true, false);
        AiSession target = session("handler-reply-target", "Target", true, true, false);
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        register(broker, sender, target);
        String original = broker.sendMessage(sender.id(), target.id(), "Question", "body", null, false, true, true);
        SendAiMessageTool tool = new SendAiMessageTool();

        String result = tool.handle(sendArgs(target, sender.id(), "Reply", "answer", original, false, false, false), null);
        assertTrue(result.startsWith("Message sent"), result);
        AiInboxMessage reply = broker.listInbox(sender.id(), sender.secret()).stream()
                .filter(m -> "Reply".equals(m.subject())).findFirst().orElseThrow();
        assertEquals(original, reply.replyToId());
        assertFalse(reply.expectsReply());

        tool.handle(sendArgs(target, sender.id(), "FYI", "no reply", null, false, false, true), null);
        AiInboxMessage fyi = broker.listInbox(sender.id(), sender.secret()).stream()
                .filter(m -> "FYI".equals(m.subject())).findFirst().orElseThrow();
        assertNull(fyi.replyToId());
        assertFalse(fyi.expectsReply());
        assertFalse(fyi.replyImportant());
    }

    @Test
    void sendToolRefusesASessionMessagingItselfAndWritesNothing() throws Exception {
        AiSession self = session("handler-self-sender", "Self", true, true, false);
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        register(broker, self);
        SendAiMessageTool tool = new SendAiMessageTool();

        // expectsReply=true is the worst case: it is the flag that would create a pending-reply entry.
        String result = tool.handle(sendArgs(self, self.id(), "Ping", "to myself", null, false, true, true), null);

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("cannot send a message to your own session"), result);
        assertTrue(result.contains(self.id()), "the refusal must name the offending id: " + result);
        assertTrue(result.contains("ListAiSessions"), "the refusal must point at the fix: " + result);
        assertTrue(broker.listInbox(self.id(), self.secret()).isEmpty(), "a refused self-send must not write an inbox entry");
        assertTrue(broker.listOwedReplies(self.id()).isEmpty());
        assertTrue(pendingRepliesOf(broker).values().stream().noneMatch(e -> e.toString().contains(self.id())),
                "a refused self-send must not create pending-reply tracking");
    }

    @Test
    void sendToolStillDeliversToADifferentSessionAfterTheSelfSendGuard() {
        AiSession sender = session("handler-self-guard-sender", "Sender", true, true, false);
        AiSession target = session("handler-self-guard-target", "Target", true, true, false);
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        register(broker, sender, target);
        SendAiMessageTool tool = new SendAiMessageTool();

        String result = tool.handle(sendArgs(sender, target.id(), "Hello", "body", null, false, false, false), null);

        assertTrue(result.startsWith("Message sent"), result);
        assertEquals(1, broker.listInbox(target.id(), target.secret()).size());
        assertTrue(broker.listInbox(sender.id(), sender.secret()).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pendingRepliesOf(AiSessionInboxBroker broker) throws Exception {
        Field f = AiSessionInboxBroker.class.getDeclaredField("pendingReplies");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(broker);
    }

    @Test
    void deleteToolAcceptsSingleBulkCombinedAndNeither() {
        AiSession target = session("handler-delete-target", "Target", true, true, false);
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        register(broker, target);
        String one = broker.sendMessage("sender", target.id(), "one", "body", null);
        String two = broker.sendMessage("sender", target.id(), "two", "body", null);
        String three = broker.sendMessage("sender", target.id(), "three", "body", null);
        DeleteAiMessageTool tool = new DeleteAiMessageTool();

        JsonObject single = new JsonObject();
        single.addProperty(DeleteAiMessageParamEnum.SESSION_ID.key(), target.id());
        single.addProperty(DeleteAiMessageParamEnum.SECRET_KEY.key(), target.secret());
        single.addProperty(DeleteAiMessageParamEnum.MESSAGE_ID.key(), one);
        assertEquals("Deleted 1 message(s).", tool.handle(new ToolRequestArguments(single), null));

        JsonObject both = new JsonObject();
        both.addProperty(DeleteAiMessageParamEnum.SESSION_ID.key(), target.id());
        both.addProperty(DeleteAiMessageParamEnum.SECRET_KEY.key(), target.secret());
        both.addProperty(DeleteAiMessageParamEnum.MESSAGE_ID.key(), two);
        JsonArray ids = new JsonArray();
        ids.add(three);
        both.add(DeleteAiMessageParamEnum.MESSAGE_IDS.key(), ids);
        assertEquals("Deleted 2 message(s).", tool.handle(new ToolRequestArguments(both), null));

        JsonObject neither = new JsonObject();
        neither.addProperty(DeleteAiMessageParamEnum.SESSION_ID.key(), target.id());
        neither.addProperty(DeleteAiMessageParamEnum.SECRET_KEY.key(), target.secret());
        assertTrue(tool.handle(new ToolRequestArguments(neither), null).contains("provide messageId"));
    }

    @Test
    void listToolExcludesCallerAndDisabledPeer() {
        AiSession caller = session("handler-list-caller", "Caller", true, true, false);
        AiSession disabled = session("handler-list-disabled", "Disabled", false, true, false);
        AiSession visible = session("handler-list-visible", "Visible", true, true, false);
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        register(broker, caller, disabled, visible);
        JsonObject object = new JsonObject();
        object.addProperty(ListAiSessionsParamEnum.SESSION_ID.key(), caller.id());

        String result = new ListAiSessionsTool().handle(new ToolRequestArguments(object), null);
        assertFalse(result.contains(caller.id()), result);
        assertFalse(result.contains(disabled.id()), result);
        assertTrue(result.contains(visible.id()), result);
        assertTrue(result.contains("\"awaitingApproval\":false"), result);

        visible.setAwaitingApproval(true);
        result = new ListAiSessionsTool().handle(new ToolRequestArguments(object), null);
        assertTrue(result.contains("\"awaitingApproval\":true"), result);
        visible.setAwaitingApproval(false);
        result = new ListAiSessionsTool().handle(new ToolRequestArguments(object), null);
        assertTrue(result.contains("\"awaitingApproval\":false"), result);
    }

    @Test
    void readToolAddsReplyInstructionOnlyOnFirstRead() {
        AiSession target = session("handler-read-target", "Target", true, true, false);
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        register(broker, target);
        String id = broker.sendMessage("sender", target.id(), "Question", "body", null, false, true, false);
        JsonObject object = new JsonObject();
        object.addProperty(ReadAiMessageParamEnum.SESSION_ID.key(), target.id());
        object.addProperty(ReadAiMessageParamEnum.SECRET_KEY.key(), target.secret());
        object.addProperty(ReadAiMessageParamEnum.MESSAGE_ID.key(), id);
        ToolRequestArguments request = new ToolRequestArguments(object);
        ReadAiMessageTool tool = new ReadAiMessageTool();
        String first = tool.handle(request, null);
        String second = tool.handle(request, null);
        assertTrue(first.contains(NotificationUtil.formatReplyExpectedInstruction(id)), first);
        assertFalse(second.contains(NotificationUtil.formatReplyExpectedInstruction(id)), second);
    }

    @Test
    void activeToolReportsUnknownIdleAndBusy() {
        AiSession idle = session("handler-active-idle", "Idle", true, true, false);
        AiSession busy = session("handler-active-busy", "Busy", true, true, true);
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        register(broker, idle, busy);
        IsAiSessionActiveTool tool = new IsAiSessionActiveTool();
        assertTrue(tool.handle(args(IsAiSessionActiveParamEnum.TARGET_SESSION_ID.key(), "missing"), null).contains("not open"));
        assertTrue(tool.handle(args(IsAiSessionActiveParamEnum.TARGET_SESSION_ID.key(), idle.id()), null).contains("idle"));
        assertTrue(tool.handle(args(IsAiSessionActiveParamEnum.TARGET_SESSION_ID.key(), busy.id()), null).contains("busy"));
        busy.setAwaitingApproval(true);
        assertTrue(tool.handle(args(IsAiSessionActiveParamEnum.TARGET_SESSION_ID.key(), busy.id()), null)
                .contains("awaiting approval"));
        busy.setAwaitingApproval(false);
        assertFalse(tool.handle(args(IsAiSessionActiveParamEnum.TARGET_SESSION_ID.key(), busy.id()), null)
                .contains("awaiting approval"));
    }

    @Test
    void updateDescriptionToolStoresAndEchoes() {
        AiSession session = session("handler-description", "Session", true, true, false);
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        register(broker, session);
        JsonObject object = new JsonObject();
        object.addProperty(UpdateSessionDescriptionParamEnum.SESSION_ID.key(), session.id());
        object.addProperty(UpdateSessionDescriptionParamEnum.SECRET_KEY.key(), session.secret());
        object.addProperty(UpdateSessionDescriptionParamEnum.DESCRIPTION.key(), "handler audit");
        String result = new UpdateSessionDescriptionTool().handle(new ToolRequestArguments(object), null);
        assertTrue(result.contains("handler audit"), result);
        assertEquals("handler audit", session.description());
    }
}

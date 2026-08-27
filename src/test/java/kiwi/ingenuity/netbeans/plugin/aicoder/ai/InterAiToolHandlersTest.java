package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
        assertTrue(first.contains(NotificationUtil.formatReplyExpectedInstruction()), first);
        assertFalse(second.contains(NotificationUtil.formatReplyExpectedInstruction()), second);
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

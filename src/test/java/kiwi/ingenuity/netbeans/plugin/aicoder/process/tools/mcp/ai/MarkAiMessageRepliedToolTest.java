package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class MarkAiMessageRepliedToolTest {

    private final Set<String> registeredIds = new HashSet<>();

    @AfterEach
    void cleanup() {
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        registeredIds.forEach(id -> {
            broker.unregister(id);
            SessionRegistry.unregister(id);
        });
    }

    @Test
    void returnsEveryMarkRepliedOutcome() {
        registerSession("mark-sender");
        AiSession target = registerSession("mark-outcomes-target");
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        MarkAiMessageRepliedTool tool = new MarkAiMessageRepliedTool();

        String expected = broker.sendMessage("mark-sender", target.id(), "Question", "body", null, false, true, false);
        assertNotNull(expected);
        assertEquals("Message " + expected + " marked replied.", tool.handle(arguments(target, expected), null));
        assertEquals("Message " + expected + " was already marked replied.", tool.handle(arguments(target, expected), null));

        String notExpected = broker.sendMessage("mark-sender", target.id(), "FYI", "body", null, false, false, false);
        assertNotNull(notExpected);
        assertEquals("Message " + notExpected + " did not ask for a reply; nothing to mark.",
                     tool.handle(arguments(target, notExpected), null));
        assertEquals("Error: no message missing-message in your inbox.",
                     tool.handle(arguments(target, "missing-message"), null));
    }

    @Test
    void markedMessageLeavesOwedReplies() {
        registerSession("mark-sender");
        AiSession target = registerSession("mark-owed-target");
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        String id = broker.sendMessage("mark-sender", target.id(), "Question", "body", null, false, true, false);
        assertNotNull(id);
        assertEquals(1, broker.listOwedReplies(target.id()).stream().filter(m -> id.equals(m.id())).count());

        String result = new MarkAiMessageRepliedTool().handle(arguments(target, id), null);
        assertEquals("Message " + id + " marked replied.", result);
        assertFalse(broker.listOwedReplies(target.id()).stream().anyMatch(m -> id.equals(m.id())));
    }

    @Test
    void isMutatingButDoesNotNeedGlobalMutationLock() {
        MarkAiMessageRepliedTool tool = new MarkAiMessageRepliedTool();
        assertTrue(tool.isMutating());
        assertFalse(tool.requiresGlobalMutationLock());
    }

    private static ToolRequestArguments arguments(AiSession target, String messageId) {
        JsonObject object = new JsonObject();
        object.addProperty(MarkAiMessageRepliedParamEnum.SESSION_ID.key(), target.id());
        object.addProperty(MarkAiMessageRepliedParamEnum.SECRET_KEY.key(), target.secret());
        object.addProperty(MarkAiMessageRepliedParamEnum.MESSAGE_ID.key(), messageId);
        return new ToolRequestArguments(object);
    }

    private AiSession registerSession(String id) {
        AiSessionSettings settings = new AiSessionSettings(null, null, true, null, true, null, null, null);
        AiSession session = new AiSession(id, id, null, AiTypeEnum.CLAUDE, null, settings, Instant.now(), Instant.now());
        session.setAiSessionCallback(new AiSessionCallback() {
            @Override
            public boolean isRunning() {
                return true;
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
        AbstractAiSession wrapper = new AbstractAiSession(session) {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getSessionName() {
                return id;
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
        AiSessionInboxBroker.getInstance().register(session);
        registeredIds.add(id);
        return session;
    }
}

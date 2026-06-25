package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiInboxMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.AbstractNotification;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSessionCallback;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiSessionBrokerTest {

    private static AiSession stubSession(String id, String name) {
        AbstractAiSessionSettings settings = new AbstractAiSessionSettings(null, null, true, null, true, null);
        AiSession s = new AiSession(id, name, null, AiTypeEnum.CLAUDE, null, settings, Instant.now(), Instant.now());
        s.setAiSessionCallback(new AiSessionCallback() {
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
        // Register in SessionRegistry so broker.sessionFromRegistry() can find it
        registerTestSession(id, s);
        return s;
    }

    private static void registerTestSession(String id, AiSession session) {
        AbstractAiSession wrapper = new AbstractAiSession(session) {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getSessionName() {
                return session.name();
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
    }

    private AiSessionInboxBroker broker;

    @BeforeEach
    void setUp() {
        broker = new AiSessionInboxBroker();
    }

    @Test
    void listActiveExcludesCaller() {
        AiSession a = stubSession("a", "AlphaAI");
        AiSession b = stubSession("b", "BetaAI");
        broker.register(a);
        broker.register(b);

        List<AiSession> visible = broker.listActive("a");
        assertEquals(1, visible.size());
        assertEquals("b", visible.get(0).id());
    }

    @Test
    void sendMessageWritesToInbox() {
        AiSession target = stubSession("target", "TargetAI");
        broker.register(target);
        broker.sendMessage("sender", "target", "Test subject", "hello body", null);

        List<AiInboxMessage> inbox = broker.listInbox("target", target.secret());
        assertEquals(1, inbox.size());
        assertEquals("Test subject", inbox.get(0).subject());
        assertTrue(inbox.get(0).body().contains("hello body"));
        assertEquals("sender", inbox.get(0).fromSessionId());
    }

    @Test
    void listInboxIsNonDestructive() {
        AiSession target = stubSession("target", "TargetAI");
        broker.register(target);
        broker.sendMessage("sender", "target", "Sub1", "msg1", null);
        broker.sendMessage("sender", "target", "Sub2", "msg2", null);

        List<AiInboxMessage> first = broker.listInbox("target", target.secret());
        assertEquals(2, first.size());
        List<AiInboxMessage> second = broker.listInbox("target", target.secret());
        assertEquals(2, second.size(), "listInbox should not consume messages");
    }

    @Test
    void readMessageMarksReadButKeepsIt() {
        AiSession target = stubSession("target", "TargetAI");
        broker.register(target);
        String id1 = broker.sendMessage("sender", "target", "Sub1", "msg1", null);
        broker.sendMessage("sender", "target", "Sub2", "msg2", null);

        AiInboxMessage read = broker.readMessage("target", target.secret(), id1);
        assertNotNull(read);
        assertNotNull(read.readAt(), "read stamps readAt");

        List<AiInboxMessage> remaining = broker.listInbox("target", target.secret());
        assertEquals(2, remaining.size(), "read is non-destructive — message stays");
    }

    @Test
    void deleteMessagesRemovesByIdAndReturnsCount() {
        AiSession target = stubSession("target", "TargetAI");
        broker.register(target);
        String id1 = broker.sendMessage("sender", "target", "Sub1", "msg1", null);
        String id2 = broker.sendMessage("sender", "target", "Sub2", "msg2", null);

        int deleted = broker.deleteMessages("target", target.secret(), List.of(id1, "nope"));
        assertEquals(1, deleted, "only existing ids count");
        List<AiInboxMessage> remaining = broker.listInbox("target", target.secret());
        assertEquals(1, remaining.size());
        assertEquals(id2, remaining.get(0).id());
    }

    @Test
    void deleteMessagesRejectsWrongSecret() {
        AiSession target = stubSession("target", "TargetAI");
        broker.register(target);
        broker.sendMessage("sender", "target", "Sub1", "msg1", null);
        assertEquals(0, broker.deleteMessages("target", "wrong", List.of("x")));
    }

    @Test
    void replyStampsRespondedAtOnOriginal() {
        AiSession a = stubSession("a", "A");
        AiSession b = stubSession("b", "B");
        broker.register(a);
        broker.register(b);
        // a -> b
        String origId = broker.sendMessage("a", "b", "Q", "question", null);
        // b replies to a, referencing origId (which lives in b's inbox)
        broker.sendMessage("b", "a", "Re: Q", "answer", origId);

        AiInboxMessage original = broker.listInbox("b", b.secret()).stream()
                .filter(m -> m.id().equals(origId)).findFirst().orElseThrow();
        assertNotNull(original.respondedAt(), "replying stamps respondedAt on the original");
    }

    @Test
    void listInboxRejectsWrongSecret() {
        AiSession target = stubSession("target", "TargetAI");
        broker.register(target);

        List<AiInboxMessage> result = broker.listInbox("target", "wrong-secret");
        assertNull(result, "should return null on auth failure");
    }

    @Test
    void readMessageReturnsNullForUnknownId() {
        AiSession target = stubSession("target", "TargetAI");
        broker.register(target);

        AiInboxMessage result = broker.readMessage("target", target.secret(), "no-such-id");
        assertNull(result);
    }

    @Test
    void unregisterRemovesHandle() {
        AiSession a = stubSession("a", "AlphaAI");
        broker.register(a);
        broker.unregister("a");

        assertTrue(broker.listActive("other").isEmpty());
        assertFalse(broker.isActive("a"));
    }

    @Test
    void sendMessagePushesNotificationAsync() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();
        AiSession target = stubSession("target", "TargetAI");
        // override the callbacks for this test:
        target.setAiSessionCallback(new AiSessionCallback() {
            @Override
            public boolean isRunning() {
                return true;
            }

            @Override
            public void requestGracefulInterrupt(InterruptTypeEnum type) {
            }

            @Override
            public void deliverIncomingMessage(String fromSessionId, AbstractNotification notification) {
                received.set(notification.text());
                latch.countDown();
            }

            @Override
            public void applyDescriptionUpdate(String desc) {
            }
        });
        broker.register(target);
        broker.sendMessage("sender", "target", "ping subject", "ping body", null);

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNotNull(received.get());
        assertTrue(received.get().contains("ping subject"));
    }

    @Test
    void purgeRemovesExpiredReadOnly() throws Exception {
        AiSession target = stubSession("target", "TargetAI");
        broker.register(target);
        String readId = broker.sendMessage("sender", "target", "old", "read-old", null);
        String unreadId = broker.sendMessage("sender", "target", "new", "unread", null);
        // mark the first read
        broker.readMessage("target", target.secret(), readId);

        long now = System.currentTimeMillis();
        // retention 0ms: any read message is already expired; unread is untouched
        broker.purgeExpiredRead(now + 1, 0L);

        List<AiInboxMessage> remaining = broker.listInbox("target", target.secret());
        assertEquals(1, remaining.size(), "expired read purged, unread kept");
        assertEquals(unreadId, remaining.get(0).id());
    }

    @Test
    void purgeKeepsReadWithinRetention() {
        AiSession target = stubSession("target", "TargetAI");
        broker.register(target);
        String readId = broker.sendMessage("sender", "target", "x", "y", null);
        broker.readMessage("target", target.secret(), readId);

        // huge retention window -> nothing expires
        broker.purgeExpiredRead(System.currentTimeMillis(), 3_600_000L);
        assertEquals(1, broker.listInbox("target", target.secret()).size());
    }

}

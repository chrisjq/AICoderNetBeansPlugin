package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.junit.jupiter.api.Test;

class InterAiToolsTest {

    private static AiSession stubSession(String id, String name, boolean commsAllowed, boolean importantAllowed, boolean running, int[] interruptCount) {
        AbstractAiSessionSettings settings = new AbstractAiSessionSettings(null, null, commsAllowed, null, importantAllowed, null);
        AiSession s = new AiSession(id, name, null, AiTypeEnum.CLAUDE, null, settings, Instant.now(), Instant.now());
        s.setAiSessionCallback(new AiSessionCallback() {
            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public void requestGracefulInterrupt(InterruptTypeEnum type) {
                if (interruptCount != null) {
                    interruptCount[0]++;
                }
            }

            @Override
            public void deliverIncomingMessage(String from, AbstractNotification msg) {
            }

            @Override
            public void applyDescriptionUpdate(String desc) {
            }
        });
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

    private static AiSession stubSession(String id, String name, boolean commsAllowed) {
        return stubSession(id, name, commsAllowed, true, true, null);
    }

    private static AiSession stubSession(String id, String name) {
        return stubSession(id, name, true);
    }

    @Test
    void listActiveExcludesCaller() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        AiSession a = stubSession("a", "AlphaAI");
        AiSession b = stubSession("b", "BetaAI");
        broker.register(a);
        broker.register(b);

        List<AiSession> sessions = broker.listActive("a");
        assertEquals(1, sessions.size());
        assertEquals("b", sessions.get(0).id());
    }

    @Test
    void getAiMessagesListsWithoutConsuming() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        AiSession target = stubSession("target", "TargetAI");
        broker.register(target);
        broker.sendMessage("sender", "target", "Hello subject", "hello body", null);

        List<AiInboxMessage> msgs = broker.listInbox("target", target.secret());
        assertNotNull(msgs);
        assertFalse(msgs.isEmpty());
        assertEquals("Hello subject", msgs.get(0).subject());
        assertTrue(msgs.get(0).body().contains("hello body"));

        List<AiInboxMessage> still = broker.listInbox("target", target.secret());
        assertEquals(1, still.size(), "listInbox must not consume messages");
    }

    @Test
    void readMessageMarksReadButKeepsMessage() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        AiSession target = stubSession("target", "TargetAI");
        broker.register(target);
        String id = broker.sendMessage("sender", "target", "Subject", "body", null);

        AiInboxMessage msg = broker.readMessage("target", target.secret(), id);
        assertNotNull(msg);
        assertEquals(id, msg.id());
        assertNotNull(msg.readAt(), "read stamps readAt");
        assertEquals(1, broker.listInbox("target", target.secret()).size(),
                "read is non-destructive — message stays until deleted/expired");
    }

    @Test
    void listInboxFailsWithWrongSecret() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        AiSession target = stubSession("target", "TargetAI");
        broker.register(target);
        assertNull(broker.listInbox("target", "wrong"));
    }

    @Test
    void listActiveExcludesSessionsWithCommsDisabled() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        AiSession caller = stubSession("caller", "CallerAI");
        AiSession disabled = stubSession("disabled", "DisabledAI", false);
        AiSession enabled = stubSession("enabled", "EnabledAI");
        broker.register(caller);
        broker.register(disabled);
        broker.register(enabled);

        List<AiSession> sessions = broker.listActive("caller");
        assertEquals(1, sessions.size());
        assertEquals("enabled", sessions.get(0).id());
    }

    @Test
    void isInterAiCommsAllowedTrueForEnabledSession() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        AiSession s = stubSession("s", "AI");
        broker.register(s);
        assertTrue(broker.isInterAiCommsAllowed("s"));
    }

    @Test
    void isInterAiCommsAllowedFalseForDisabledSession() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        AiSession s = stubSession("s", "AI", false);
        broker.register(s);
        assertFalse(broker.isInterAiCommsAllowed("s"));
    }

    @Test
    void isInterAiCommsAllowedFalseForUnknownSession() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        assertFalse(broker.isInterAiCommsAllowed("unknown"));
    }

    @Test
    void sendMessageWithImportantFlagInterruptsRunningSession() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        int[] ic = {0};
        AiSession target = stubSession("target", "TargetAI", true, true, true, ic);
        broker.register(target);

        broker.sendMessage("sender", "target", "Subject", "body", null, true);

        assertEquals(1, ic[0]);
    }

    @Test
    void sendMessageWithImportantFlagDoesNotInterruptWhenDisabled() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        int[] ic = {0};
        AiSession target = stubSession("target", "TargetAI", true, false, true, ic);
        broker.register(target);

        broker.sendMessage("sender", "target", "Subject", "body", null, true);

        assertEquals(0, ic[0]);
    }

    @Test
    void sendMessageWithImportantFlagDoesNotInterruptWhenNotRunning() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        int[] ic = {0};
        AiSession target = stubSession("target", "TargetAI", true, true, false, ic);
        broker.register(target);

        broker.sendMessage("sender", "target", "Subject", "body", null, true);

        assertEquals(0, ic[0]);
    }

    // --- unregister with unread messages ---
    @Test
    void unregisterWithUnreadMessagesAddsDeliveryFailureToSenderInbox() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        AiSession sender = stubSession("sender", "SenderAI");
        AiSession target = stubSession("target", "TargetAI");
        broker.register(sender);
        broker.register(target);
        broker.sendMessage("sender", "target", "My task", "do this", null);

        broker.unregister("target");

        List<AiInboxMessage> senderInbox = broker.listInbox("sender", sender.secret());
        assertEquals(1, senderInbox.size());
        AiInboxMessage notification = senderInbox.get(0);
        assertTrue(notification.subject().toLowerCase().contains("undelivered")
                || notification.subject().toLowerCase().contains("exited"),
                "subject should indicate undelivered/exited, was: " + notification.subject());
        assertTrue(notification.body().contains("My task"),
                "body should reference the original message subject, was: " + notification.body());
    }

    @Test
    void unregisterWithImportantUnreadMessageSetsImportantOnNotification() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        AiSession sender = stubSession("sender", "SenderAI");
        AiSession target = stubSession("target", "TargetAI");
        broker.register(sender);
        broker.register(target);
        broker.sendMessage("sender", "target", "Urgent task", "do this now", null, true);

        broker.unregister("target");

        List<AiInboxMessage> senderInbox = broker.listInbox("sender", sender.secret());
        assertEquals(1, senderInbox.size());
        assertTrue(senderInbox.get(0).important(),
                "delivery failure notification must carry important=true when original was important");
    }

    @Test
    void unregisterWithImportantUnreadMessageInterruptsSenderRegardlessOfAllowSetting() throws InterruptedException {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        CountDownLatch latch = new CountDownLatch(1);
        int[] interruptCount = {0};

        // Sender has allowImportantMessages=false — must still be interrupted
        AiSession sender = new AiSession("sender", "SenderAI", null, AiTypeEnum.CLAUDE, null,
                new AbstractAiSessionSettings(null, null, true, null, false, null),
                Instant.now(), Instant.now());
        sender.setAiSessionCallback(new AiSessionCallback() {
            @Override
            public boolean isRunning() {
                return true;
            }

            @Override
            public void requestGracefulInterrupt(InterruptTypeEnum type) {
                interruptCount[0]++;
                latch.countDown();
            }

            @Override
            public void deliverIncomingMessage(String from, AbstractNotification msg) {
            }

            @Override
            public void applyDescriptionUpdate(String desc) {
            }
        });
        registerTestSession("sender", sender);

        AiSession target = stubSession("target", "TargetAI");
        broker.register(sender);
        broker.register(target);
        broker.sendMessage("sender", "target", "Urgent task", "do this urgently", null, true);

        broker.unregister("target");

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "sender must be interrupted even when allowImportantMessages=false");
        assertEquals(1, interruptCount[0]);
    }

    @Test
    void unregisterWithNonImportantUnreadMessageDoesNotInterruptSender() throws InterruptedException {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        int[] interruptCount = {0};

        AiSession sender = new AiSession("sender", "SenderAI", null, AiTypeEnum.CLAUDE, null,
                new AbstractAiSessionSettings(null, null, true, null, true, null),
                Instant.now(), Instant.now());
        sender.setAiSessionCallback(new AiSessionCallback() {
            @Override
            public boolean isRunning() {
                return true;
            }

            @Override
            public void requestGracefulInterrupt(InterruptTypeEnum type) {
                interruptCount[0]++;
            }

            @Override
            public void deliverIncomingMessage(String from, AbstractNotification msg) {
            }

            @Override
            public void applyDescriptionUpdate(String desc) {
            }
        });

        AiSession target = stubSession("target", "TargetAI");
        broker.register(sender);
        broker.register(target);
        broker.sendMessage("sender", "target", "Routine task", "do this when free", null, false);

        broker.unregister("target");

        // Give the notifier a moment — there should be no interrupt submitted
        Thread.sleep(200);
        assertEquals(0, interruptCount[0], "non-important unread should not interrupt the sender");
    }

    // --- atomic target-active check ---
    @Test
    void sendMessageToInactiveSessionReturnsNull() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        String result = broker.sendMessage("a", "nonexistent", "Hello", "body", null);
        assertNull(result, "sendMessage to unregistered session should return null");
    }

    // --- expectsReply / replyImportant ---
    @Test
    void replyToMessageWithReplyImportantFalseDoesNotInterruptSender() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        int[] icA = {0};
        int[] icB = {0};
        AiSession sessionA = stubSession("a", "A", true, true, true, icA);
        AiSession sessionB = stubSession("b", "B", true, true, true, icB);
        broker.register(sessionA);
        broker.register(sessionB);

        String originalId = broker.sendMessage("a", "b", "Q", "body", null, false, true, false);
        broker.sendMessage("b", "a", "Re: Q", "answer", originalId, false, false, false);

        assertEquals(0, icA[0],
                "replyImportant=false should not interrupt the original sender on reply");
    }

    @Test
    void replyToMessageWithReplyImportantTrueAutoUpgradesImportanceToInterruptSender() {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        int[] icA = {0};
        int[] icB = {0};
        AiSession sessionA = stubSession("a", "A", true, true, true, icA);
        AiSession sessionB = stubSession("b", "B", true, true, true, icB);
        broker.register(sessionA);
        broker.register(sessionB);

        String originalId = broker.sendMessage("a", "b", "Q", "body", null, false, true, true);
        broker.sendMessage("b", "a", "Re: Q", "answer", originalId, false, false, false);

        assertEquals(1, icA[0],
                "replyImportant=true should auto-upgrade reply importance and interrupt the original sender");
    }

    // --- pending reply cleanup on session exit ---
    @Test
    void unregisterAfterReadWithoutReplyNotifiesSenderOfNoReply() throws InterruptedException {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        CountDownLatch latch = new CountDownLatch(1);

        AiSession sessionA = new AiSession("a", "A", null, AiTypeEnum.CLAUDE, null,
                new AbstractAiSessionSettings(null, null, true, null, true, null),
                Instant.now(), Instant.now());
        sessionA.setAiSessionCallback(new AiSessionCallback() {
            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public void requestGracefulInterrupt(InterruptTypeEnum type) {
            }

            @Override
            public void deliverIncomingMessage(String from, AbstractNotification msg) {
                latch.countDown();
            }

            @Override
            public void applyDescriptionUpdate(String desc) {
            }
        });
        registerTestSession("a", sessionA);

        AiSession sessionB = stubSession("b", "B");
        broker.register(sessionA);
        broker.register(sessionB);

        String originalId = broker.sendMessage("a", "b", "My question", "body", null, false, true, false);
        broker.readMessage("b", sessionB.secret(), originalId);
        broker.unregister("b");

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "A should receive a no-reply notification when B exits without responding");
        assertFalse(broker.listInbox("a", sessionA.secret()).isEmpty(),
                "A's inbox should contain the no-reply notification");
    }

    @Test
    void unregisterAfterReadWithoutReplyAndReplyImportantInterruptsSenderRegardlessOfSetting()
            throws InterruptedException {
        AiSessionInboxBroker broker = new AiSessionInboxBroker();
        CountDownLatch latch = new CountDownLatch(1);
        int[] interruptCount = {0};

        // allowsImportantMessages=false — must still be interrupted because replyImportant=true
        AiSession sessionA = new AiSession("a", "A", null, AiTypeEnum.CLAUDE, null,
                new AbstractAiSessionSettings(null, null, true, null, false, null),
                Instant.now(), Instant.now());
        sessionA.setAiSessionCallback(new AiSessionCallback() {
            @Override
            public boolean isRunning() {
                return true;
            }

            @Override
            public void requestGracefulInterrupt(InterruptTypeEnum type) {
                interruptCount[0]++;
                latch.countDown();
            }

            @Override
            public void deliverIncomingMessage(String from, AbstractNotification msg) {
            }

            @Override
            public void applyDescriptionUpdate(String desc) {
            }
        });
        registerTestSession("a", sessionA);

        AiSession sessionB = stubSession("b", "B");
        broker.register(sessionA);
        broker.register(sessionB);

        String originalId = broker.sendMessage("a", "b", "Urgent question", "body", null, false, true, true);
        broker.readMessage("b", sessionB.secret(), originalId);
        broker.unregister("b");

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "A should be interrupted by no-reply notification even with allowImportantMessages=false");
        assertEquals(1, interruptCount[0]);
    }
}

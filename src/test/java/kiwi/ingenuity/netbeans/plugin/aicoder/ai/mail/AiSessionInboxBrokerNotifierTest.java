package kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiInboxMessageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.AbstractNotification;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSessionCallback;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.GlobalPropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for required fix 16: pending-reply hijack guard, unread-aware eviction, zero-capacity floor, visible
 * capacity-checked failure/expiry notices, and the coalescing notifier that never drops announcements under burst.
 * Lives in the broker's package so it can drive the package-private {@code setMaxInboxSize} supplier.
 */
class AiSessionInboxBrokerNotifierTest {

    private static AiSession stubSession(String id, String name, Runnable onDelivery) {
        AiSessionSettings settings = new AiSessionSettings(null, null, true, null, true, null, null, null);
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
                if (onDelivery != null) {
                    onDelivery.run();
                }
            }

            @Override
            public void applyDescriptionUpdate(String desc) {
            }
        });
        AbstractAiSession wrapper = new AbstractAiSession(s) {
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
        return s;
    }

    private AiSessionInboxBroker broker;
    private AiPropertyListener busListener;

    @BeforeEach
    void setUp() {
        broker = new AiSessionInboxBroker();
    }

    @AfterEach
    void tearDown() {
        if (busListener != null) {
            GlobalPropertyBus.getInstance().removeListener(busListener);
            busListener = null;
        }
    }

    private List<String> subjectsOf(String sessionId, String secret) {
        return broker.listInbox(sessionId, secret).stream()
                .map(AiInboxMessage::subject)
                .collect(Collectors.toList());
    }

    @Test
    void zeroMaxInboxSizeFloorsAtOneAndNeverThrows() throws Exception {
        AiSession target = stubSession("cap-target", "TargetAI", null);
        broker.setMaxInboxSize(() -> 0);
        broker.register(target);

        assertDoesNotThrow(() -> broker.sendMessage("s", "cap-target", "first", "body", null));
        assertEquals(1, broker.listInbox("cap-target", target.secret()).size(),
                "capacity floors at one message");

        broker.sendMessage("s", "cap-target", "second", "body", null);
        assertTrue(broker.awaitNotifierIdle(2, TimeUnit.SECONDS));
        List<AiInboxMessage> inbox = broker.listInbox("cap-target", target.secret());
        assertEquals(1, inbox.size(), "still capped at one");
        assertEquals("second", inbox.get(0).subject(), "oldest unread falls to make room");
    }

    @Test
    void fullInboxEvictsOldestReadBeforeUnread() throws Exception {
        AiSession target = stubSession("evict-target", "TargetAI", null);
        broker.setMaxInboxSize(() -> 2);
        broker.register(target);

        String readId = broker.sendMessage("s", "evict-target", "old-read", "body", null);
        broker.sendMessage("s", "evict-target", "unread-keep", "body", null);
        broker.readMessageWithResult("evict-target", target.secret(), readId);
        assertTrue(broker.awaitNotifierIdle(2, TimeUnit.SECONDS));

        broker.sendMessage("s", "evict-target", "newest", "body", null);
        assertTrue(broker.awaitNotifierIdle(2, TimeUnit.SECONDS));

        Set<String> subjects = Set.copyOf(subjectsOf("evict-target", target.secret()));
        assertEquals(2, subjects.size());
        assertTrue(subjects.contains("unread-keep"), "unread message must survive while a read one exists");
        assertTrue(subjects.contains("newest"));
        assertFalse(subjects.contains("old-read"), "the oldest READ message is the eviction victim");
    }

    @Test
    void evictedPendingReplyProducesVisibleCapacityCheckedFailureNotice() throws Exception {
        CountDownLatch eventArrived = new CountDownLatch(1);
        AtomicReference<AiInboxMessageEvent> seenEvent = new AtomicReference<>();
        busListener = (AiPropertyEvent event) -> {
            if (event instanceof AiInboxMessageEvent inboxEvent) {
                seenEvent.set(inboxEvent);
                eventArrived.countDown();
            }
        };
        GlobalPropertyBus.getInstance().addListener(busListener);

        AiSession sender = stubSession("fail-sender", "SenderAI", null);
        AiSession target = stubSession("fail-target", "TargetAI", null);
        broker.setMaxInboxSize(() -> 2);
        broker.register(sender);
        broker.register(target);

        broker.sendMessage("fail-sender", "fail-target", "need answer", "body", null,
                false, true, false);
        broker.sendMessage("other", "fail-target", "filler", "body", null);
        // Third send fills the inbox and evicts the oldest entry — every entry is unread, so the
        // oldest unread (the expects-reply message) falls and its sender must be told.
        broker.sendMessage("other2", "fail-target", "trigger", "body", null);
        assertTrue(broker.awaitNotifierIdle(2, TimeUnit.SECONDS));

        List<String> subjects = subjectsOf("fail-sender", sender.secret());
        assertTrue(subjects.stream().anyMatch(s -> s.startsWith("Delivery failed")),
                "failure notice stored in sender inbox: " + subjects);
        assertTrue(broker.listInbox("fail-sender", sender.secret()).size() <= 2,
                "notice insertion respected the capacity policy");

        assertTrue(eventArrived.await(2, TimeUnit.SECONDS), "AiInboxMessageEvent fired for the notice");
        assertEquals("fail-sender", seenEvent.get().targetSessionId());
        assertTrue(seenEvent.get().subject().startsWith("Delivery failed"));
    }

    @Test
    void systemNoticeDoesNotDisplaceUnreadMailAndIsDroppedWhenFull() throws Exception {
        AiSession sender = stubSession("sys-sender", "SenderAI", null);
        AiSession target = stubSession("sys-target", "TargetAI", null);
        broker.setMaxInboxSize(() -> 2);
        broker.register(sender);
        broker.register(target);

        // Sender's inbox: two unread messages — no read entry a system notice could displace.
        broker.sendMessage("x", "sys-sender", "a", "body", null);
        broker.sendMessage("y", "sys-sender", "b", "body", null);
        // Target's inbox: an unread expects-reply message plus filler.
        broker.sendMessage("sys-sender", "sys-target", "Q", "body", null, false, true, false);
        broker.sendMessage("z", "sys-target", "filler", "body", null);

        // Triggering send evicts the pending unread message; its failure notice targets the
        // sender whose inbox is full of unread mail, so the notice must be dropped rather than
        // displacing an unread message or cascading another eviction.
        broker.sendMessage("w", "sys-target", "trigger", "body", null);
        assertTrue(broker.awaitNotifierIdle(2, TimeUnit.SECONDS));

        List<String> subjects = subjectsOf("sys-sender", sender.secret());
        assertEquals(List.of("a", "b"), subjects,
                "system notice neither displaced unread mail nor exceeded capacity");
    }

    @Test
    void burstIsCoalescedWithoutLosingAnnouncements() throws Exception {
        int total = 250;
        CountDownLatch allDelivered = new CountDownLatch(total);
        AtomicInteger deliveries = new AtomicInteger();
        AiSession target = stubSession("burst-target", "TargetAI", () -> {
            deliveries.incrementAndGet();
            allDelivered.countDown();
        });
        broker.setMaxInboxSize(() -> 10_000);
        broker.register(target);

        for (int i = 0; i < total; i++) {
            broker.sendMessage("burst-sender", "burst-target", "s" + i, "body", null);
        }

        assertTrue(allDelivered.await(15, TimeUnit.SECONDS),
                "every message announced despite burst that would overflow the old queue of 100");
        assertEquals(total, deliveries.get(), "no duplicate announcements either");
        assertTrue(broker.awaitNotifierIdle(2, TimeUnit.SECONDS));
    }

    @Test
    void replyFromNonOwnerConsumesNothingAndInheritsNothing() {
        AiSession owner = stubSession("hijack-owner", "OwnerAI", null);
        AiSession impostor = stubSession("hijack-impostor", "ImpostorAI", null);
        AiSession originalSender = stubSession("hijack-sender", "SenderAI", null);
        broker.register(owner);
        broker.register(impostor);
        broker.register(originalSender);

        String origId = broker.sendMessage("hijack-sender", "hijack-owner", "Q", "question", null,
                false, true, true);

        // The impostor quotes someone else's message id.
        String hijackId = broker.sendMessage("hijack-impostor", "hijack-sender", "Re: Q", "spoofed", origId);

        AiInboxMessage hijacked = broker.listInbox("hijack-sender", originalSender.secret()).stream()
                .filter(m -> m.id().equals(hijackId)).findFirst().orElseThrow();
        assertFalse(hijacked.important(), "impostor must not inherit replyImportant priority");

        AiInboxMessage original = broker.listInbox("hijack-owner", owner.secret()).stream()
                .filter(m -> m.id().equals(origId)).findFirst().orElseThrow();
        assertNull(original.respondedAt(), "impostor must not stamp respondedAt on someone else's message");

        // The genuine recipient's later reply still consumes the expectation and gets the upgrade.
        String realReplyId = broker.sendMessage("hijack-owner", "hijack-sender", "Re: Q", "real answer", origId);
        AiInboxMessage realReply = broker.listInbox("hijack-sender", originalSender.secret()).stream()
                .filter(m -> m.id().equals(realReplyId)).findFirst().orElseThrow();
        assertTrue(realReply.important(), "genuine reply inherits replyImportant");

        // And exactly once: the expectation was consumed by the genuine reply.
        String secondReplyId = broker.sendMessage("hijack-owner", "hijack-sender", "Re: Q again", "again", origId);
        AiInboxMessage secondReply = broker.listInbox("hijack-sender", originalSender.secret()).stream()
                .filter(m -> m.id().equals(secondReplyId)).findFirst().orElseThrow();
        assertFalse(secondReply.important(), "upgrade applies only once per expectation");
    }

    @Test
    void purgeOfExpiredUnansweredExpectationNotifiesSender() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AiSession sender = stubSession("purge-sender", "SenderAI", delivered::countDown);
        AiSession target = stubSession("purge-target", "TargetAI", null);
        broker.register(sender);
        broker.register(target);

        String id = broker.sendMessage("purge-sender", "purge-target", "answer me", "body", null,
                false, true, false);
        broker.readMessageWithResult("purge-target", target.secret(), id);

        broker.purgeExpiredRead(System.currentTimeMillis() + 1_000, 0L);

        List<String> subjects = subjectsOf("purge-sender", sender.secret());
        assertTrue(subjects.stream().anyMatch(s -> s.startsWith("No reply")),
                "expired expectation must notify the sender, got: " + subjects);
        assertTrue(delivered.await(2, TimeUnit.SECONDS), "notice routed through the notifier path");
    }

    @Test
    void purgeIsSilentWhenExpectationAlreadyAnswered() throws Exception {
        AiSession sender = stubSession("answered-sender", "SenderAI", null);
        AiSession target = stubSession("answered-target", "TargetAI", null);
        broker.register(sender);
        broker.register(target);

        String id = broker.sendMessage("answered-sender", "answered-target", "answer me", "body", null,
                false, true, false);
        // Genuine reply consumes the expectation before expiry.
        broker.sendMessage("answered-target", "answered-sender", "Re: answer me", "here", id);
        broker.readMessageWithResult("answered-target", target.secret(), id);
        assertTrue(broker.awaitNotifierIdle(2, TimeUnit.SECONDS));
        int before = broker.listInbox("answered-sender", sender.secret()).size();

        broker.purgeExpiredRead(System.currentTimeMillis() + 1_000, 0L);

        List<String> subjects = subjectsOf("answered-sender", sender.secret());
        assertEquals(before, subjects.size(), "answered expectation expires silently");
        assertTrue(subjects.stream().noneMatch(s -> s.startsWith("No reply")), () -> subjects.toString());
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.AbstractNotification;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSessionCallback;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for F4 reply tracking as visible state: replyToMessageId refusal for unknown or mis-addressed ids, markReplied
 * clearing a pending expectation exactly like a real reply, owed-reply listing, the expiry notice wording, and the
 * [Awaiting reply]/[Replied] summary flags.
 */
class AiSessionInboxBrokerReplyTrackingTest {

    private static AiSession stubSession(String id, String name) {
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

    @BeforeEach
    void setUp() {
        broker = new AiSessionInboxBroker();
    }

    @Test
    void validateReplyToAcceptsBlankOrOwnInboxMessage() {
        AiSession sender = stubSession("vr-a", "SenderAI");
        AiSession target = stubSession("vr-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        assertNull(broker.validateReplyTo("vr-b", null), "null replyToMessageId is always allowed");
        assertNull(broker.validateReplyTo("vr-b", "   "), "blank replyToMessageId is always allowed");

        String id = broker.sendMessage("vr-a", "vr-b", "question", "body", null);
        assertNull(broker.validateReplyTo("vr-b", id), "own inbox message is a valid reply target");
    }

    @Test
    void validateReplyToRefusesUnknownId() {
        AiSession target = stubSession("vr-u", "TargetAI");
        broker.register(target);

        String reason = broker.validateReplyTo("vr-u", "no-such-message");
        assertNotNull(reason);
        assertTrue(reason.contains("no-such-message"), reason);
        assertTrue(reason.contains("does not match any message"), reason);
        assertTrue(reason.contains("send again without replyToMessageId"), reason);
    }

    @Test
    void validateReplyToRefusesMessageAddressedToSomeoneElse() {
        AiSession sender = stubSession("vr-x", "SenderAI");
        AiSession recipient = stubSession("vr-r", "RecipientAI");
        AiSession onlooker = stubSession("vr-o", "OnlookerAI");
        broker.register(sender);
        broker.register(recipient);
        broker.register(onlooker);

        String id = broker.sendMessage("vr-x", "vr-r", "not for you", "body", null);

        String reason = broker.validateReplyTo("vr-o", id);
        assertNotNull(reason);
        assertTrue(reason.contains("is not a message sent to you"), reason);
    }

    @Test
    void sendMessageWithInvalidReplyToIdIsRefusedAndSendsNothing() {
        AiSession sender = stubSession("sr-a", "SenderAI");
        AiSession target = stubSession("sr-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        String unknownId = broker.sendMessage("sr-a", "sr-b", "Re: ghost", "answer", "no-such-message");
        assertNull(unknownId, "send with an unknown replyToMessageId is refused");
        assertEquals(0, broker.listInbox("sr-b", target.secret()).size(), "nothing was delivered");

        String origId = broker.sendMessage("sr-x", "sr-b", "question", "body", null);
        String foreignId = broker.sendMessage("sr-a", "sr-b", "Re: spoof", "hijack", origId);
        assertNull(foreignId, "send with another session's replyToMessageId is refused");
        assertEquals(1, broker.listInbox("sr-b", target.secret()).size(), "impostor reply was not delivered");
        assertNull(broker.listInbox("sr-b", target.secret()).get(0).respondedAt(), "no expectation consumed by impostor");
    }

    @Test
    void markRepliedStampsExactlyOnceAndConsumesPendingExpectation() {
        AiSession sender = stubSession("mr-a", "SenderAI");
        AiSession target = stubSession("mr-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        String id = broker.sendMessage("mr-a", "mr-b", "answer me", "body", null, false, true, false);

        assertEquals(AiSessionInboxBroker.MarkRepliedResultEnum.MARKED,
                     broker.markReplied("mr-b", id), "first mark succeeds");
        assertEquals(AiSessionInboxBroker.MarkRepliedResultEnum.ALREADY_REPLIED,
                     broker.markReplied("mr-b", id), "second mark is idempotent");

        // Marking consumed the expectation exactly like a real reply: expiry is now silent for the sender.
        broker.readMessageWithResult("mr-b", target.secret(), id);
        int before = broker.listInbox("mr-a", sender.secret()).size();
        broker.purgeExpiredRead(System.currentTimeMillis() + 1_000, 0L);
        List<String> subjects = broker.listInbox("mr-a", sender.secret()).stream()
                .map(AiInboxMessage::subject)
                .collect(Collectors.toList());
        assertEquals(before, subjects.size(), "marked expectation expires without a no-reply notice");
        assertTrue(subjects.stream().noneMatch(s -> s.startsWith("No reply")), () -> subjects.toString());
    }

    @Test
    void markRepliedRejectsNonExpectingForeignAndUnknownMessages() {
        AiSession sender = stubSession("mr2-a", "SenderAI");
        AiSession target = stubSession("mr2-b", "TargetAI");
        AiSession onlooker = stubSession("mr2-o", "OnlookerAI");
        broker.register(sender);
        broker.register(target);
        broker.register(onlooker);

        String plainId = broker.sendMessage("mr2-a", "mr2-b", "FYI", "body", null);
        assertEquals(AiSessionInboxBroker.MarkRepliedResultEnum.NOT_EXPECTING_REPLY,
                     broker.markReplied("mr2-b", plainId), "a message that never asked for a reply has nothing to mark");

        String askedId = broker.sendMessage("mr2-a", "mr2-b", "answer me", "body", null, false, true, false);
        assertEquals(AiSessionInboxBroker.MarkRepliedResultEnum.NOT_FOUND,
                     broker.markReplied("mr2-o", askedId), "only the addressed recipient may mark replied");
        assertEquals(AiSessionInboxBroker.MarkRepliedResultEnum.NOT_FOUND,
                     broker.markReplied("mr2-b", "no-such-message"), "unknown id is not found");
    }

    @Test
    void listOwedRepliesShowsOnlyUnansweredExpectationToSessionOldestFirst() {
        AiSession sender = stubSession("ow-a", "SenderAI");
        AiSession target = stubSession("ow-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        broker.sendMessage("ow-a", "ow-b", "older", "body", null, false, true, false);
        broker.sendMessage("ow-a", "ow-b", "fyi", "no reply wanted", null);
        broker.sendMessage("ow-a", "ow-b", "newer", "body", null, false, true, false);

        List<String> owed = broker.listOwedReplies("ow-b").stream()
                .map(AiInboxMessage::subject)
                .collect(Collectors.toList());
        assertEquals(List.of("older", "newer"), owed,
                     "only expects-reply un-responded mail owed BY this session, oldest first");

        String newerId = broker.listOwedReplies("ow-b").stream()
                .filter(m -> "newer".equals(m.subject()))
                .findFirst().orElseThrow().id();
        assertEquals(AiSessionInboxBroker.MarkRepliedResultEnum.MARKED, broker.markReplied("ow-b", newerId));
        owed = broker.listOwedReplies("ow-b").stream()
                .map(AiInboxMessage::subject)
                .collect(Collectors.toList());
        assertEquals(List.of("older"), owed, "marking drops the message from the owed list");

        String olderId = broker.listOwedReplies("ow-b").get(0).id();
        broker.sendMessage("ow-b", "ow-a", "Re: older", "answer", olderId);
        assertTrue(broker.listOwedReplies("ow-b").isEmpty(), "a real reply also clears the owed list");
    }

    @Test
    void listOwedRepliesDoesNotIncludeSentMessages() {
        AiSession sender = stubSession("ow2-a", "SenderAI");
        AiSession target = stubSession("ow2-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        broker.sendMessage("ow2-a", "ow2-b", "my question", "body", null, false, true, false);
        assertTrue(broker.listOwedReplies("ow2-a").isEmpty(),
                   "a message I sent is someone else's owed reply, not mine");
    }

    @Test
    void formatSummaryShowsAwaitingReplyUntilReplied() {
        AiSession sender = stubSession("fs-a", "SenderAI");
        AiSession target = stubSession("fs-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        String id = broker.sendMessage("fs-a", "fs-b", "answer me", "body", null, false, true, false);
        AiInboxMessage message = broker.listInbox("fs-b", target.secret()).get(0);

        assertTrue(message.formatSummary().contains("[Awaiting reply]"),
                   "unanswered expectation is flagged as awaiting reply");
        assertFalse(message.formatSummary().contains("[Replied]"));

        assertEquals(AiSessionInboxBroker.MarkRepliedResultEnum.MARKED, broker.markReplied("fs-b", id));
        AiInboxMessage replied = broker.listInbox("fs-b", target.secret()).stream()
                .filter(m -> m.id().equals(id)).findFirst().orElseThrow();
        assertTrue(replied.formatSummary().contains("[Replied]"),
                   "answered expectation shows [Replied] instead of [Awaiting reply]");
        assertFalse(replied.formatSummary().contains("[Awaiting reply]"));
    }

    @Test
    void expiredNoticeNamesTheRecipient() {
        AiSession sender = stubSession("ex-a", "SenderAI");
        AiSession target = stubSession("ex-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        String id = broker.sendMessage("ex-a", "ex-b", "answer me", "body", null, false, true, false);
        broker.readMessageWithResult("ex-b", target.secret(), id);
        broker.purgeExpiredRead(System.currentTimeMillis() + 1_000, 0L);

        String notice = broker.listInbox("ex-a", sender.secret()).stream()
                .filter(m -> m.subject().startsWith("No reply"))
                .findFirst().orElseThrow().body();
        assertTrue(notice.contains("expired unanswered"), notice);
        assertTrue(notice.contains("was never marked replied by session TargetAI"), notice);
        assertNotNull(notice);
    }

    @Test
    void deleteDropsPendingExpectationSilentlyAndExitStaysSilent() throws Exception {
        AiSession sender = stubSession("del-a", "SenderAI");
        AiSession target = stubSession("del-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        String id = broker.sendMessage("del-a", "del-b", "answer me", "body", null, false, true, false);
        assertEquals(1, broker.deleteMessages("del-b", target.secret(), List.of(id)), "message deleted");
        assertFalse(pendingRepliesOf(broker).containsKey(id),
                    "delete drops the pending expectation with the message, before any exit");

        // With the stale entry gone, the deleter's exit can no longer fabricate a "no reply" notice.
        broker.unregister("del-b");
        List<String> subjects = broker.listInbox("del-a", sender.secret()).stream()
                .map(AiInboxMessage::subject)
                .collect(Collectors.toList());
        assertTrue(subjects.stream().noneMatch(s -> s.startsWith("No reply")),
                   "deleted expectation must not notify on exit: " + subjects);
        assertFalse(pendingRepliesOf(broker).containsKey(id), "pendingReplies loses the deleted id");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pendingRepliesOf(AiSessionInboxBroker broker) throws Exception {
        Field f = AiSessionInboxBroker.class.getDeclaredField("pendingReplies");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(broker);
    }
}

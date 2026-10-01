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
 * Tests reply tracking, including invalid reply targets delivered as new mail and deleted expectations
 * retained as non-visible tombstones until they are resolved or expire.
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

        String blankReply = broker.sendMessage("vr-a", "vr-b", "blank reply", "body", "   ");
        AiInboxMessage blankMessage = broker.listInbox("vr-b", target.secret()).stream()
                .filter(m -> m.id().equals(blankReply)).findFirst().orElseThrow();
        assertNull(blankMessage.replyToId(), "a blank reply id is stored as absent");
    }

    @Test
    void validateReplyToExplainsUnknownIdWithoutClaimingTheSendWasBlocked() {
        AiSession target = stubSession("vr-u", "TargetAI");
        broker.register(target);

        String reason = broker.validateReplyTo("vr-u", "no-such-message");
        assertNotNull(reason);
        assertTrue(reason.contains("no-such-message"), reason);
        assertTrue(reason.contains("unknown"), reason);
        assertTrue(reason.contains("delivered as a new message"), reason);
        assertFalse(reason.contains("nothing was sent"), reason);
    }

    @Test
    void validateReplyToExplainsMessageAddressedToSomeoneElse() {
        AiSession sender = stubSession("vr-x", "SenderAI");
        AiSession recipient = stubSession("vr-r", "RecipientAI");
        AiSession onlooker = stubSession("vr-o", "OnlookerAI");
        broker.register(sender);
        broker.register(recipient);
        broker.register(onlooker);

        String id = broker.sendMessage("vr-x", "vr-r", "not for you", "body", null);

        String reason = broker.validateReplyTo("vr-o", id);
        assertNotNull(reason);
        assertTrue(reason.contains("addressed to RecipientAI"), reason);
        assertTrue(reason.contains("delivered as a new message"), reason);
    }

    @Test
    void invalidReplyToDeliversUnlinkedMessageWithoutConsumingAnotherSessionsExpectation() throws Exception {
        AiSession sender = stubSession("sr-a", "SenderAI");
        AiSession target = stubSession("sr-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        String unknownId = broker.sendMessage("sr-a", "sr-b", "Re: ghost", "answer", "no-such-message");
        assertNotNull(unknownId, "an unknown reply id still delivers a new message");
        AiInboxMessage unknownReply = broker.listInbox("sr-b", target.secret()).get(0);
        assertNull(unknownReply.replyToId(), "invalid reply ids are not linked to delivered mail");

        String origId = broker.sendMessage("sr-x", "sr-b", "question", "body", null, false, true, false);
        String foreignId = broker.sendMessage("sr-a", "sr-b", "Re: spoof", "hijack", origId);
        assertNotNull(foreignId, "a foreign reply id still delivers a new message");
        AiInboxMessage original = broker.listInbox("sr-b", target.secret()).stream()
                .filter(m -> m.id().equals(origId)).findFirst().orElseThrow();
        AiInboxMessage foreignReply = broker.listInbox("sr-b", target.secret()).stream()
                .filter(m -> m.id().equals(foreignId)).findFirst().orElseThrow();
        assertNull(foreignReply.replyToId(), "the impostor message must be unlinked");
        assertNull(original.respondedAt(), "an impostor must not consume another session's expectation");
        assertTrue(pendingRepliesOf(broker).containsKey(origId), "the original expectation remains pending");
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
    void deleteKeepsReplyTrackingUntilTheRecipientReplies() throws Exception {
        AiSession sender = stubSession("del-a", "SenderAI");
        AiSession target = stubSession("del-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        String id = broker.sendMessage("del-a", "del-b", "answer me", "body", null, false, true, false);
        assertEquals(1, broker.deleteMessages("del-b", target.secret(), List.of(id)), "message deleted");
        assertFalse(broker.listInbox("del-b", target.secret()).stream().anyMatch(m -> m.id().equals(id)));
        assertTrue(broker.listOwedReplies("del-b").isEmpty(), "deleted tombstones stay out of owed-reply reminders");
        assertTrue(pendingRepliesOf(broker).containsKey(id), "the deleted expectation remains pending");
        assertTrue(deletedTombstonesOf(broker).containsKey(id), "the deleted message remains reply-trackable");

        String replyId = broker.sendMessage("del-b", "del-a", "Re: answer me", "answer", id);
        assertNotNull(replyId);
        assertFalse(pendingRepliesOf(broker).containsKey(id), "a reply cleans up the expectation");
        assertFalse(deletedTombstonesOf(broker).containsKey(id), "a reply cleans up its tombstone");
    }

    @Test
    void markRepliedResolvesDeletedExpectationWithoutANoReplyNotice() throws Exception {
        AiSession sender = stubSession("del-mark-a", "SenderAI");
        AiSession target = stubSession("del-mark-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        String id = broker.sendMessage("del-mark-a", "del-mark-b", "answer me", "body", null, false, true, false);
        broker.deleteMessages("del-mark-b", target.secret(), List.of(id));
        assertEquals(AiSessionInboxBroker.MarkRepliedResultEnum.MARKED, broker.markReplied("del-mark-b", id));
        broker.purgeExpiredRead(System.currentTimeMillis() + 1_000, 0L);

        assertFalse(deletedTombstonesOf(broker).containsKey(id));
        assertTrue(broker.listInbox("del-mark-a", sender.secret()).stream()
                .noneMatch(m -> m.subject().startsWith("No reply")));
    }

    @Test
    void deletedUnansweredExpectationNotifiesSenderOnExitAndIsCleanedUp() throws Exception {
        AiSession sender = stubSession("del-exit-a", "SenderAI");
        AiSession target = stubSession("del-exit-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        String id = broker.sendMessage("del-exit-a", "del-exit-b", "answer me", "body", null, false, true, false);
        broker.deleteMessages("del-exit-b", target.secret(), List.of(id));
        broker.unregister("del-exit-b");

        assertTrue(broker.listInbox("del-exit-a", sender.secret()).stream()
                .anyMatch(m -> m.subject().startsWith("No reply")));
        assertFalse(pendingRepliesOf(broker).containsKey(id));
        assertFalse(deletedTombstonesOf(broker).containsKey(id));
    }

    @Test
    void deletedUnansweredExpectationNotifiesSenderOnExpiryAndIsCleanedUp() throws Exception {
        AiSession sender = stubSession("del-expire-a", "SenderAI");
        AiSession target = stubSession("del-expire-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        String id = broker.sendMessage("del-expire-a", "del-expire-b", "answer me", "body", null, false, true, false);
        broker.deleteMessages("del-expire-b", target.secret(), List.of(id));
        broker.purgeExpiredRead(System.currentTimeMillis() + 1_000, 0L);

        assertTrue(broker.listInbox("del-expire-a", sender.secret()).stream()
                .anyMatch(m -> m.subject().startsWith("No reply")));
        assertFalse(pendingRepliesOf(broker).containsKey(id));
        assertFalse(deletedTombstonesOf(broker).containsKey(id));
    }

    @Test
    void deletedReadMessageUsesItsOriginalReadTimeForExpiry() throws Exception {
        AiSession sender = stubSession("clock-a", "SenderAI");
        AiSession target = stubSession("clock-b", "TargetAI");
        broker.register(sender);
        broker.register(target);
        String id = broker.sendMessage("clock-a", "clock-b", "answer me", "body", null, false, true, false);
        broker.readMessageWithResult("clock-b", target.secret(), id);
        Thread.sleep(5);
        broker.deleteMessages("clock-b", target.secret(), List.of(id));
        broker.purgeExpiredRead(System.currentTimeMillis(), 1L);

        assertTrue(broker.listInbox("clock-a", sender.secret()).stream()
                .anyMatch(m -> m.subject().startsWith("No reply")));
        assertFalse(deletedTombstonesOf(broker).containsKey(id));
    }

    @Test
    void deletingAnAlreadyAnsweredMessageCreatesNoTombstoneOrNotice() throws Exception {
        AiSession sender = stubSession("answered-del-a", "SenderAI");
        AiSession target = stubSession("answered-del-b", "TargetAI");
        broker.register(sender);
        broker.register(target);
        String id = broker.sendMessage("answered-del-a", "answered-del-b", "answer me", "body", null, false, true, false);
        broker.markReplied("answered-del-b", id);
        broker.deleteMessages("answered-del-b", target.secret(), List.of(id));
        broker.unregister("answered-del-b");

        assertFalse(deletedTombstonesOf(broker).containsKey(id));
        assertTrue(broker.listInbox("answered-del-a", sender.secret()).stream()
                .noneMatch(m -> m.subject().startsWith("No reply")));
    }

    @Test
    void deletingTheSameMessageTwiceCreatesOnlyOneNotice() throws Exception {
        AiSession sender = stubSession("twice-a", "SenderAI");
        AiSession target = stubSession("twice-b", "TargetAI");
        broker.register(sender);
        broker.register(target);
        String id = broker.sendMessage("twice-a", "twice-b", "answer me", "body", null, false, true, false);
        assertEquals(1, broker.deleteMessages("twice-b", target.secret(), List.of(id)));
        assertEquals(0, broker.deleteMessages("twice-b", target.secret(), List.of(id)));
        broker.unregister("twice-b");

        assertEquals(1, broker.listInbox("twice-a", sender.secret()).stream()
                .filter(m -> m.subject().startsWith("No reply")).count());
    }

    @Test
    void tombstonesDoNotConsumeInboxCapacity() {
        AiSession sender = stubSession("capacity-a", "SenderAI");
        AiSession target = stubSession("capacity-b", "TargetAI");
        broker.register(sender);
        broker.register(target);
        broker.setMaxInboxSize(() -> 1);
        String deleted = broker.sendMessage("capacity-a", "capacity-b", "answer me", "body", null, false, true, false);
        broker.deleteMessages("capacity-b", target.secret(), List.of(deleted));
        String visible = broker.sendMessage("capacity-a", "capacity-b", "visible", "body", null);

        assertEquals(List.of(visible), broker.listInbox("capacity-b", target.secret()).stream()
                .map(AiInboxMessage::id).toList());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pendingRepliesOf(AiSessionInboxBroker broker) throws Exception {
        Field f = AiSessionInboxBroker.class.getDeclaredField("pendingReplies");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(broker);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deletedTombstonesOf(AiSessionInboxBroker broker) throws Exception {
        Field f = AiSessionInboxBroker.class.getDeclaredField("deletedMessageTombstones");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(broker);
    }

    @Test
    void answeredButUnreadMessageIsNotListedOnExit() {
        AiSession sender = stubSession("ug-a", "SenderAI");
        AiSession target = stubSession("ug-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        String id = broker.sendMessage("ug-a", "ug-b", "answer me", "body", null, false, true, false);
        // Answered with a genuine replyToMessageId but never read with PeerMessageRead: the answer proves it
        // was handled, so the exit must not claim it was "never opened".
        broker.sendMessage("ug-b", "ug-a", "Re: answer me", "here it is", id);

        broker.unregister("ug-b");

        List<String> subjects = broker.listInbox("ug-a", sender.secret()).stream()
                .map(AiInboxMessage::subject)
                .collect(Collectors.toList());
        assertEquals(List.of("Re: answer me"), subjects,
                "answered-but-unread message must not appear in an exit notice: " + subjects);
    }

    @Test
    void unreadUnansweredMessageIsListedWithNotReadWording() {
        AiSession sender = stubSession("ug2-a", "SenderAI");
        AiSession target = stubSession("ug2-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        broker.sendMessage("ug2-a", "ug2-b", "open me", "body", null);
        broker.unregister("ug2-b");

        AiInboxMessage notice = broker.listInbox("ug2-a", sender.secret()).stream()
                .filter(m -> m.subject().startsWith("Not read"))
                .findFirst().orElseThrow();
        assertTrue(notice.subject().contains("TargetAI"), notice.subject());
        assertTrue(notice.body().contains("exited with message(s) from you it never opened: \"open me\""),
                notice.body());
        assertTrue(notice.body().contains("They were delivered to its inbox but not read."), notice.body());
    }

    @Test
    void noReplyPathStillFiresForUnansweredExpectsReplyOnExit() {
        AiSession sender = stubSession("ug3-a", "SenderAI");
        AiSession target = stubSession("ug3-b", "TargetAI");
        broker.register(sender);
        broker.register(target);

        broker.sendMessage("ug3-a", "ug3-b", "answer me", "body", null, false, true, false);
        broker.unregister("ug3-b");

        List<String> subjects = broker.listInbox("ug3-a", sender.secret()).stream()
                .map(AiInboxMessage::subject)
                .collect(Collectors.toList());
        assertTrue(subjects.stream().anyMatch(s -> s.startsWith("No reply")),
                "unanswered expects-reply message still fires the no-reply notice: " + subjects);
        assertTrue(subjects.stream().noneMatch(s -> s.startsWith("Not read")),
                "must not also be listed in the generic not-read notice: " + subjects);
    }

}

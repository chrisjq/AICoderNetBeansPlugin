package kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiInboxMessageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.DeliverIncomingMessageNotification;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.SimpleNotification;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.GlobalPropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;

public final class AiSessionInboxBroker {

    private static final Logger LOG = Logger.getLogger(AiSessionInboxBroker.class.getName());
    private static volatile AiSessionInboxBroker instance;

    public static AiSessionInboxBroker getInstance() {
        AiSessionInboxBroker lInstance = AiSessionInboxBroker.instance;
        if (lInstance == null) {
            synchronized (AiSessionInboxBroker.class) {
                lInstance = AiSessionInboxBroker.instance;
                if (lInstance == null) {
                    AiSessionInboxBroker b = new AiSessionInboxBroker();
                    b.setMaxInboxSize(kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings::getInboxMaxSize);
                    b.setRetentionMinutes(kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings::getInboxRetentionMinutes);
                    b.startSweeper();
                    AiSessionInboxBroker.instance = lInstance = b;
                }
            }
        }
        return lInstance;
    }

    private final Object lock = new Object();

    // Both maps are guarded by `lock`. inbox is keyed by sessionId; inboxMessageById
    // mirrors every stored message for O(1) lookup by message ID.
    private final Map<String, List<AiInboxMessage>> inbox = new HashMap<>();
    private final Map<String, AiInboxMessage> inboxMessageById = new HashMap<>();

    // pendingReplies is guarded by `lock`.
    private final Map<String, PendingReplyEntry> pendingReplies = new HashMap<>();

    // Effective max inbox size. Defaults to a literal so unit tests never touch
    // NbPreferences; getInstance() swaps in PluginSettings::getInboxMaxSize for prod.
    private volatile IntSupplier maxInboxSize = () -> 1000;

    private volatile IntSupplier retentionMinutes = () -> 0;
    private ScheduledExecutorService sweeper;

    private final ExecutorService notifier = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "ai-inbox-notifier");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());

    public AiSessionInboxBroker() {
    }

    void setMaxInboxSize(IntSupplier supplier) {
        this.maxInboxSize = supplier;
    }

    void setRetentionMinutes(IntSupplier supplier) {
        this.retentionMinutes = supplier;
    }

    private AiSession sessionFromRegistry(String sessionId) {
        var abs = SessionRegistry.get(sessionId);
        return abs != null ? abs.getAiSession() : null;
    }

    public void register(AiSession session) {
        synchronized (lock) {
            inbox.computeIfAbsent(session.id(), k -> new ArrayList<>());
        }
    }

    /**
     * Unregisters a session. Senders of unread messages are notified of
     * non-delivery. Senders whose messages were read but not yet replied to
     * (expectsReply=true) receive a "no reply" notification; if replyImportant
     * was set, the sender is interrupted regardless of their own setting.
     */
    public void unregister(String sessionId) {
        List<AiInboxMessage> unread;
        List<PendingReplyEntry> pendingAsRecipient;
        Set<String> activeIds;
        String exitingName;
        synchronized (lock) {
            AiSession exitingSession = sessionFromRegistry(sessionId);
            exitingName = exitingSession != null ? exitingSession.name() : sessionId;
            List<AiInboxMessage> removed = inbox.remove(sessionId);
            unread = removed != null ? removed : List.of();
            unread.forEach(m -> inboxMessageById.remove(m.id()));
            // Collect pending entries where the exiting session was the recipient
            pendingAsRecipient = pendingReplies.values().stream()
                    .filter(e -> e.toSessionId().equals(sessionId))
                    .toList();
            pendingAsRecipient.forEach(e -> pendingReplies.remove(e.messageId()));
            // Remove orphaned entries where the exiting session was the sender
            pendingReplies.entrySet().removeIf(e -> e.getValue().fromSessionId().equals(sessionId));
            activeIds = new java.util.HashSet<>(inbox.keySet());
        }
        if (unread.isEmpty() && pendingAsRecipient.isEmpty()) {
            return;
        }
        // Messages covered by pending entries get a "no reply" notification below,
        // not the generic "undelivered" one, to avoid notifying the sender twice.
        Set<String> pendingMessageIds = pendingAsRecipient.stream()
                .map(PendingReplyEntry::messageId)
                .collect(Collectors.toSet());

        // "Undelivered" notifications for unread messages without an expectsReply entry
        Map<String, Boolean> senderImportant = new LinkedHashMap<>();
        Map<String, List<String>> senderSubjects = new LinkedHashMap<>();
        for (AiInboxMessage msg : unread) {
            if (pendingMessageIds.contains(msg.id())) {
                continue;
            }
            String from = msg.fromSessionId();
            senderImportant.merge(from, msg.important(), Boolean::logicalOr);
            senderSubjects.computeIfAbsent(from, k -> new ArrayList<>())
                    .add(msg.subject() != null ? msg.subject() : "(no subject)");
        }
        for (Map.Entry<String, Boolean> entry : senderImportant.entrySet()) {
            String senderId = entry.getKey();
            if (!activeIds.contains(senderId)) {
                continue;
            }
            boolean wasImportant = entry.getValue();
            AiSession senderHandle = sessionFromRegistry(senderId);
            if (senderHandle == null || !senderHandle.allowsInterAiComms()) {
                continue;
            }
            List<String> subjects = senderSubjects.get(senderId);
            String notifId = UUID.randomUUID().toString();
            String notifSubject = "Undelivered — session " + sessionId + " exited";
            String notifBody = "Session '" + sessionId + "' exited with "
                    + subjects.size() + " unread message(s) from you. Subjects: "
                    + subjects.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", "));
            AiInboxMessage notification = new AiInboxMessage(notifId, sessionId, senderId,
                    notifSubject, notifBody, null, wasImportant, false, false,
                    java.time.Instant.now(), null, null);
            boolean inserted;
            synchronized (lock) {
                inserted = inbox.containsKey(senderId);
                if (inserted) {
                    inbox.computeIfAbsent(senderId, k -> new ArrayList<>()).add(notification);
                    inboxMessageById.put(notifId, notification);
                }
            }
            if (inserted) {
                GlobalPropertyBus.getInstance().fire(new AiInboxMessageEvent(senderId, notifId, notifSubject, exitingName));
                String deliveryNote = "Session " + sessionId + " exited — your message(s) were not delivered: "
                        + subjects.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", "));
                notifier.submit(() -> senderHandle.deliverIncomingMessage(sessionId, new SimpleNotification(deliveryNote)));
                if (wasImportant) {
                    notifier.submit(() -> senderHandle.requestGracefulInterrupt(InterruptTypeEnum.Mail));
                }
            }
        }

        // "No reply" notifications for expectsReply messages (read or unread)
        for (PendingReplyEntry pendingEntry : pendingAsRecipient) {
            String senderId = pendingEntry.fromSessionId();
            if (!activeIds.contains(senderId)) {
                continue;
            }
            AiSession senderHandle = sessionFromRegistry(senderId);
            if (senderHandle == null || !senderHandle.allowsInterAiComms()) {
                continue;
            }
            String notifId = UUID.randomUUID().toString();
            String notifSubject = "No reply — session " + sessionId + " exited";
            String notifBody = "Session '" + sessionId + "' exited without responding to your message."
                    + " Subject: \"" + pendingEntry.subject() + "\"";
            AiInboxMessage notification = new AiInboxMessage(notifId, sessionId, senderId,
                    notifSubject, notifBody, pendingEntry.messageId(), pendingEntry.replyImportant(), false, false,
                    java.time.Instant.now(), null, null);
            boolean inserted;
            synchronized (lock) {
                inserted = inbox.containsKey(senderId);
                if (inserted) {
                    inbox.computeIfAbsent(senderId, k -> new ArrayList<>()).add(notification);
                    inboxMessageById.put(notifId, notification);
                }
            }
            if (inserted) {
                GlobalPropertyBus.getInstance().fire(new AiInboxMessageEvent(senderId, notifId, notifSubject, exitingName));
                String deliveryNote = "Session " + sessionId + " exited without responding to your message."
                        + " Subject: \"" + pendingEntry.subject() + "\"";
                notifier.submit(() -> senderHandle.deliverIncomingMessage(sessionId, new SimpleNotification(deliveryNote)));
                if (pendingEntry.replyImportant()) {
                    notifier.submit(() -> senderHandle.requestGracefulInterrupt(InterruptTypeEnum.Mail));
                }
            }
        }
    }

    public boolean isActive(String sessionId) {
        synchronized (lock) {
            return inbox.containsKey(sessionId);
        }
    }

    public boolean isInterAiCommsAllowed(String sessionId) {
        AiSession session = sessionFromRegistry(sessionId);
        return session != null && session.allowsInterAiComms();
    }

    public boolean isSessionRunning(String sessionId) {
        AiSession session = sessionFromRegistry(sessionId);
        return session != null && session.isRunning();
    }

    public boolean isImportantMessagesAllowed(String sessionId) {
        AiSession session = sessionFromRegistry(sessionId);
        return session != null && session.allowsImportantMessages();
    }

    public List<AiSession> listActive(String callerSessionId) {
        List<String> sessionIds;
        synchronized (lock) {
            sessionIds = new ArrayList<>(inbox.keySet());
        }
        List<AiSession> result = new ArrayList<>();
        for (String sessionId : sessionIds) {
            if (sessionId.equals(callerSessionId)) {
                continue;
            }
            AiSession s = sessionFromRegistry(sessionId);
            if (s == null || !s.allowsInterAiComms()) {
                continue;
            }
            result.add(s);
        }
        return Collections.unmodifiableList(result);
    }

    public String sendMessage(String callerSessionId, String targetSessionId,
            String subject, String body, String replyToId) {
        return sendMessage(callerSessionId, targetSessionId, subject, body, replyToId, false, false, false);
    }

    public String sendMessage(String callerSessionId, String targetSessionId,
            String subject, String body, String replyToId, boolean important) {
        return sendMessage(callerSessionId, targetSessionId, subject, body, replyToId, important, false, false);
    }

    /**
     * Sends a message to another session's inbox.
     *
     * Returns the generated message ID, or null if the target session is not
     * active. The active check and inbox insertion are atomic under the same
     * lock, closing the TOCTOU gap between isActive() and delivery.
     *
     * When replyToId refers to an expectsReply entry with replyImportant=true,
     * the reply is automatically upgraded to important so the original sender
     * is interrupted when the reply arrives.
     *
     * When expectsReply=true a lightweight PendingReplyEntry (no body) is
     * stored. If the recipient exits without replying, the sender receives an
     * automatic "no reply" notification; if replyImportant=true the sender is
     * also interrupted regardless of their allowImportantMessages setting.
     */
    public String sendMessage(String callerSessionId, String targetSessionId,
            String subject, String body, String replyToId,
            boolean important, boolean expectsReply, boolean replyImportant) {
        String id = UUID.randomUUID().toString();
        String truncatedSubject = subject != null && subject.length() > AiInboxMessage.MAX_SUBJECT_LENGTH
                ? subject.substring(0, AiInboxMessage.MAX_SUBJECT_LENGTH)
                : subject;
        boolean effectiveImportant = important;
        AiInboxMessage msg = null;
        synchronized (lock) {
            if (!inbox.containsKey(targetSessionId)) {
                return null;
            }
            // Auto-upgrade importance when the original sender requested it
            if (replyToId != null) {
                PendingReplyEntry pending = pendingReplies.remove(replyToId);
                if (pending != null && pending.replyImportant()) {
                    effectiveImportant = true;
                }
                // Stamp respondedAt only if the caller was the intended recipient
                // of the original message — prevents spoofing another session's reply state.
                AiInboxMessage original = inboxMessageById.get(replyToId);
                if (original != null && callerSessionId.equals(original.toSessionId())) {
                    original.setRespondedAt(java.time.Instant.now());
                }
            }
            msg = new AiInboxMessage(id, callerSessionId, targetSessionId,
                    truncatedSubject, body, replyToId, effectiveImportant, expectsReply, replyImportant,
                    java.time.Instant.now(), null, null);
            List<AiInboxMessage> targetInbox = inbox.computeIfAbsent(targetSessionId, k -> new ArrayList<>());
            if (targetInbox.size() >= maxInboxSize.getAsInt()) {
                AiInboxMessage evicted = targetInbox.remove(0);
                inboxMessageById.remove(evicted.id());
                PendingReplyEntry evictedReply = pendingReplies.remove(evicted.id());
                if (evictedReply != null) {
                    String failId = java.util.UUID.randomUUID().toString();
                    String failSubject = "Delivery failed — inbox full (session " + targetSessionId + ")";
                    String failBody = "Your message \"" + evictedReply.subject() + "\" was dropped because the recipient's inbox is full.";
                    String failRecipient = evictedReply.fromSessionId();
                    AiInboxMessage failNotif = new AiInboxMessage(failId, targetSessionId, failRecipient,
                            failSubject, failBody, evicted.id(), false, false, false,
                            java.time.Instant.now(), null, null);
                    if (inbox.containsKey(failRecipient)) {
                        inbox.computeIfAbsent(failRecipient, k -> new ArrayList<>()).add(failNotif);
                        inboxMessageById.put(failId, failNotif);
                    }
                }
            }
            targetInbox.add(msg);
            inboxMessageById.put(id, msg);
            if (expectsReply) {
                pendingReplies.put(id, new PendingReplyEntry(
                        id,
                        truncatedSubject != null ? truncatedSubject : "",
                        callerSessionId,
                        targetSessionId,
                        replyImportant));
            }
        }
        AiSession handle = sessionFromRegistry(targetSessionId);
        AiSession caller = sessionFromRegistry(callerSessionId);
        if (effectiveImportant && handle != null && handle.isRunning() && handle.allowsImportantMessages()) {
            handle.requestGracefulInterrupt(InterruptTypeEnum.Mail);
        }
        String callerName = caller != null ? caller.name() : callerSessionId;
        GlobalPropertyBus.getInstance().fire(new AiInboxMessageEvent(targetSessionId, id, truncatedSubject, callerName));
        if (handle != null) {
            final AiInboxMessage capturedMsg = msg;
            notifier.submit(() -> handle.deliverIncomingMessage(callerSessionId,
                    new DeliverIncomingMessageNotification(capturedMsg)));
        }
        return id;
    }

    /**
     * Returns a non-draining snapshot of the inbox. Returns null if
     * authentication fails.
     */
    public List<AiInboxMessage> listInbox(String sessionId, String secret) {
        if (!validateSecret(sessionId, secret)) {
            return null;
        }
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(
                    inbox.getOrDefault(sessionId, new ArrayList<>())));
        }
    }

    /**
     * Marks a message as read and returns it. Returns null if not found, not
     * owned by this session, or authentication fails.
     */
    public AiInboxMessage readMessage(String sessionId, String secret, String messageId) {
        if (!validateSecret(sessionId, secret)) {
            return null;
        }
        synchronized (lock) {
            AiInboxMessage m = inboxMessageById.get(messageId);
            if (m == null || !m.toSessionId().equals(sessionId)) {
                return null;
            }
            if (m.readAt() == null) {
                m.setReadAt(java.time.Instant.now());
            }
            return m;
        }
    }

    public boolean isMessageUnread(String sessionId, String messageId) {
        return isMessageUnread(messageId);
    }

    public boolean isMessageUnread(String messageId) {
        synchronized (lock) {
            AiInboxMessage m = inboxMessageById.get(messageId);
            return m != null && m.readAt() == null;
        }
    }

    /**
     * Deletes inbox messages by id for the authenticated session. Returns the
     * count actually removed (unknown ids are ignored). Returns 0 on auth
     * failure.
     */
    public int deleteMessages(String sessionId, String secret, List<String> ids) {
        if (!validateSecret(sessionId, secret) || ids == null || ids.isEmpty()) {
            return 0;
        }
        Set<String> idSet = new java.util.HashSet<>(ids);
        synchronized (lock) {
            List<AiInboxMessage> messages = inbox.get(sessionId);
            if (messages == null) {
                return 0;
            }
            int before = messages.size();
            messages.removeIf(m -> {
                if (idSet.contains(m.id())) {
                    inboxMessageById.remove(m.id());
                    return true;
                }
                return false;
            });
            return before - messages.size();
        }
    }

    public void updateDescription(String sessionId, String description, String secret) {
        if (!validateSecret(sessionId, secret)) {
            return;
        }
        AiSession handle = sessionFromRegistry(sessionId);
        if (handle != null) {
            handle.applyDescriptionUpdate(description);
        }
    }

    public boolean validateSecret(String sessionId, String secret) {
        AiSession session = sessionFromRegistry(sessionId);
        if (session == null || secret == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                session.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    /**
     * Removes read messages whose readAt + retentionMs is at or before nowMs.
     * Iterates the flat inboxMessageById map for efficient expired-only
     * scanning; uses toSessionId for O(1) inbox list lookup to remove the
     * entry. Unread messages are never purged.
     */
    public void purgeExpiredRead(long nowMs, long retentionMs) {
        synchronized (lock) {
            inboxMessageById.values().removeIf(m -> {
                if (m.readAt() != null && m.readAt().toEpochMilli() + retentionMs <= nowMs) {
                    List<AiInboxMessage> list = inbox.get(m.toSessionId());
                    if (list != null) {
                        list.remove(m);
                    }
                    pendingReplies.remove(m.id());
                    return true;
                }
                return false;
            });
        }
    }

    /**
     * Starts the periodic retention sweep (prod only; called from getInstance()
     * before the instance is published — no concurrent access).
     */
    private void startSweeper() {
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ai-inbox-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(() -> {
            try {
                int mins = retentionMinutes.getAsInt();
                if (mins > 0) {
                    purgeExpiredRead(System.currentTimeMillis(), mins * 60_000L);
                }
            }
            catch (Exception e) {
                LOG.log(java.util.logging.Level.WARNING, "Inbox sweep failed", e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    public void shutdownSweeper() {
        ScheduledExecutorService s;
        synchronized (lock) {
            s = sweeper;
            sweeper = null;
        }
        if (s != null) {
            s.shutdownNow();
        }
    }

    public void shutdownNotifier() {
        notifier.shutdown();
        try {
            if (!notifier.awaitTermination(5, TimeUnit.SECONDS)) {
                notifier.shutdownNow();
            }
        }
        catch (InterruptedException e) {
            notifier.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // Lightweight entry tracking a sent message that expects a reply.
    // Stored without message body to save memory; cleaned up on reply or session exit.
    private record PendingReplyEntry(
            String messageId, String subject,
            String fromSessionId, String toSessionId,
            boolean replyImportant) {

    }
}

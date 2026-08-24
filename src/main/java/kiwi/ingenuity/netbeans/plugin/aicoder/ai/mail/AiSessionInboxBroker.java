package kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiInboxMessageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.AbstractNotification;
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

    // ---- Agent notifier: bounded coalescing with reconciliation ----
    //
    // Replaces the former single-worker ThreadPoolExecutor with a queue of 100 and
    // DiscardOldestPolicy, which silently dropped agent delivery notifications and important-message
    // interrupts under burst. The design keeps every constraint from the review watchlist:
    //
    // - BOUNDED: pendingWork holds at most one entry per target session, so it can never grow with
    //   traffic. No unbounded queue.
    // - NON-BLOCKING: submission is a map insert under notifierLock; broker and UI threads never
    //   block or run notifier work themselves. No deadlock.
    // - COALESCING + RECONCILIATION: a work item is only a per-target marker. What to announce is
    //   derived at run time from unannouncedBySession, which tracks every stored-but-unannounced
    //   message per recipient, so each processed item sweeps that target's whole backlog and nothing
    //   is announced twice. Inbox contents and the synchronous GlobalPropertyBus event remain
    //   authoritative exactly as before; only the best-effort agent-facing delivery got loss-proof.
    private final Object notifierLock = new Object();

    // Guarded by notifierLock; insertion-ordered so the longest-waiting target drains first.
    private final Map<String, NotifierWork> pendingWork = new LinkedHashMap<>();
    private Thread notifierThread;
    private boolean notifierRunning;
    private boolean notifierShutdown;

    // Work sequence numbers, both guarded by notifierLock. A new work item takes the next value of
    // notifierSeq; the single worker publishes the highest finished sequence in
    // notifierCompletedSeq. Items pop in insertion order, which is also sequence order, so
    // "completed >= captured" is an exact idle barrier for awaitNotifierIdle().
    private long notifierSeq;
    private long notifierCompletedSeq;

    // Guarded by `lock`: recipient sessionId -> (messageId -> entry) in arrival order. An entry is
    // added by every inbox insertion and removed when announced, deleted, purged or evicted, so it
    // is bounded by the stored message population.
    private final Map<String, LinkedHashMap<String, UnannouncedEntry>> unannouncedBySession = new HashMap<>();

    public AiSessionInboxBroker() {
    }

    /**
     * Sets the effective inbox capacity. Clamped to at least 1: the setter accepts any {@link IntSupplier}, and a
     * supplier returning 0 would otherwise drive the capacity loop onto an empty list.
     */
    void setMaxInboxSize(IntSupplier supplier) {
        this.maxInboxSize = () -> Math.max(1, supplier.getAsInt());
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
     * Unregisters a session. Senders of unread messages are notified of non-delivery. Senders whose messages were read
     * but not yet replied to (expectsReply=true) receive a "no reply" notification; if replyImportant was set, the
     * sender is interrupted regardless of their own setting.
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
            List<AiInboxMessage> removedMessages = removed != null ? removed : List.of();
            removedMessages.forEach(m -> inboxMessageById.remove(m.id()));
            // The exiting session's unannounced backlog dies with its inbox; messages it SENT that
            // are still queued in other recipients' backlogs stay queued, since those inbox copies
            // remain readable there.
            unannouncedBySession.remove(sessionId);
            // Read messages remain in the inbox, but only unread ones are undelivered.
            // A replied-to message is no longer pending, so the pending check below cannot exclude it.
            unread = removedMessages.stream().filter(m -> m.readAt() == null).toList();
            // Collect pending entries where the exiting session was the recipient
            pendingAsRecipient = pendingReplies.values().stream()
                    .filter(e -> e.toSessionId().equals(sessionId))
                    .toList();
            pendingAsRecipient.forEach(e -> pendingReplies.remove(e.messageId()));
            // Remove orphaned entries where the exiting session was the sender
            pendingReplies.entrySet().removeIf(e -> e.getValue().fromSessionId().equals(sessionId));
            activeIds = new HashSet<>(inbox.keySet());
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
                    Instant.now(), null, null);
            String deliveryNote = "Session " + sessionId + " exited — your message(s) were not delivered: "
                    + subjects.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", "));
            List<AiInboxMessage> stored;
            synchronized (lock) {
                ArrayDeque<PendingInsert> inserts = new ArrayDeque<>();
                // System notice: never displaces an unread message; wasImportant keeps the
                // unconditional interrupt the old path gave the sender.
                inserts.add(new PendingInsert(notification, wasImportant, true,
                        new SimpleNotification(deliveryNote)));
                stored = insertAllLocked(inserts);
            }
            announceStored(stored);
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
                    Instant.now(), null, null);
            String deliveryNote = "Session " + sessionId + " exited without responding to your message."
                    + " Subject: \"" + pendingEntry.subject() + "\"";
            List<AiInboxMessage> stored;
            synchronized (lock) {
                ArrayDeque<PendingInsert> inserts = new ArrayDeque<>();
                // System notice; replyImportant keeps the unconditional interrupt.
                inserts.add(new PendingInsert(notification, pendingEntry.replyImportant(), true,
                        new SimpleNotification(deliveryNote)));
                stored = insertAllLocked(inserts);
            }
            announceStored(stored);
        }
    }

    public boolean isActive(String sessionId) {
        synchronized (lock) {
            return inbox.containsKey(sessionId);
        }
    }

    /**
     * Whether the IDE knows this session at all, regardless of whether it can currently receive mail.
     * <p>
     * Distinct from {@link #isActive(String)}: a sender that mistypes a session ID and a sender addressing a session
     * that has since stopped both fail the active check, but they need opposite advice — fix the ID, versus pick a
     * different recipient. Without this the caller can only say "not active", which reads as "they have stopped" and
     * sends a caller with a corrupted ID looking in entirely the wrong place.
     */
    public boolean isKnownSession(String sessionId) {
        return sessionFromRegistry(sessionId) != null;
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
     * Returns the generated message ID, or null if the target session is not active. The active check and inbox
     * insertion are atomic under the same lock, closing the TOCTOU gap between isActive() and delivery.
     *
     * When replyToId refers to an expectsReply entry with replyImportant=true, the reply is automatically upgraded to
     * important so the original sender is interrupted when the reply arrives.
     *
     * When expectsReply=true a lightweight PendingReplyEntry (no body) is stored. If the recipient exits without
     * replying, the sender receives an automatic "no reply" notification; if replyImportant=true the sender is also
     * interrupted regardless of their allowImportantMessages setting.
     */
    public String sendMessage(String callerSessionId, String targetSessionId,
            String subject, String body, String replyToId,
            boolean important, boolean expectsReply, boolean replyImportant) {
        String id = UUID.randomUUID().toString();
        String truncatedSubject = subject != null && subject.length() > AiInboxMessage.MAX_SUBJECT_LENGTH
                ? subject.substring(0, AiInboxMessage.MAX_SUBJECT_LENGTH)
                : subject;
        boolean effectiveImportant = important;
        List<AiInboxMessage> stored;
        synchronized (lock) {
            if (!inbox.containsKey(targetSessionId)) {
                return null;
            }
            // Reply bookkeeping applies only when the caller was the intended recipient of the
            // original message, and ownership is decided BEFORE anything is consumed. The previous
            // order removed pendingReplies.remove(replyToId) first: an impostor quoting someone
            // else's message id suppressed the expected no-reply notification, and then inherited
            // replyImportant into effectiveImportant to escalate its own message. Now a non-owner's
            // replyToId is recorded verbatim on the new message but consumes no expectation,
            // stamps no respondedAt and upgrades nothing.
            if (replyToId != null) {
                AiInboxMessage original = inboxMessageById.get(replyToId);
                if (original != null && callerSessionId.equals(original.toSessionId())) {
                    PendingReplyEntry pending = pendingReplies.remove(replyToId);
                    if (pending != null && pending.replyImportant()) {
                        effectiveImportant = true;
                    }
                    original.setRespondedAt(Instant.now());
                }
            }
            AiInboxMessage msg = new AiInboxMessage(id, callerSessionId, targetSessionId,
                    truncatedSubject, body, replyToId, effectiveImportant, expectsReply, replyImportant,
                    Instant.now(), null, null);
            ArrayDeque<PendingInsert> inserts = new ArrayDeque<>();
            // Not a system notice: it always makes room for itself under the capacity policy.
            inserts.add(new PendingInsert(msg, false, false, new DeliverIncomingMessageNotification(msg)));
            stored = insertAllLocked(inserts);
            if (expectsReply) {
                pendingReplies.put(id, new PendingReplyEntry(
                        id,
                        truncatedSubject != null ? truncatedSubject : "",
                        callerSessionId,
                        targetSessionId,
                        replyImportant));
            }
        }
        announceStored(stored);
        return id;
    }

    /**
     * Returns a non-draining snapshot of the inbox. Returns null if authentication fails.
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
     * Marks a message as read and returns it. Returns null if not found, not owned by this session, or authentication
     * fails.
     */
    /**
     * Marks a message as read and reports whether this call performed the first read. The message lookup and first-read
     * decision happen under one lock.
     */
    public ReadResult readMessageWithResult(String sessionId, String secret, String messageId) {
        if (!validateSecret(sessionId, secret)) {
            return new ReadResult(null, false);
        }
        synchronized (lock) {
            AiInboxMessage m = inboxMessageById.get(messageId);
            if (m == null || !m.toSessionId().equals(sessionId)) {
                return new ReadResult(null, false);
            }
            boolean firstRead = m.readAt() == null;
            if (firstRead) {
                m.setReadAt(Instant.now());
            }
            return new ReadResult(m, firstRead);
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
     * Deletes inbox messages by id for the authenticated session. Returns the count actually removed (unknown ids are
     * ignored). Returns 0 on auth failure.
     */
    public int deleteMessages(String sessionId, String secret, List<String> ids) {
        if (!validateSecret(sessionId, secret) || ids == null || ids.isEmpty()) {
            return 0;
        }
        Set<String> idSet = new HashSet<>(ids);
        synchronized (lock) {
            List<AiInboxMessage> messages = inbox.get(sessionId);
            if (messages == null) {
                return 0;
            }
            int before = messages.size();
            messages.removeIf(m -> {
                if (idSet.contains(m.id())) {
                    inboxMessageById.remove(m.id());
                    // A deleted message must never be announced later.
                    removeUnannouncedLocked(m);
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
        return MessageDigest.isEqual(
                session.secret().getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Removes read messages whose readAt + retentionMs is at or before nowMs. Iterates the flat inboxMessageById map
     * for efficient expired-only scanning; uses toSessionId for O(1) inbox list lookup to remove the entry. Unread
     * messages are never purged.
     *
     * <p>
     * A purged message that still expected a reply no longer loses its expectation silently: the sender receives a "no
     * reply" notice through the normal capacity-checked insert, event and notifier path (interrupted when
     * replyImportant was set). Expiry collection happens before any insertion so the id map is never mutated while it
     * is being iterated.
     */
    public void purgeExpiredRead(long nowMs, long retentionMs) {
        List<AiInboxMessage> storedNotices;
        synchronized (lock) {
            List<AiInboxMessage> expired = new ArrayList<>();
            for (AiInboxMessage m : inboxMessageById.values()) {
                if (m.readAt() != null && m.readAt().toEpochMilli() + retentionMs <= nowMs) {
                    expired.add(m);
                }
            }
            ArrayDeque<PendingInsert> notices = new ArrayDeque<>();
            for (AiInboxMessage m : expired) {
                List<AiInboxMessage> list = inbox.get(m.toSessionId());
                if (list != null) {
                    list.remove(m);
                }
                inboxMessageById.remove(m.id());
                removeUnannouncedLocked(m);
                PendingReplyEntry orphanedReply = pendingReplies.remove(m.id());
                if (orphanedReply != null) {
                    notices.add(new PendingInsert(expiredNoReplyNotice(orphanedReply),
                            orphanedReply.replyImportant(), true, null));
                }
            }
            storedNotices = insertAllLocked(notices);
        }
        announceStored(storedNotices);
    }

    /**
     * Builds the "no reply before expiry" notice for a purged expects-reply message's sender.
     */
    private AiInboxMessage expiredNoReplyNotice(PendingReplyEntry orphanedReply) {
        String notifId = UUID.randomUUID().toString();
        String subject = "No reply — message expired";
        String body = "Your message \"" + orphanedReply.subject()
                + "\" expired without a reply from session " + orphanedReply.toSessionId() + ".";
        return new AiInboxMessage(notifId, orphanedReply.toSessionId(), orphanedReply.fromSessionId(),
                subject, body, orphanedReply.messageId(), orphanedReply.replyImportant(), false, false,
                Instant.now(), null, null);
    }

    /**
     * Starts the periodic retention sweep (prod only; called from getInstance() before the instance is published — no
     * concurrent access).
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
                LOG.log(Level.WARNING, "Inbox sweep failed", e);
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

    /**
     * Blocks until every notification enqueued before this call has been processed, or the timeout expires. Returns
     * true if the notifier drained in time.
     *
     * <p>
     * Work items carry monotonically increasing sequence numbers assigned at enqueue time and the single worker
     * publishes the highest finished sequence, so waiting for {@code completed >= captured} is an exact barrier. Exists
     * because delivery and the mail interrupt are dispatched asynchronously: without a barrier a caller (notably a
     * test) observing state straight after {@code sendMessage} reads it before the work has run, which makes a "did not
     * interrupt" assertion pass whether or not the code is correct.
     */
    public boolean awaitNotifierIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        synchronized (notifierLock) {
            long targetSeq = notifierSeq;
            while (notifierCompletedSeq < targetSeq) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                // Wait in bounded slices so a missed notify still re-checks the deadline promptly.
                notifierLock.wait(Math.max(1, Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 100)));
            }
            return true;
        }
    }

    public void shutdownNotifier() {
        Thread worker;
        synchronized (notifierLock) {
            notifierShutdown = true;
            notifierRunning = false;
            notifierLock.notifyAll();
            worker = notifierThread;
        }
        if (worker != null) {
            try {
                worker.join(TimeUnit.SECONDS.toMillis(5));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Queues an agent-facing announcement for {@code targetSessionId}. Never blocks and never discards: at most one
     * pending item per target exists, so further submissions for the same target coalesce — the queued item will sweep
     * whatever is unannounced when it runs.
     */
    private void enqueueNotification(String targetSessionId) {
        synchronized (notifierLock) {
            if (!pendingWork.containsKey(targetSessionId)) {
                NotifierWork work = new NotifierWork();
                work.seq = ++notifierSeq;
                pendingWork.put(targetSessionId, work);
                ensureNotifierThreadLocked();
                notifierLock.notifyAll();
            }
        }
    }

    /**
     * Starts the worker lazily. Caller must hold {@code notifierLock}.
     */
    private void ensureNotifierThreadLocked() {
        if (!notifierShutdown && notifierThread == null) {
            notifierRunning = true;
            notifierThread = new Thread(this::runNotifier, "ai-inbox-notifier");
            notifierThread.setDaemon(true);
            notifierThread.start();
        }
    }

    private void runNotifier() {
        while (true) {
            Map.Entry<String, NotifierWork> job;
            synchronized (notifierLock) {
                while (pendingWork.isEmpty()) {
                    if (!notifierRunning) {
                        notifierThread = null;
                        return;
                    }
                    try {
                        notifierLock.wait();
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                Iterator<Map.Entry<String, NotifierWork>> jobs = pendingWork.entrySet().iterator();
                job = jobs.next();
                jobs.remove();
            }
            try {
                announceToTarget(job.getKey());
            }
            catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Inbox notifier failed for session " + job.getKey(), e);
            }
            synchronized (notifierLock) {
                notifierCompletedSeq = Math.max(notifierCompletedSeq, job.getValue().seq);
                notifierLock.notifyAll();
            }
        }
    }

    /**
     * Delivers every currently-unannounced message for one target and fires its interrupt decision. The batch is
     * snapshotted under the broker lock and delivered outside it; entries are consumed by the snapshot so nothing is
     * announced twice, while anything enqueued during delivery gets its own follow-up work item — that is the
     * reconciliation half of the burst contract.
     *
     * <p>
     * Interrupt rules per entry match the paths that produced it: normal sends interrupt only when the recipient allows
     * important messages and is running, while exit/expiry notices carry an unconditional interrupt when replyImportant
     * was set.
     */
    private void announceToTarget(String targetSessionId) {
        List<UnannouncedEntry> batch;
        synchronized (lock) {
            LinkedHashMap<String, UnannouncedEntry> queued = unannouncedBySession.remove(targetSessionId);
            batch = queued == null ? List.of() : new ArrayList<>(queued.values());
        }
        if (batch.isEmpty()) {
            return;
        }
        AiSession handle = sessionFromRegistry(targetSessionId);
        if (handle == null) {
            return; // nothing reachable to deliver; the backlog was consumed like the old guard did
        }
        boolean interrupt = false;
        for (UnannouncedEntry entry : batch) {
            try {
                handle.deliverIncomingMessage(entry.message().fromSessionId(), entry.notification());
            }
            catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Delivering inbox notification failed", e);
            }
            if (entry.unconditionalInterrupt()
                    || (entry.message().important() && handle.isRunning() && handle.allowsImportantMessages())) {
                interrupt = true;
            }
        }
        if (interrupt) {
            try {
                handle.requestGracefulInterrupt(InterruptTypeEnum.Mail);
            }
            catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Mail interrupt failed for session " + targetSessionId, e);
            }
        }
    }

    /**
     * Fires the synchronous {@link AiInboxMessageEvent} for every stored message and queues its agent-facing
     * announcement. Must be called OUTSIDE {@code lock}: the bus event is delivered to listeners synchronously and they
     * may re-enter the broker.
     */
    private void announceStored(List<AiInboxMessage> stored) {
        for (AiInboxMessage message : stored) {
            AiSession from = sessionFromRegistry(message.fromSessionId());
            String fromName = from != null ? from.name() : message.fromSessionId();
            GlobalPropertyBus.getInstance().fire(new AiInboxMessageEvent(
                    message.toSessionId(), message.id(), message.subject(), fromName));
            enqueueNotification(message.toSessionId());
        }
    }

    /**
     * Inserts every queued request under the capacity policy and tracks stored messages for agent notification. While a
     * recipient's inbox is at capacity the oldest already-read message is evicted; only when every entry is unread does
     * the oldest unread message fall. Evicting a message that still expects a reply queues a delivery-failure notice
     * for its sender, which is processed through this same policy — chained notices terminate because each link either
     * fits, consumes one of the finite read entries, or is dropped by the system-notice rule below.
     *
     * <p>
     * A system notice (exit notices, expiry notices, failure notices) never displaces an unread message: when no read
     * entry can make room it is dropped and logged instead, so system traffic can neither bypass the capacity policy
     * nor cascade evictions through unread mail.
     *
     * <p>
     * Returns the messages actually stored, in insertion order; callers fire events and queue announcements for exactly
     * these via {@link #announceStored}. Caller must hold {@code lock}.
     */
    private List<AiInboxMessage> insertAllLocked(ArrayDeque<PendingInsert> queue) {
        List<AiInboxMessage> stored = new ArrayList<>();
        while (!queue.isEmpty()) {
            PendingInsert insert = queue.poll();
            String recipientId = insert.message().toSessionId();
            List<AiInboxMessage> targetInbox = inbox.get(recipientId);
            if (targetInbox == null) {
                continue; // recipient not active: nothing is stored, matching the inactive paths
            }
            int maxSize = Math.max(1, maxInboxSize.getAsInt());
            while (targetInbox.size() >= maxSize) {
                int victimIndex = -1;
                for (int i = 0; i < targetInbox.size(); i++) {
                    if (targetInbox.get(i).readAt() != null) {
                        victimIndex = i;
                        break;
                    }
                }
                if (victimIndex < 0) {
                    if (insert.systemNotice()) {
                        break; // full of unread mail: never displace unread for a system notice
                    }
                    victimIndex = 0; // everything is unread: the oldest falls
                }
                AiInboxMessage evicted = targetInbox.remove(victimIndex);
                inboxMessageById.remove(evicted.id());
                removeUnannouncedLocked(evicted);
                PendingReplyEntry orphanedReply = pendingReplies.remove(evicted.id());
                if (orphanedReply != null) {
                    String failSubject = "Delivery failed — inbox full (session " + recipientId + ")";
                    String failBody = "Your message \"" + orphanedReply.subject()
                            + "\" was dropped because the recipient's inbox is full.";
                    AiInboxMessage failNotif = new AiInboxMessage(UUID.randomUUID().toString(),
                            recipientId, orphanedReply.fromSessionId(), failSubject, failBody,
                            evicted.id(), false, false, false, Instant.now(), null, null);
                    SimpleNotification failDelivery = new SimpleNotification(
                            "Your message \"" + orphanedReply.subject()
                            + "\" was dropped because session " + recipientId + "'s inbox is full.");
                    queue.add(new PendingInsert(failNotif, orphanedReply.replyImportant(), true, failDelivery));
                }
            }
            if (targetInbox.size() >= maxSize) {
                LOG.log(Level.WARNING, "Dropped system notice {0}: inbox of session {1} is full of unread messages",
                        new Object[]{insert.message().id(), recipientId});
                continue;
            }
            targetInbox.add(insert.message());
            inboxMessageById.put(insert.message().id(), insert.message());
            unannouncedBySession.computeIfAbsent(recipientId, k -> new LinkedHashMap<>())
                    .put(insert.message().id(), new UnannouncedEntry(insert.message(), insert.notification(),
                            insert.unconditionalInterrupt()));
            stored.add(insert.message());
        }
        return stored;
    }

    /**
     * Removes a message from the unannounced tracking, wherever it is queued. Caller holds {@code lock}.
     */
    private void removeUnannouncedLocked(AiInboxMessage message) {
        LinkedHashMap<String, UnannouncedEntry> queued = unannouncedBySession.get(message.toSessionId());
        if (queued != null) {
            queued.remove(message.id());
            if (queued.isEmpty()) {
                unannouncedBySession.remove(message.toSessionId());
            }
        }
    }

    public static final class ReadResult {

        private final AiInboxMessage message;
        private final boolean firstRead;

        private ReadResult(AiInboxMessage message, boolean firstRead) {
            this.message = message;
            this.firstRead = firstRead;
        }

        public AiInboxMessage message() {
            return message;
        }

        public boolean firstRead() {
            return firstRead;
        }
    }

    // Per-target notifier marker: presence in pendingWork means "announce this target's backlog".
    private static final class NotifierWork {

        private long seq;
    }

    // One stored-but-unannounced message plus how its agent-facing delivery is shaped.
    private static final class UnannouncedEntry {

        private final AiInboxMessage message;
        private final AbstractNotification notification;
        private final boolean unconditionalInterrupt;

        UnannouncedEntry(AiInboxMessage message, AbstractNotification notification,
                boolean unconditionalInterrupt) {
            this.message = message;
            this.notification = notification;
            this.unconditionalInterrupt = unconditionalInterrupt;
        }

        AiInboxMessage message() {
            return message;
        }

        AbstractNotification notification() {
            return notification;
        }

        boolean unconditionalInterrupt() {
            return unconditionalInterrupt;
        }
    }

    // One inbox insertion request processed through the shared capacity policy in insertAllLocked.
    private static final class PendingInsert {

        private final AiInboxMessage message;
        private final boolean unconditionalInterrupt;
        private final boolean systemNotice;
        private final AbstractNotification notification;

        PendingInsert(AiInboxMessage message, boolean unconditionalInterrupt,
                boolean systemNotice, AbstractNotification notification) {
            this.message = message;
            this.unconditionalInterrupt = unconditionalInterrupt;
            this.systemNotice = systemNotice;
            this.notification = notification;
        }

        AiInboxMessage message() {
            return message;
        }

        boolean unconditionalInterrupt() {
            return unconditionalInterrupt;
        }

        boolean systemNotice() {
            return systemNotice;
        }

        AbstractNotification notification() {
            return notification != null ? notification : new DeliverIncomingMessageNotification(message);
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

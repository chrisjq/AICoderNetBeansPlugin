package kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail;

import java.time.Instant;

public final class AiInboxMessage {

    public static final int MAX_SUBJECT_LENGTH = 100;
    public static final int MAX_MESSAGE_LENGTH = 200_000;

    private final String id;
    private final String fromSessionId;
    private final String toSessionId;
    private final String subject;
    private final String body;
    private final String replyToId;
    private final boolean important;
    private final boolean expectsReply;
    private final boolean replyImportant;
    private final Instant sentAt;
    private volatile Instant readAt;
    private volatile Instant respondedAt;

    public AiInboxMessage(String id, String fromSessionId, String toSessionId,
            String subject, String body,
            String replyToId, boolean important, boolean expectsReply, boolean replyImportant,
            Instant sentAt, Instant readAt, Instant respondedAt) {
        this.id = id;
        this.fromSessionId = fromSessionId;
        this.toSessionId = toSessionId;
        this.subject = subject;
        this.body = body;
        this.replyToId = replyToId;
        this.important = important;
        this.expectsReply = expectsReply;
        this.replyImportant = replyImportant;
        this.sentAt = sentAt;
        this.readAt = readAt;
        this.respondedAt = respondedAt;
    }

    public String id() {
        return id;
    }

    public String fromSessionId() {
        return fromSessionId;
    }

    public String toSessionId() {
        return toSessionId;
    }

    public String subject() {
        return subject;
    }

    public String body() {
        return body;
    }

    public String replyToId() {
        return replyToId;
    }

    public boolean important() {
        return important;
    }

    public boolean expectsReply() {
        return expectsReply;
    }

    public boolean replyImportant() {
        return replyImportant;
    }

    public Instant sentAt() {
        return sentAt;
    }

    public Instant readAt() {
        return readAt;
    }

    public Instant respondedAt() {
        return respondedAt;
    }

    void setReadAt(Instant t) {
        this.readAt = t;
    }

    void setRespondedAt(Instant t) {
        this.respondedAt = t;
    }

    public String formatSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(id)
                .append(" from=").append(fromSessionId)
                .append(" ").append(readAt != null ? "[READ]" : "[UNREAD]")
                .append("\nSubject: ").append(subject);
        if (sentAt != null) {
            sb.append("\nSent: ").append(sentAt);
        }
        if (readAt != null) {
            sb.append("\nRead: ").append(readAt);
        }
        if (replyToId != null) {
            sb.append("\nReply-To: ").append(replyToId);
        }
        if (expectsReply) {
            sb.append("\n[Expects reply]");
        }
        return sb.toString();
    }
}

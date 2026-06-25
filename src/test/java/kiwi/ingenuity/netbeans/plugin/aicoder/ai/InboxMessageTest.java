package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiInboxMessage;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboxMessageTest {

    private AiInboxMessage base() {
        return new AiInboxMessage("id1", "fromX", "toY", "Subject here", "body text",
                null, false, false, false,
                Instant.parse("2026-06-15T10:00:00Z"), null, null);
    }

    @Test
    void newMessageIsUnread() {
        AiInboxMessage m = base();
        assertNull(m.readAt());
        assertNull(m.respondedAt());
        assertEquals("id1", m.id());
        assertEquals("body text", m.body());
        assertEquals("fromX", m.fromSessionId());
        assertEquals("toY", m.toSessionId());
    }

    @Test
    void constructorWithReadAtIsRead() {
        Instant t = Instant.parse("2026-06-15T10:05:00Z");
        AiInboxMessage m = new AiInboxMessage("id1", "fromX", "toY", "Subject here", "body text",
                null, false, false, false,
                Instant.parse("2026-06-15T10:00:00Z"), t, null);
        assertEquals(t, m.readAt());
    }

    @Test
    void formatSummaryShowsUnreadStateAndSentAt() {
        String s = base().formatSummary();
        assertTrue(s.contains("id=id1"), "summary shows id");
        assertTrue(s.contains("from=fromX"), "summary shows sender");
        assertTrue(s.contains("[UNREAD]"), "summary marks unread");
        assertTrue(s.contains("2026-06-15T10:00:00Z"), "summary shows sentAt");
    }

    @Test
    void formatSummaryShowsReadStateWhenRead() {
        AiInboxMessage m = new AiInboxMessage("id1", "fromX", "toY", "Subject here", "body text",
                null, false, false, false,
                Instant.parse("2026-06-15T10:00:00Z"),
                Instant.parse("2026-06-15T10:05:00Z"), null);
        String s = m.formatSummary();
        assertTrue(s.contains("[READ]"), "summary marks read");
        assertNotNull(s);
    }
}

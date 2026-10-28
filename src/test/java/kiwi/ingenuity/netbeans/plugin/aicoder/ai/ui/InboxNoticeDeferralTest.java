package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiInboxMessageEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class InboxNoticeDeferralTest {

    @Test
    void holdsNoticesWhileBusyAndFlushesInArrivalOrder() {
        List<AiInboxMessageEvent> immediate = new ArrayList<>();
        InboxNoticeDeferral deferral = new InboxNoticeDeferral(immediate::add);
        AiInboxMessageEvent first = message("first");
        AiInboxMessageEvent second = message("second");

        deferral.offer(first, true);
        deferral.offer(second, true);

        assertTrue(immediate.isEmpty());
        deferral.flush();

        assertEquals(List.of(first, second), immediate);
    }

    @Test
    void showsNoticeImmediatelyWhenIdle() {
        List<AiInboxMessageEvent> shown = new ArrayList<>();
        InboxNoticeDeferral deferral = new InboxNoticeDeferral(shown::add);
        AiInboxMessageEvent notice = message("idle");

        deferral.offer(notice, false);

        assertEquals(List.of(notice), shown);
    }

    @Test
    void idleOfferFlushesPendingBeforeShowingNewNotice() {
        List<AiInboxMessageEvent> shown = new ArrayList<>();
        InboxNoticeDeferral deferral = new InboxNoticeDeferral(shown::add);
        AiInboxMessageEvent first = message("first");
        AiInboxMessageEvent second = message("second");
        deferral.offer(first, true);
        deferral.offer(second, false);
        assertEquals(List.of(first, second), shown);
    }

    @Test
    void flushingIsIdempotentAndDoesNotDuplicateNotices() {
        List<AiInboxMessageEvent> flushed = new ArrayList<>();
        InboxNoticeDeferral deferral = new InboxNoticeDeferral(flushed::add);
        AiInboxMessageEvent notice = message("once");
        deferral.offer(notice, true);

        deferral.flush();
        deferral.flush();

        assertEquals(List.of(notice), flushed);
    }

    private static AiInboxMessageEvent message(String subject) {
        return new AiInboxMessageEvent("session", subject, subject, "sender");
    }
}

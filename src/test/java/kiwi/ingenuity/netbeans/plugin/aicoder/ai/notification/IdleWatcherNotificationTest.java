package kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification;

import java.time.Duration;
import java.time.Instant;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch.IdleWatchEventEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch.IdleWatcher;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class IdleWatcherNotificationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-17T01:00:00Z");
    private static final Instant IDLE_SINCE = Instant.parse("2026-09-17T01:10:00Z");

    private static IdleWatcher watcher(boolean recurring, String note) {
        return watcher(recurring, false, note);
    }

    private static IdleWatcher watcher(boolean recurring, boolean interrupt, String note) {
        return new IdleWatcher("idle-watch-7", "watcher-session", "target-session", Duration.ofMinutes(5), recurring,
                               interrupt, note, CREATED_AT);
    }

    @Test
    void typeIsIdleWatcherAndDeliveryNeverWaitsOrHoldsBack() {
        IdleWatcherNotification n = new IdleWatcherNotification(watcher(false, null), IdleWatchEventEnum.IDLE,
                                                                IDLE_SINCE, "Target");

        assertEquals(NotificationTypeEnum.IDLE_WATCHER, n.type());
        assertTrue(n.skipAutoNotifyDeferral());
        assertTrue(n.shouldDeliver());
    }

    @Test
    void idleTextNamesTheTargetAndTheWatcherId() {
        IdleWatcherNotification n = new IdleWatcherNotification(watcher(false, null), IdleWatchEventEnum.IDLE,
                                                                IDLE_SINCE, "Target");

        String text = n.text();
        assertTrue(text.contains("Target"), text);
        assertTrue(text.contains("idle-watch-7"), text);
    }

    @Test
    void idleAgentOnlyTextCarriesTargetSessionIdIdleSinceAndNote() {
        IdleWatcherNotification n = new IdleWatcherNotification(watcher(false, "check the reactor build"),
                                                                IdleWatchEventEnum.IDLE, IDLE_SINCE, "Target");

        String agentOnly = n.agentOnlyText();
        assertTrue(agentOnly.contains("target-session"), agentOnly);
        assertTrue(agentOnly.contains("Idle since"), agentOnly);
        assertTrue(agentOnly.contains("Note: check the reactor build"), agentOnly);
    }

    @Test
    void idleAgentOnlyTextOmitsNoteWhenNoneWasGiven() {
        IdleWatcherNotification n = new IdleWatcherNotification(watcher(false, null), IdleWatchEventEnum.IDLE,
                                                                IDLE_SINCE, "Target");

        assertFalse(n.agentOnlyText().contains("Note:"), n.agentOnlyText());
    }

    @Test
    void oneshotAgentOnlyTextSaysItHasBeenRemoved() {
        IdleWatcherNotification n = new IdleWatcherNotification(watcher(false, null), IdleWatchEventEnum.IDLE,
                                                                IDLE_SINCE, "Target");

        String agentOnly = n.agentOnlyText();
        assertTrue(agentOnly.contains("has now been removed"), agentOnly);
        assertFalse(agentOnly.contains("stays armed"), agentOnly);
    }

    @Test
    void recurringAgentOnlyTextSaysItStaysArmed() {
        IdleWatcherNotification n = new IdleWatcherNotification(watcher(true, null), IdleWatchEventEnum.IDLE,
                                                                IDLE_SINCE, "Target");

        String agentOnly = n.agentOnlyText();
        assertTrue(agentOnly.contains("stays armed"), agentOnly);
        assertFalse(agentOnly.contains("has now been removed"), agentOnly);
    }

    @Test
    void targetClosedTextNeverContainsIdleSinceEvenWhenAnIdleSinceWasRecorded() {
        IdleWatcherNotification n = new IdleWatcherNotification(watcher(false, null), IdleWatchEventEnum.TARGET_CLOSED,
                                                                IDLE_SINCE, "Target");

        String text = n.text();
        assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("idle since"), text);
        assertTrue(text.contains("Target"), text);
        assertTrue(text.contains("idle-watch-7"), text);
    }

    @Test
    void targetClosedAgentOnlyTextExplicitlySaysTheIdleConditionWasNotTheReason() {
        IdleWatcherNotification n = new IdleWatcherNotification(watcher(false, null), IdleWatchEventEnum.TARGET_CLOSED,
                                                                IDLE_SINCE, "Target");

        String agentOnly = n.agentOnlyText();
        assertTrue(agentOnly.contains("NOT the reason"), agentOnly);
    }

    /**
     * Live-test-caught gap: agentOnlyText() used to print the "Idle since: ... (N whole minute(s) idle as of now)" line
     * for ANY event with a recorded idleSince, including TARGET_CLOSED — misleadingly implying the idle condition was
     * involved when it explicitly was not.
     */
    @Test
    void targetClosedAgentOnlyTextNeverMentionsIdleSinceEvenWhenOneWasRecorded() {
        IdleWatcherNotification n = new IdleWatcherNotification(watcher(false, "check the reactor build"),
                                                                IdleWatchEventEnum.TARGET_CLOSED, IDLE_SINCE, "Target");

        String agentOnly = n.agentOnlyText();
        String lower = agentOnly.toLowerCase(java.util.Locale.ROOT);
        assertFalse(lower.contains("idle since"), agentOnly);
        assertFalse(lower.contains("idle as of now"), agentOnly);
        assertTrue(agentOnly.contains("target-session"), agentOnly);
        assertTrue(agentOnly.contains("Timeout:"), agentOnly);
        assertTrue(agentOnly.contains("Note: check the reactor build"), agentOnly);
        assertTrue(lower.contains("closed"), agentOnly);
    }

    @Test
    void targetClosedTextIsTheSameRegardlessOfOneshotOrRecurring() {
        IdleWatcherNotification oneshot = new IdleWatcherNotification(watcher(false, null),
                                                                      IdleWatchEventEnum.TARGET_CLOSED, IDLE_SINCE,
                                                                      "Target");
        IdleWatcherNotification recurring = new IdleWatcherNotification(watcher(true, null),
                                                                        IdleWatchEventEnum.TARGET_CLOSED, IDLE_SINCE,
                                                                        "Target");

        assertEquals(oneshot.text(), recurring.text());
    }

    @Test
    void notificationTypeIdleWatcherRendersAsSystemMessageAndIsNotAnnouncedOnArrival() {
        assertTrue(NotificationTypeEnum.IDLE_WATCHER.rendersAsSystemMessage());
        assertFalse(NotificationTypeEnum.IDLE_WATCHER.announcedOnArrival());
    }

    /**
     * The interrupt REQUEST is a registry/notifier-level side effect (see SessionIdleWatchNotifierTest); the notice
     * content itself must not depend on it, since the watching session reads the same text either way.
     */
    @Test
    void theInterruptFlagDoesNotChangeTheNoticeTextOrAgentOnlyText() {
        IdleWatcherNotification notInterrupting = new IdleWatcherNotification(watcher(false, false, "a note"),
                                                                              IdleWatchEventEnum.IDLE, IDLE_SINCE,
                                                                              "Target");
        IdleWatcherNotification interrupting = new IdleWatcherNotification(watcher(false, true, "a note"),
                                                                           IdleWatchEventEnum.IDLE, IDLE_SINCE,
                                                                           "Target");

        assertEquals(notInterrupting.text(), interrupting.text());
        assertEquals(notInterrupting.agentOnlyText(), interrupting.agentOnlyText());
    }
}

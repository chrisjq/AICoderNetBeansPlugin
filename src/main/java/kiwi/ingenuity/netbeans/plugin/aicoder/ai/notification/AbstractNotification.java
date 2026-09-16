package kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification;

public abstract class AbstractNotification {

    public abstract String text();

    public abstract boolean shouldDeliver();

    /**
     * Text only the AI should see, wrapped in the agent-only SYSTEM block — null (the default) if this notification has
     * nothing beyond its visible {@link #text()}.
     */
    public String agentOnlyText() {
        return null;
    }

    /**
     * Groups this notification for display: entries of the same type are joined under one prefix rather than one per
     * entry. Defaults to {@link NotificationTypeEnum#NEW_INBOX_MESSAGE}, the only kind that existed before grouping was
     * introduced.
     */
    public NotificationTypeEnum type() {
        return NotificationTypeEnum.NEW_INBOX_MESSAGE;
    }

    /**
     * True if this notification must reach the AI at once regardless of the session's auto-notify-inbox setting — for a
     * result the AI is specifically waiting on, rather than unrelated mail it can choose to defer. False (the default)
     * preserves today's behaviour: parked until the next turn when auto-notify is off.
     */
    public boolean skipAutoNotifyDeferral() {
        return false;
    }
}

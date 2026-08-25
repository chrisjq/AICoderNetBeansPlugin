package kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification;

public enum NotificationTypeEnum {

    NEW_INBOX_MESSAGE("[New Inbox Message]"),
    /**
     * Sent when a turn was interrupted to deliver mail but the inbox flush had nothing left to
     * say — because the assistant read the message itself during the interrupted turn.
     * <p>
     * Without this the assistant is left with an aborted tool call, a deliberately suppressed
     * INTERRUPTED status, and no explanation. Backends report a mid-turn abort as a user
     * cancellation, so the assistant concludes the USER rejected the call. That is a false
     * belief about the user's intent, and it has been acted on and reported as fact.
     */
    INBOX_INTERRUPT_NOTICE("[Inbox Interrupt]");

    private final String prefix;

    NotificationTypeEnum(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification;

public enum NotificationTypeEnum {

    NEW_INBOX_MESSAGE("[New Inbox Message]");

    private final String prefix;

    NotificationTypeEnum(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}

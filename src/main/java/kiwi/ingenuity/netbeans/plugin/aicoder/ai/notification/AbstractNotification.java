package kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification;

public abstract class AbstractNotification {

    public abstract String text();

    public abstract boolean shouldDeliver();
}

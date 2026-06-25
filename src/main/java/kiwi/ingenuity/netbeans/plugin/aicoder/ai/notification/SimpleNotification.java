package kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification;

public class SimpleNotification extends AbstractNotification {

    private final String text;

    public SimpleNotification(String text) {
        this.text = text;
    }

    @Override
    public String text() {
        return text;
    }

    @Override
    public boolean shouldDeliver() {
        return true;
    }
}

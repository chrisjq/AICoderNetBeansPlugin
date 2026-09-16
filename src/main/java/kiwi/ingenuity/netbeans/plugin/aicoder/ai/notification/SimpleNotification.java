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

    /**
     * The same text the user sees, repeated to the assistant.
     * <p>
     * These carry delivery outcomes — "session X exited, not delivered", "exited without responding", "dropped because
     * the inbox is full" — and BOTH audiences need them: the user to see what happened, and the AI that sent the
     * message to learn its message never arrived. Without this override the text reached only the transcript: the type
     * renders as a system message, so it is excluded from the prompt, and the agent-only collection takes only
     * {@code agentOnlyText()}. A sender was left believing a message had been delivered when it had not.
     */
    @Override
    public String agentOnlyText() {
        return text;
    }

    @Override
    public boolean shouldDeliver() {
        return true;
    }
}

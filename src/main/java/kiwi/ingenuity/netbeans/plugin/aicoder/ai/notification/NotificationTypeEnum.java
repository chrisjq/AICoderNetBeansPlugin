package kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification;

public enum NotificationTypeEnum {

    /**
     * The prefix is now unused: an arriving message renders as its own system line ("New message from [X]: Subject"),
     * so there is no group of prompt entries left to label. Kept rather than deleted because the value is persisted in
     * saved histories written before the change.
     */
    NEW_INBOX_MESSAGE("[New Inbox Message]", true, true),
    /**
     * Sent when a turn was interrupted to deliver mail but the inbox flush had nothing left to say — because the
     * assistant read the message itself during the interrupted turn.
     * <p>
     * Without this the assistant is left with an aborted tool call, a deliberately suppressed INTERRUPTED status, and
     * no explanation. Backends report a mid-turn abort as a user cancellation, so the assistant concludes the USER
     * rejected the call. That is a false belief about the user's intent, and it has been acted on and reported as fact.
     */
    INBOX_INTERRUPT_NOTICE("[Inbox Interrupt]", false, false),
    /**
     * An async build or test run finishing. The prefix is deliberately blank: {@link
     * kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildReportFormatter#chatSummary} already produces one
     * complete, self-identifying line ("BUILD: ..."), so gluing another marker in front of it would duplicate what the
     * line already says.
     */
    BUILD_COMPLETE("", true, false),
    /**
     * An idle watcher firing: the target AI has now been continuously idle for the watcher's timeout, or the target
     * session closed (which is never an idle event). The prefix is deliberately blank: {@link
     * IdleWatcherNotification#text} already produces one complete, self-identifying line ("IDLE WATCH: ..."), so gluing
     * another marker in front of it would duplicate what the line already says.
     */
    IDLE_WATCHER("", true, false);

    private final String prefix;
    private final boolean rendersAsSystemMessage;
    private final boolean announcedOnArrival;

    NotificationTypeEnum(String prefix, boolean rendersAsSystemMessage, boolean announcedOnArrival) {
        this.prefix = prefix;
        this.rendersAsSystemMessage = rendersAsSystemMessage;
        this.announcedOnArrival = announcedOnArrival;
    }

    public String prefix() {
        return prefix;
    }

    /**
     * Whether this type belongs in the conversation as a SYSTEM entry rather than in the prompt of the turn that
     * delivers it.
     * <p>
     * A build finishing, or a message arriving, is an EVENT: nobody typed it, and showing it as though the user had
     * said it is misleading. Such entries belong with the other things that happen to a session — tool actions, review
     * logs, session instructions — which already render as system messages. The assistant still receives the full
     * detail, in the agent-only block of the same turn, so nothing is lost by keeping it out of the prompt.
     * <p>
     * A type that returns true therefore contributes NOTHING to the prompt. Anything such a notification must tell the
     * assistant has to be in its {@code agentOnlyText()} — or it reaches the user and nobody else.
     */
    public boolean rendersAsSystemMessage() {
        return rendersAsSystemMessage;
    }

    /**
     * Whether a SEPARATE publisher has already put this type's line in the conversation by the time the inbox flush
     * runs, so the flush must not post it a second time.
     * <p>
     * True only for mail. {@code AiTopComponent.handleGlobalProperty} posts "New message from [X]: Subject" the moment
     * the message lands, and has done so since long before these entries started rendering as system messages — that
     * arrival line is the ORIGINAL publisher, and the one that gives prompt feedback. When the flush later composed the
     * same line from {@link AbstractNotification#text}, mail gained a second, identical entry.
     * <p>
     * The duplicate is invisible on default settings, which is why it survived review: with {@code autoNotifyInbox} off
     * (the plugin default) mail is parked in the deferred queue and never reaches the flush at all, so only the arrival
     * line is drawn. The three session templates turn that setting ON, so exactly the sessions created from them would
     * have seen every arrival twice.
     * <p>
     * False for {@link #BUILD_COMPLETE}: a finishing build has no arrival publisher, and the flush is the only thing
     * that ever draws its summary. Suppressing it there would lose the line entirely rather than de-duplicate it.
     */
    public boolean announcedOnArrival() {
        return announcedOnArrival;
    }
}

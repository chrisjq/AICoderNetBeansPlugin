package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

public enum StatusEventTypeEnum {
    /**
     * The backend can accept input again after work that was NOT a turn — a compaction, the session starting.
     * A turn closes with its {@code TurnCompleteEvent} instead and must never be followed by READY: the UI
     * may already have started the next queued turn by then, and a late READY would unlock it mid-turn.
     */
    READY("Ready"),
    /**
     * The backend has started work the user must wait for; the UI locks until the work closes with READY,
     * FAILED or EXITED. Carries {@code StatusEvent.cancellable()} so the UI knows whether to show Stop.
     */
    BUSY("Busy"),
    THINKING("Thinking"),
    STOPPED("Stopped"),
    EXITED("Exited"),
    FAILED("Failed"),
    // Turn ended abnormally mid-stream (e.g. runtime aborted_streaming) but the
    // process itself is still alive — surface a notice without flagging the tab fatal.
    INTERRUPTED("Interrupted"),
    INFO("Info");

    private final String title;

    StatusEventTypeEnum(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}

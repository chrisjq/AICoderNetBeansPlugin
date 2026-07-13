package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

public enum StatusEventTypeEnum {
    READY("Ready"),
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

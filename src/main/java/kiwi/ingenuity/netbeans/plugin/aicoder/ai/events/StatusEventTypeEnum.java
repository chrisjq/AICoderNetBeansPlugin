package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

public enum StatusEventTypeEnum {
    READY("Ready"),
    THINKING("Thinking"),
    STOPPED("Stopped"),
    EXITED("Exited"),
    FAILED("Failed"),
    INFO("Info");

    private final String title;

    StatusEventTypeEnum(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}

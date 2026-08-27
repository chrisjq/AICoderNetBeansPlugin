package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

/**
 * JSON keys for the OpenCode HTTP steer API ({@code POST /api/session/{sessionID}/prompt}). Values are the exact wire
 * names and must not be changed.
 */
public enum OpenCodeSteerJsonKeyEnum {

    /**
     * Outer wrapper: {@code {"prompt":{...}}}.
     */
    PROMPT("prompt"),
    /**
     * Text field inside the prompt object: {@code {"text":"..."}}.
     */
    TEXT("text"),
    /**
     * Delivery mode: {@code {"delivery":"steer"}}.
     */
    DELIVERY("delivery"),
    /**
     * Steer delivery value.
     */
    STEER("steer");

    private final String key;

    OpenCodeSteerJsonKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

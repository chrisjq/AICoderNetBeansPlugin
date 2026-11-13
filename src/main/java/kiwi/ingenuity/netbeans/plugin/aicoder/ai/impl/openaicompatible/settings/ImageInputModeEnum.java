package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

/**
 * Whether the model can be sent image content. Auto trusts what the server reports about the model, or tries once and
 * stops if the server rejects images; On and Off override that. Unset means {@link #AUTO}.
 */
public enum ImageInputModeEnum {
    AUTO("Auto"),
    ON("On"),
    OFF("Off");

    private final String displayName;

    ImageInputModeEnum(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * @return the matching mode, or {@code null} for null or unknown text (a hand-edited settings value is ignored
     * rather than preventing session loading)
     */
    public static ImageInputModeEnum parse(String value) {
        if (value == null) {
            return null;
        }
        for (ImageInputModeEnum mode : values()) {
            if (mode.name().equals(value)) {
                return mode;
            }
        }
        return null;
    }
}

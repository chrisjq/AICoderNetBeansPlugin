package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

public enum AiTypeEnum {
    CLAUDE("Claude", "claude", true, true),
    GROK("Grok", "grok", false, true),
    GitHubCoPilot("GitHub CoPilot", "github_copilot", true, true);

    public static AiTypeEnum fromKey(String key) {
        if (key == null) {
            return null;
        }
        for (AiTypeEnum v : values()) {
            if (v.key().equals(key)) {
                return v;
            }
        }
        return null;
    }

    private final String displayName;
    private final String key;
    private final boolean enabledByDefault;
    private final boolean implemented;

    AiTypeEnum(String displayName, String key, boolean isImplemented, boolean enabledByDefault) {
        this.displayName = displayName;
        this.key = key;
        this.implemented = isImplemented;
        this.enabledByDefault = enabledByDefault;
    }

    public String displayName() {
        return displayName;
    }

    public String key() {
        return key;
    }

    public boolean isEnabledByDefault() {
        return enabledByDefault;
    }

    public boolean isImplemented() {
        return implemented;
    }

}

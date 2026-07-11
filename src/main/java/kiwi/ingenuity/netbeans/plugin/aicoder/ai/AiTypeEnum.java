package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudeSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettingsCreator;

/**
 * Enumerates available AI implementations and their configurations. Each type
 * maintains a settings creator for initializing and updating type-specific
 * configurations.
 */
public enum AiTypeEnum {
    CLAUDE("Claude", "claude", true, true, new ClaudeSettingsCreator()),
    GROK("Grok", "grok", true, true, new GrokSettingsCreator()),
    GitHubCoPilot("GitHub CoPilot", "github_copilot", true, true, new GithubCopilotSettingsCreator());

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

    /**
     * User-facing display name for the AI type
     */
    private final String displayName;
    /**
     * Internal configuration key identifier
     */
    private final String key;
    /**
     * Whether this AI type is enabled by default
     */
    private final boolean enabledByDefault;
    /**
     * Whether this AI type has been implemented
     */
    private final boolean implemented;
    /**
     * Creator responsible for instantiating and updating settings
     */
    private final AiSessionSettingsCreator settingCreator;

    AiTypeEnum(String displayName, String key, boolean isImplemented, boolean enabledByDefault, AiSessionSettingsCreator settingCreator) {
        this.displayName = displayName;
        this.key = key;
        this.implemented = isImplemented;
        this.enabledByDefault = enabledByDefault;
        this.settingCreator = settingCreator;
    }

    /**
     * Gets the user-facing display name.
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Gets the internal configuration key.
     */
    public String key() {
        return key;
    }

    /**
     * Checks if this AI type is enabled by default.
     */
    public boolean isEnabledByDefault() {
        return enabledByDefault;
    }

    /**
     * Checks if this AI type has been implemented.
     */
    public boolean isImplemented() {
        return implemented;
    }

    /**
     * Creates default settings for this AI type.
     *
     * @return a new AiSessionSettings instance configured for this AI type
     */
    public AiSessionSettings createDefaultSettings() {
        return settingCreator.create();
    }

    /**
     * Returns the settings creator for this AI type.
     *
     * @return the AiSessionSettingsCreator responsible for managing settings
     */
    public AiSessionSettingsCreator getSettingsCreator() {
        return settingCreator;
    }

}

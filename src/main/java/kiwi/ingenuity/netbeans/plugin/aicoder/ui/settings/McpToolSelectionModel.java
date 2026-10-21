package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolAccess;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;

/**
 * Non-Swing mapping between per-session group switches and an explicit {@link McpToolConfig}. Owns the rules for the
 * always-on group, the derived Build-queue group, and which groups gate their detailed settings.
 */
public final class McpToolSelectionModel {

    private McpToolConfig config;

    public McpToolSelectionModel(McpToolConfig config) {
        this.config = config == null ? McpToolConfig.ALL_ENABLED : config;
    }

    public static McpToolSelectionModel forSettings(AiSessionSettings settings) {
        return new McpToolSelectionModel(McpToolAccess.configOf(settings));
    }

    public boolean isGroupEnabled(McpToolGroupEnum group) {
        if (group == McpToolGroupEnum.ALWAYS_ON) {
            return true;
        }
        if (group == McpToolGroupEnum.BUILD_QUEUE) {
            return McpToolGroupEnum.BUILD_SYSTEM_GROUPS.stream().anyMatch(this::isSwitchableEnabled);
        }
        return isSwitchableEnabled(group);
    }

    public boolean isGroupEditable(McpToolGroupEnum group) {
        return group.switchable();
    }

    public void setGroupEnabled(McpToolGroupEnum group, boolean enabled) {
        if (!group.switchable()) {
            return;
        }
        config = config.with(group, !enabled);
    }

    /**
     * Whether the config settings that sit beneath {@code group}'s MCP switch should be shown. The config settings
     * (§5.6: the on/off setting, its detailed options and, for the database, the row limit) are hidden — not merely
     * greyed — while the MCP group is off, and shown again with their kept values when it is switched on. Their stored
     * values are never cleared by hiding.
     */
    public boolean areConfigSettingsVisibleFor(McpToolGroupEnum group) {
        return isGroupEnabled(group);
    }

    public McpToolConfig config() {
        return config;
    }

    public void applyConfig(McpToolConfig other) {
        this.config = other == null ? McpToolConfig.ALL_ENABLED : other;
    }

    private boolean isSwitchableEnabled(McpToolGroupEnum group) {
        return !config.isDisabled(group);
    }

}

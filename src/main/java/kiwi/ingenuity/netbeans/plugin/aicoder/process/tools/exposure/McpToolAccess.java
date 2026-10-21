package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.AccessControlLabelEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

/**
 * Resolves the tools visible to one session. A tool is available only if its MCP group is on (the session's
 * {@link McpToolConfig}, or the one derived from its legacy settings before it has started) AND, for the groups that
 * have a config setting, that setting allows it too.
 */
public final class McpToolAccess {

    /**
     * Which layer refuses a group: the MCP group switch, or the group's config setting beneath it.
     */
    public enum DenialLayer {
        NONE,
        MCP,
        CONFIG
    }

    private McpToolAccess() {
    }

    public static McpToolConfig configOf(AiSessionSettings settings) {
        if (settings != null && settings.mcpToolConfig() != null) {
            return settings.mcpToolConfig();
        }
        return LegacyToolGroups.derive(settings);
    }

    public static boolean isGroupEnabled(AiSessionSettings settings, McpToolGroupEnum group) {
        return denialLayer(settings, group) == DenialLayer.NONE;
    }

    /**
     * Which layer refuses {@code group} for this session, or {@link DenialLayer#NONE} when it is available.
     *
     * <p>
     * The MCP layer takes precedence: when the session has its own MCP config and that switches the group off, the
     * answer is MCP whatever the config setting says, because with the group off that setting is not even shown. A
     * session that has not started yet has no MCP config of its own — it is derived from the very same config settings
     * — so there the config setting is the real cause and is reported as CONFIG.
     */
    public static DenialLayer denialLayer(AiSessionSettings settings, McpToolGroupEnum group) {
        if (group == McpToolGroupEnum.ALWAYS_ON) {
            return DenialLayer.NONE;
        }
        AiSessionSettings effectiveSettings = settings == null ? new AiSessionSettings() : settings;
        if (group == McpToolGroupEnum.BUILD_QUEUE) {
            return McpToolGroupEnum.BUILD_SYSTEM_GROUPS.stream()
                    .anyMatch(buildGroup -> isGroupEnabled(effectiveSettings, buildGroup))
                   ? DenialLayer.NONE : DenialLayer.MCP;
        }
        // Read once: the first start can set the session's own config at any moment, and two reads could then pair a
        // legacy-derived answer with "has its own config" and misreport the layer.
        McpToolConfig ownConfig = effectiveSettings.mcpToolConfig();
        boolean ownMcpConfig = ownConfig != null;
        boolean mcpOff = (ownMcpConfig ? ownConfig : LegacyToolGroups.derive(effectiveSettings)).isDisabled(group);
        if (mcpOff && ownMcpConfig) {
            return DenialLayer.MCP;
        }
        if (!configSettingAllows(effectiveSettings, group)) {
            return DenialLayer.CONFIG;
        }
        return mcpOff ? DenialLayer.MCP : DenialLayer.NONE;
    }

    private static boolean configSettingAllows(AiSessionSettings settings, McpToolGroupEnum group) {
        return switch (group) {
            case DATABASE ->
                settings.effectiveAllowDatabaseAccess();
            case GIT_READ ->
                settings.effectiveAllowGitAccess()
                && settings.effectiveAllowGitAccessOption(GitAccessOptionEnum.READ);
            case GIT_WRITE ->
                settings.effectiveAllowGitAccess()
                && settings.effectiveAllowGitAccessOption(GitAccessOptionEnum.WRITE);
            case WEB ->
                settings.effectiveAllowWebRequests();
            case CLIPBOARD ->
                settings.effectiveEnableClipboardAccess();
            case PEER_MESSAGING ->
                settings.effectiveAllowInterAiComms();
            case IDLE_WATCHERS ->
                settings.effectiveAllowIdleWatcherTimer();
            default ->
                true;
        };
    }

    /**
     * The refusal text for a group switched off at the MCP layer.
     */
    public static String switchedOffMessage(McpToolGroupEnum group) {
        return "The '" + group.label() + "' tool group is switched off for this session "
                + "(session settings → MCP Tools).";
    }

    /**
     * The Build queue group has no switch of its own — it is on whenever any build group is — so its refusal points at
     * the build groups instead of a switch that does not exist.
     */
    private static String noBuildGroupMessage() {
        return "No build tool group is switched on for this session (session settings → MCP Tools).";
    }

    /**
     * Why {@code group} is refused for this session, worded for the layer that refuses it, or null if it is available.
     * The MCP wording is the same for every group; the config wording names the setting the user has to change, and is
     * only ever shown with the MCP group on, where that setting is visible.
     */
    public static String refusalMessage(AiSessionSettings settings, McpToolGroupEnum group) {
        AiSessionSettings effectiveSettings = settings == null ? new AiSessionSettings() : settings;
        return switch (denialLayer(effectiveSettings, group)) {
            case NONE ->
                null;
            case MCP ->
                group == McpToolGroupEnum.BUILD_QUEUE ? noBuildGroupMessage() : switchedOffMessage(group);
            case CONFIG ->
                configRefusalMessage(effectiveSettings, group);
        };
    }

    private static String configRefusalMessage(AiSessionSettings settings, McpToolGroupEnum group) {
        return switch (group) {
            case DATABASE ->
                askToEnable("Database access", AccessControlLabelEnum.ALLOW_DATABASE_ACCESS.displayLabel(), group);
            case WEB ->
                askToEnable("Web request access", AccessControlLabelEnum.ALLOW_WEB_REQUESTS.displayLabel(), group);
            case CLIPBOARD ->
                askToEnable("Clipboard access", AccessControlLabelEnum.ALLOW_CLIPBOARD_ACCESS.displayLabel(), group);
            case GIT_READ, GIT_WRITE -> {
                if (!settings.effectiveAllowGitAccess()) {
                    yield askToEnable("Git access", AccessControlLabelEnum.ALLOW_GIT_ACCESS.displayLabel(), group);
                }
                GitAccessOptionEnum option = group == McpToolGroupEnum.GIT_WRITE
                                             ? GitAccessOptionEnum.WRITE : GitAccessOptionEnum.READ;
                yield askToEnable(option == GitAccessOptionEnum.WRITE ? "Git write access" : "Git read access",
                                  option.label().displayLabel(), group);
            }
            case PEER_MESSAGING ->
                askToEnable("Inter-AI communication", AccessControlLabelEnum.ALLOW_INTER_AI_COMMS.displayLabel(),
                            group);
            case IDLE_WATCHERS ->
                askToEnable("The idle watcher timer", AccessControlLabelEnum.ALLOW_IDLE_WATCHER_TIMER.displayLabel(),
                            group);
            default ->
                "'" + group.label() + "' is disabled for this session.";
        };
    }

    /**
     * One wording for every config-layer refusal: what is off, then the exact UI label of the setting the user has to
     * switch on and where it is. The model cannot change session settings itself, so it is always told to ask.
     */
    private static String askToEnable(String subject, String settingLabel, McpToolGroupEnum group) {
        // The git settings sit once under the Git category, not under either git group's own switch.
        String where = group.category() == McpToolCategoryEnum.GIT ? group.category().label() : group.label();
        return subject + " is disabled for this session. Ask the user to enable '" + settingLabel
                + "' in this session's settings (MCP Tools → " + where + ") before retrying.";
    }

    public static boolean isToolEnabled(AiSessionSettings settings, McpToolEnum tool,
                                        McpToolInterface handler) {
        return isGroupEnabled(settings, McpToolGroupEnum.groupOf(tool, handler));
    }

    /**
     * The refusal for a call to a tool this session cannot use, naming the tool and the layer that refuses it. Falls
     * back to a generic line when no layer refuses it.
     */
    public static String unavailableMessage(AiSessionSettings settings, McpToolEnum tool, McpToolInterface handler) {
        AiSessionSettings effectiveSettings = settings == null ? new AiSessionSettings() : settings;
        McpToolGroupEnum group = McpToolGroupEnum.groupOf(tool, handler);
        String reason = refusalMessage(effectiveSettings, group);
        if (reason == null) {
            reason = "It is not part of this session's tool set.";
        }
        return "Tool not enabled for this session: " + tool.toolName() + ". " + reason;
    }

    public static Map<McpToolEnum, McpToolInterface> effectiveTools(
            AiSessionSettings settings, Map<McpToolEnum, McpToolInterface> handlers) {
        Map<McpToolEnum, McpToolInterface> effective = new LinkedHashMap<>();
        for (Map.Entry<McpToolEnum, McpToolInterface> entry : handlers.entrySet()) {
            if (isToolEnabled(settings, entry.getKey(), entry.getValue())) {
                effective.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(effective);
    }
}

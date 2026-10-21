package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpToolAccessTest {

    @Test
    void groupEnabledHandlesAlwaysOnAndBuildQueue() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.withDisabled(McpToolGroupEnum.BUILD_SYSTEM_GROUPS));

        assertTrue(McpToolAccess.isGroupEnabled(settings, McpToolGroupEnum.ALWAYS_ON));
        assertFalse(McpToolAccess.isGroupEnabled(settings, McpToolGroupEnum.BUILD_QUEUE));
        assertFalse(McpToolAccess.isGroupEnabled(settings, McpToolGroupEnum.MAVEN));

        settings.setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.MAVEN)));
        assertTrue(McpToolAccess.isGroupEnabled(settings, McpToolGroupEnum.BUILD_QUEUE));
    }

    @Test
    void explicitConfigurationStillHonoursLegacyConfigToggles() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowGitAccess(false);
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);

        assertFalse(McpToolAccess.isToolEnabled(settings, McpToolEnum.GIT_LOG,
                                                handler(McpSectionEnum.GIT, false)));
    }

    @Test
    void configLayerGroupsRequireBothTheMcpAndConfigLayers() {
        assertTwoLayerTruthTable(McpToolGroupEnum.DATABASE,
                                 (settings, enabled) -> settings.setAllowDatabaseAccess(enabled));
        assertTwoLayerTruthTable(McpToolGroupEnum.GIT_READ, (settings, enabled) -> {
                             settings.setAllowGitAccess(true);
                             settings.setAllowGitAccessOption(GitAccessOptionEnum.READ, enabled);
                         });
        assertTwoLayerTruthTable(McpToolGroupEnum.GIT_WRITE, (settings, enabled) -> {
                             settings.setAllowGitAccess(true);
                             settings.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, enabled);
                         });
        assertTwoLayerTruthTable(McpToolGroupEnum.WEB,
                                 (settings, enabled) -> settings.setAllowWebRequests(enabled));
        assertTwoLayerTruthTable(McpToolGroupEnum.CLIPBOARD,
                                 (settings, enabled) -> settings.setEnableClipboardAccess(enabled));
    }

    @Test
    void gitMasterOffDisablesBothGitGroupsDespiteAnEnabledMcpConfig() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        settings.setAllowGitAccess(false);
        settings.setAllowGitAccessOption(GitAccessOptionEnum.READ, true);
        settings.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, true);

        assertFalse(McpToolAccess.isGroupEnabled(settings, McpToolGroupEnum.GIT_READ));
        assertFalse(McpToolAccess.isGroupEnabled(settings, McpToolGroupEnum.GIT_WRITE));
    }

    @Test
    void reviewerPeerSettingsKeepGitWriteHiddenWhileLeavingGitReadVisible() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        settings.setAllowGitAccess(true);
        settings.setAllowGitAccessOption(GitAccessOptionEnum.READ, true);
        settings.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, false);

        assertTrue(McpToolAccess.isGroupEnabled(settings, McpToolGroupEnum.GIT_READ));
        assertFalse(McpToolAccess.isGroupEnabled(settings, McpToolGroupEnum.GIT_WRITE));
    }

    @Test
    void coderPeerSettingsKeepWebHiddenWhenItsConfigSettingIsOff() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        settings.setAllowWebRequests(false);

        assertFalse(McpToolAccess.isGroupEnabled(settings, McpToolGroupEnum.WEB));
    }

    @Test
    void eachBehaviourSettingGatesOnlyItsOwnCollaborationToolsAndNeverTheOthers() {
        McpToolInterface plugin = handler(McpSectionEnum.PLUGIN, false);
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);

        settings.setAllowInterAiComms(false);
        settings.setAllowIdleWatcherTimer(false);
        assertFalse(McpToolAccess.isToolEnabled(settings, McpToolEnum.PEER_MESSAGE_LIST, plugin));
        assertFalse(McpToolAccess.isToolEnabled(settings, McpToolEnum.PEER_SESSION_LIST, plugin));
        assertFalse(McpToolAccess.isToolEnabled(settings, McpToolEnum.PEER_IDLE_WATCHER_LIST, plugin));
        assertTrue(McpToolAccess.isToolEnabled(settings, McpToolEnum.ASK_USER_QUESTION, plugin),
                   "Ask the user has no behaviour setting");
        assertTrue(McpToolAccess.isToolEnabled(settings, McpToolEnum.GET_PLUGIN_VERSION, plugin));

        settings.setAllowInterAiComms(true);
        assertTrue(McpToolAccess.isToolEnabled(settings, McpToolEnum.PEER_MESSAGE_LIST, plugin));
        assertFalse(McpToolAccess.isToolEnabled(settings, McpToolEnum.PEER_IDLE_WATCHER_LIST, plugin),
                    "comms on must not switch the idle watcher tools on");

        settings.setAllowInterAiComms(false);
        settings.setAllowIdleWatcherTimer(true);
        assertFalse(McpToolAccess.isToolEnabled(settings, McpToolEnum.PEER_MESSAGE_LIST, plugin),
                    "the idle timer must not switch the peer tools on");
        assertTrue(McpToolAccess.isToolEnabled(settings, McpToolEnum.PEER_IDLE_WATCHER_LIST, plugin));
    }

    @Test
    void nullSettingsFollowThePluginDefaultsAndDeriveNothingDisabled() {
        // The plugin defaults are read from PluginSettings itself, not from a second AiSessionSettings that would
        // restate the same expression.
        assertEquals(PluginSettings.isAllowWebRequests(), McpToolAccess.isGroupEnabled(null, McpToolGroupEnum.WEB));
        assertEquals(PluginSettings.isEnableClipboardAccess(),
                     McpToolAccess.isGroupEnabled(null, McpToolGroupEnum.CLIPBOARD));
        assertEquals(PluginSettings.isAllowInterAiComms(),
                     McpToolAccess.isToolEnabled(null, McpToolEnum.PEER_MESSAGE_LIST,
                                                 handler(McpSectionEnum.PLUGIN, false)));
        assertTrue(McpToolAccess.isGroupEnabled(null, McpToolGroupEnum.ALWAYS_ON));
        assertTrue(McpToolAccess.isGroupEnabled(null, McpToolGroupEnum.FILE_READING),
                   "groups with no config setting are on when nothing disables them");
        assertTrue(McpToolAccess.isGroupEnabled(null, McpToolGroupEnum.MAVEN));
    }

    @Test
    void effectiveToolsPreservesHandlerOrderAndIsUnmodifiable() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.WEB)));
        Map<McpToolEnum, McpToolInterface> handlers = new LinkedHashMap<>();
        handlers.put(McpToolEnum.GET_INSTRUCTIONS, handler(McpSectionEnum.PLUGIN, false));
        handlers.put(McpToolEnum.WEB_REQUEST, handler(McpSectionEnum.SYSTEM, false));

        Map<McpToolEnum, McpToolInterface> effective = McpToolAccess.effectiveTools(settings, handlers);

        assertTrue(effective.containsKey(McpToolEnum.GET_INSTRUCTIONS));
        assertFalse(effective.containsKey(McpToolEnum.WEB_REQUEST));
        assertThrows(UnsupportedOperationException.class, effective::clear);
    }

    @Test
    void effectiveToolsOmitsAToolDisabledByItsConfigSetting() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        settings.setAllowWebRequests(false);
        Map<McpToolEnum, McpToolInterface> handlers = new LinkedHashMap<>();
        handlers.put(McpToolEnum.GET_INSTRUCTIONS, handler(McpSectionEnum.PLUGIN, false));
        handlers.put(McpToolEnum.WEB_REQUEST, handler(McpSectionEnum.SYSTEM, false));

        Map<McpToolEnum, McpToolInterface> effective = McpToolAccess.effectiveTools(settings, handlers);

        assertTrue(effective.containsKey(McpToolEnum.GET_INSTRUCTIONS));
        assertFalse(effective.containsKey(McpToolEnum.WEB_REQUEST));
    }

    // ---- which layer refuses, and what it says ----
    @Test
    void denialLayerNamesTheMcpLayerWhenTheGroupIsSwitchedOffWhatEverTheConfigSays() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.WEB)));

        settings.setAllowWebRequests(true);
        assertEquals(McpToolAccess.DenialLayer.MCP, McpToolAccess.denialLayer(settings, McpToolGroupEnum.WEB));
        settings.setAllowWebRequests(false);
        assertEquals(McpToolAccess.DenialLayer.MCP, McpToolAccess.denialLayer(settings, McpToolGroupEnum.WEB),
                     "the MCP layer takes precedence when both refuse");
    }

    @Test
    void denialLayerNamesTheConfigLayerWhenTheMcpGroupIsOnButTheSettingIsOff() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        settings.setEnableClipboardAccess(false);

        assertEquals(McpToolAccess.DenialLayer.CONFIG,
                     McpToolAccess.denialLayer(settings, McpToolGroupEnum.CLIPBOARD));
    }

    @Test
    void denialLayerIsNoneWhenBothLayersAllowAndForGroupsWithNoConfigSetting() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        settings.setAllowDatabaseAccess(true);

        assertEquals(McpToolAccess.DenialLayer.NONE, McpToolAccess.denialLayer(settings, McpToolGroupEnum.DATABASE));
        assertEquals(McpToolAccess.DenialLayer.NONE, McpToolAccess.denialLayer(settings, McpToolGroupEnum.MAVEN));
        assertEquals(McpToolAccess.DenialLayer.NONE, McpToolAccess.denialLayer(settings, McpToolGroupEnum.ALWAYS_ON));
    }

    @Test
    void aSessionThatHasNotStartedReportsTheConfigSettingBecauseItsMcpLayerIsDerivedFromIt() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowWebRequests(false);

        assertEquals(McpToolAccess.DenialLayer.CONFIG, McpToolAccess.denialLayer(settings, McpToolGroupEnum.WEB),
                     "no own MCP config yet: the derived MCP layer is just the config setting restated");
    }

    @Test
    void buildQueueIsRefusedByTheMcpLayerOnlyWhenEveryBuildGroupIsOff() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.withDisabled(McpToolGroupEnum.BUILD_SYSTEM_GROUPS));
        assertEquals(McpToolAccess.DenialLayer.MCP, McpToolAccess.denialLayer(settings, McpToolGroupEnum.BUILD_QUEUE));

        settings.setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.MAVEN)));
        assertEquals(McpToolAccess.DenialLayer.NONE, McpToolAccess.denialLayer(settings, McpToolGroupEnum.BUILD_QUEUE));
    }

    @Test
    void refusalMessageIsWordedForTheLayerThatRefuses() {
        AiSessionSettings mcpOff = new AiSessionSettings();
        mcpOff.setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.DATABASE)));
        assertEquals("The 'Database' tool group is switched off for this session (session settings → MCP Tools).",
                     McpToolAccess.refusalMessage(mcpOff, McpToolGroupEnum.DATABASE));

        AiSessionSettings configOff = new AiSessionSettings();
        configOff.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        configOff.setAllowDatabaseAccess(false);
        assertTrue(McpToolAccess.refusalMessage(configOff, McpToolGroupEnum.DATABASE)
                .contains("enable 'Allow database access' in this session's settings"));

        AiSessionSettings allowed = new AiSessionSettings();
        allowed.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        allowed.setAllowDatabaseAccess(true);
        assertNull(McpToolAccess.refusalMessage(allowed, McpToolGroupEnum.DATABASE));
    }

    @Test
    void gitRefusalReportsTheMasterBeforeTheOptionForTheConfigLayer() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        settings.setAllowGitAccess(false);
        settings.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, false);
        assertTrue(McpToolAccess.refusalMessage(settings, McpToolGroupEnum.GIT_WRITE)
                .startsWith("Git access is disabled for this session."));

        settings.setAllowGitAccess(true);
        assertTrue(McpToolAccess.refusalMessage(settings, McpToolGroupEnum.GIT_WRITE)
                .contains("Allow git commands that modify the repository"));
    }

    @Test
    void unavailableMessageNamesTheToolAndTheRefusingLayer() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.CLIPBOARD)));

        String message = McpToolAccess.unavailableMessage(settings, McpToolEnum.GET_CLIPBOARD,
                                                          handler(McpSectionEnum.SYSTEM, false));

        assertTrue(message.startsWith("Tool not enabled for this session: GetClipboard. "), message);
        assertTrue(message.contains("The 'Clipboard' tool group is switched off"), message);
    }

    @Test
    void unavailableMessageExplainsTheBehaviourSettingsThatGateCollaborationTools() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        settings.setAllowInterAiComms(false);
        settings.setAllowIdleWatcherTimer(false);
        McpToolInterface handler = handler(McpSectionEnum.PLUGIN, false);

        assertTrue(McpToolAccess.unavailableMessage(settings, McpToolEnum.PEER_MESSAGE_LIST, handler)
                .contains("Inter-AI communication is disabled for this session"));
        assertTrue(McpToolAccess.unavailableMessage(settings, McpToolEnum.PEER_IDLE_WATCHER_LIST, handler)
                .contains("The idle watcher timer is disabled for this session"));
    }

    @Test
    void commsGateIsTheConfigLayerForPeerMessaging() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        settings.setAllowInterAiComms(false);

        assertFalse(McpToolAccess.isGroupEnabled(settings, McpToolGroupEnum.PEER_MESSAGING));
        assertEquals(McpToolAccess.DenialLayer.CONFIG,
                     McpToolAccess.denialLayer(settings, McpToolGroupEnum.PEER_MESSAGING));
        assertEquals("Inter-AI communication is disabled for this session. Ask the user to enable "
                + "'Allow inter-AI communication' in this session's settings (MCP Tools → Peer messaging) before retrying.",
                     McpToolAccess.refusalMessage(settings, McpToolGroupEnum.PEER_MESSAGING));

        settings.setAllowInterAiComms(true);
        assertTrue(McpToolAccess.isGroupEnabled(settings, McpToolGroupEnum.PEER_MESSAGING));

        settings.setAllowInterAiComms(false);
        settings.setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.PEER_MESSAGING)));
        assertEquals(McpToolAccess.DenialLayer.MCP,
                     McpToolAccess.denialLayer(settings, McpToolGroupEnum.PEER_MESSAGING));
    }

    @Test
    void idleWatcherGateIsTheConfigLayerForIdleWatchers() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        settings.setAllowIdleWatcherTimer(false);

        assertFalse(McpToolAccess.isGroupEnabled(settings, McpToolGroupEnum.IDLE_WATCHERS));
        assertEquals(McpToolAccess.DenialLayer.CONFIG,
                     McpToolAccess.denialLayer(settings, McpToolGroupEnum.IDLE_WATCHERS));
        assertEquals("The idle watcher timer is disabled for this session. Ask the user to enable "
                + "'Allow idle AI watcher timer' in this session's settings (MCP Tools → Idle watchers) before retrying.",
                     McpToolAccess.refusalMessage(settings, McpToolGroupEnum.IDLE_WATCHERS));

        settings.setAllowIdleWatcherTimer(true);
        assertTrue(McpToolAccess.isGroupEnabled(settings, McpToolGroupEnum.IDLE_WATCHERS));

        settings.setAllowIdleWatcherTimer(false);
        settings.setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.IDLE_WATCHERS)));
        assertEquals(McpToolAccess.DenialLayer.MCP,
                     McpToolAccess.denialLayer(settings, McpToolGroupEnum.IDLE_WATCHERS));
    }

    @Test
    void configRefusalTextNamesTheSettingForWebClipboardAndGitRead() {
        AiSessionSettings web = new AiSessionSettings();
        web.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        web.setAllowWebRequests(false);
        assertTrue(McpToolAccess.refusalMessage(web, McpToolGroupEnum.WEB)
                .contains("enable 'Allow web requests' in this session's settings (MCP Tools → Web)"));

        AiSessionSettings clipboard = new AiSessionSettings();
        clipboard.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        clipboard.setEnableClipboardAccess(false);
        assertEquals("Clipboard access is disabled for this session. Ask the user to enable "
                + "'Allow clipboard access' in this session's settings (MCP Tools → Clipboard) before retrying.",
                     McpToolAccess.refusalMessage(clipboard, McpToolGroupEnum.CLIPBOARD));

        AiSessionSettings gitRead = new AiSessionSettings();
        gitRead.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        gitRead.setAllowGitAccess(true);
        gitRead.setAllowGitAccessOption(GitAccessOptionEnum.READ, false);
        String message = McpToolAccess.refusalMessage(gitRead, McpToolGroupEnum.GIT_READ);
        assertTrue(message.contains("Git read access is disabled for this session. Ask the user to enable "
                + "'Allow read-only git commands' in this session's settings (MCP Tools → Git) before retrying."),
                   message);
        assertFalse(message.startsWith("Git access is disabled"), message);
    }

    @Test
    void configRefusalsPointOnlyAtThisSessionsSettingsBecauseTheGlobalControlsAreGone() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        settings.setAllowDatabaseAccess(false);
        settings.setAllowWebRequests(false);
        settings.setEnableClipboardAccess(false);
        settings.setAllowGitAccess(false);
        settings.setAllowInterAiComms(false);
        settings.setAllowIdleWatcherTimer(false);

        for (McpToolGroupEnum group : new McpToolGroupEnum[]{McpToolGroupEnum.DATABASE, McpToolGroupEnum.WEB,
            McpToolGroupEnum.CLIPBOARD, McpToolGroupEnum.GIT_READ, McpToolGroupEnum.GIT_WRITE,
            McpToolGroupEnum.PEER_MESSAGING, McpToolGroupEnum.IDLE_WATCHERS}) {
            String message = McpToolAccess.refusalMessage(settings, group);
            assertEquals(McpToolAccess.DenialLayer.CONFIG, McpToolAccess.denialLayer(settings, group), group.name());
            assertTrue(message.contains("session"), message);
            assertFalse(message.contains("global"), group + ": " + message);
            assertFalse(message.contains("Options"), group + ": " + message);
            assertTrue(message.contains("MCP Tools"), group + " should say where to change it: " + message);
        }
    }

    @Test
    void buildQueueRefusalPointsAtTheBuildGroupsBecauseItHasNoSwitchOfItsOwn() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.withDisabled(McpToolGroupEnum.BUILD_SYSTEM_GROUPS));

        assertEquals("No build tool group is switched on for this session (session settings → MCP Tools).",
                     McpToolAccess.refusalMessage(settings, McpToolGroupEnum.BUILD_QUEUE));

        settings.setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.MAVEN)));
        assertNull(McpToolAccess.refusalMessage(settings, McpToolGroupEnum.BUILD_QUEUE),
                   "no refusal once any build group is on");
    }

    private static void assertTwoLayerTruthTable(McpToolGroupEnum group,
                                                 BiConsumer<AiSessionSettings, Boolean> configLayer) {
        AiSessionSettings mcpOff = new AiSessionSettings();
        mcpOff.setMcpToolConfig(McpToolConfig.withDisabled(Set.of(group)));
        configLayer.accept(mcpOff, true);
        assertFalse(McpToolAccess.isGroupEnabled(mcpOff, group), group + " must require the MCP layer");

        AiSessionSettings configOff = new AiSessionSettings();
        configOff.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        configLayer.accept(configOff, false);
        assertFalse(McpToolAccess.isGroupEnabled(configOff, group), group + " must require the config layer");

        AiSessionSettings bothOn = new AiSessionSettings();
        bothOn.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        configLayer.accept(bothOn, true);
        assertTrue(McpToolAccess.isGroupEnabled(bothOn, group), group + " should be enabled when both layers allow it");
    }

    private static McpToolInterface handler(McpSectionEnum section, boolean mutating) {
        return new McpToolInterface() {
            @Override
            public McpSectionEnum section() {
                return section;
            }

            @Override
            public boolean isMutating() {
                return mutating;
            }

            @Override
            public String instruction(Set<McpInstructionOptionEnum> options) {
                return null;
            }

            @Override
            public JsonObject schema(Set<McpInstructionOptionEnum> options) {
                return new JsonObject();
            }

            @Override
            public String handle(kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments args,
                                 kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession session) {
                return "";
            }
        };
    }
}

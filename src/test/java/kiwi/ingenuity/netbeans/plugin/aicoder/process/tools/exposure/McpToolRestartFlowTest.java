package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpToolRestartFlowTest {

    private static final class StubTool extends AbstractActionTool {

        StubTool(McpToolEnum tool) {
            super(McpSectionEnum.PLUGIN, tool.toolName(), "stub", tool.toolName() + " -> stub");
        }

        @Override
        public boolean isMutating() {
            return false;
        }

        @Override
        public String handle(ToolRequestArguments args, AbstractAiSession session) {
            return "ran";
        }
    }

    private static Map<McpToolEnum, McpToolInterface> handlers() {
        Map<McpToolEnum, McpToolInterface> handlers = new EnumMap<>(McpToolEnum.class);
        for (McpToolEnum tool : new McpToolEnum[]{McpToolEnum.GET_INSTRUCTIONS, McpToolEnum.WEB_REQUEST,
            McpToolEnum.GET_CLIPBOARD, McpToolEnum.PEER_MESSAGE_LIST, McpToolEnum.PEER_IDLE_WATCHER_LIST}) {
            handlers.put(tool, new StubTool(tool));
        }
        return handlers;
    }

    private static AiSessionSettings settings(boolean comms, boolean idleTimer, McpToolGroupEnum... disabled) {
        AiSessionSettings settings = new AiSessionSettings(null, null, comms, null, null, null, null, null);
        settings.setAllowIdleWatcherTimer(idleTimer);
        settings.setMcpToolConfig(McpToolConfig.withDisabled(Set.of(disabled)));
        return settings;
    }

    @Test
    void newlyExposedIsOnlyWhatTheAfterSetAddsToTheBeforeSet() {
        Set<McpToolEnum> before = Set.of(McpToolEnum.GET_INSTRUCTIONS, McpToolEnum.WEB_REQUEST);
        Set<McpToolEnum> after = Set.of(McpToolEnum.GET_INSTRUCTIONS, McpToolEnum.GET_CLIPBOARD);

        assertEquals(Set.of(McpToolEnum.GET_CLIPBOARD), McpToolRestartFlow.newlyExposed(before, after));
    }

    @Test
    void switchingAGroupOnExposesItsToolsAndPrompts() {
        Set<McpToolEnum> before = McpToolRestartFlow.exposedTools(settings(true, true, McpToolGroupEnum.WEB), handlers());
        Set<McpToolEnum> after = McpToolRestartFlow.exposedTools(settings(true, true), handlers());

        assertEquals(Set.of(McpToolEnum.WEB_REQUEST), McpToolRestartFlow.newlyExposed(before, after));
        assertTrue(McpToolRestartFlow.shouldPrompt(AiTypeEnum.CODEX, true, before, after));
    }

    @Test
    void aConfigSettingTurnedOnUnderAnEnabledMcpGroupExposesTheToolAndPrompts() {
        AiSessionSettings configOff = settings(true, true);
        configOff.setAllowWebRequests(false);
        AiSessionSettings configOn = settings(true, true);
        configOn.setAllowWebRequests(true);

        Set<McpToolEnum> before = McpToolRestartFlow.exposedTools(configOff, handlers());
        Set<McpToolEnum> after = McpToolRestartFlow.exposedTools(configOn, handlers());

        assertFalse(before.contains(McpToolEnum.WEB_REQUEST));
        assertEquals(Set.of(McpToolEnum.WEB_REQUEST), McpToolRestartFlow.newlyExposed(before, after));
        assertTrue(McpToolRestartFlow.shouldPrompt(AiTypeEnum.CODEX, true, before, after));
    }

    @Test
    void anMcpGroupTurnedOnWhileItsConfigSettingIsStillOffExposesNothingAndDoesNotPrompt() {
        AiSessionSettings groupOff = settings(true, true, McpToolGroupEnum.WEB);
        groupOff.setAllowWebRequests(false);
        AiSessionSettings groupOn = settings(true, true);
        groupOn.setAllowWebRequests(false);

        Set<McpToolEnum> before = McpToolRestartFlow.exposedTools(groupOff, handlers());
        Set<McpToolEnum> after = McpToolRestartFlow.exposedTools(groupOn, handlers());

        assertEquals(before, after, "the config setting still blocks the tool, so the AI's tool list is unchanged");
        assertFalse(McpToolRestartFlow.shouldPrompt(AiTypeEnum.CODEX, true, before, after));
    }

    @Test
    void turningTheSecondLayerOnLastIsWhatFinallyExposesTheTool() {
        AiSessionSettings bothOff = settings(true, true, McpToolGroupEnum.WEB);
        bothOff.setAllowWebRequests(false);
        AiSessionSettings mcpOnConfigOff = settings(true, true);
        mcpOnConfigOff.setAllowWebRequests(false);
        AiSessionSettings bothOn = settings(true, true);
        bothOn.setAllowWebRequests(true);

        Set<McpToolEnum> first = McpToolRestartFlow.exposedTools(bothOff, handlers());
        Set<McpToolEnum> second = McpToolRestartFlow.exposedTools(mcpOnConfigOff, handlers());
        Set<McpToolEnum> third = McpToolRestartFlow.exposedTools(bothOn, handlers());

        assertFalse(McpToolRestartFlow.shouldPrompt(AiTypeEnum.CODEX, true, first, second));
        assertTrue(McpToolRestartFlow.shouldPrompt(AiTypeEnum.CODEX, true, second, third));
    }

    @Test
    void turningInterAiCommsOnExposesThePeerToolsEvenThoughNoGroupChanged() {
        Set<McpToolEnum> before = McpToolRestartFlow.exposedTools(settings(false, true), handlers());
        Set<McpToolEnum> after = McpToolRestartFlow.exposedTools(settings(true, true), handlers());

        assertEquals(Set.of(McpToolEnum.PEER_MESSAGE_LIST), McpToolRestartFlow.newlyExposed(before, after));
        assertTrue(McpToolRestartFlow.shouldPrompt(AiTypeEnum.CLAUDE, true, before, after));
    }

    @Test
    void turningTheIdleWatcherTimerOnExposesTheWatcherTools() {
        Set<McpToolEnum> before = McpToolRestartFlow.exposedTools(settings(true, false), handlers());
        Set<McpToolEnum> after = McpToolRestartFlow.exposedTools(settings(true, true), handlers());

        assertEquals(Set.of(McpToolEnum.PEER_IDLE_WATCHER_LIST), McpToolRestartFlow.newlyExposed(before, after));
    }

    @Test
    void turningThingsOffNeverPrompts() {
        Set<McpToolEnum> before = McpToolRestartFlow.exposedTools(settings(true, true), handlers());
        Set<McpToolEnum> after = McpToolRestartFlow.exposedTools(
                settings(false, false, McpToolGroupEnum.WEB, McpToolGroupEnum.CLIPBOARD), handlers());

        assertTrue(McpToolRestartFlow.newlyExposed(before, after).isEmpty());
        assertFalse(McpToolRestartFlow.shouldPrompt(AiTypeEnum.CODEX, true, before, after));
    }

    @Test
    void ollamaAppliesToolChangesLiveSoItNeverPrompts() {
        Set<McpToolEnum> before = Set.of(McpToolEnum.GET_INSTRUCTIONS);
        Set<McpToolEnum> after = Set.of(McpToolEnum.GET_INSTRUCTIONS, McpToolEnum.WEB_REQUEST);

        assertFalse(McpToolRestartFlow.shouldPrompt(AiTypeEnum.OLLAMA_LOCAL, true, before, after));
        assertTrue(McpToolRestartFlow.shouldPrompt(AiTypeEnum.CODEX, true, before, after));
    }

    @Test
    void aBackendThatIsNotRunningNeedsNoPromptBecauseItsNextStartCarriesTheNewTools() {
        Set<McpToolEnum> before = Set.of(McpToolEnum.GET_INSTRUCTIONS);
        Set<McpToolEnum> after = Set.of(McpToolEnum.GET_INSTRUCTIONS, McpToolEnum.WEB_REQUEST);

        assertFalse(McpToolRestartFlow.shouldPrompt(AiTypeEnum.CODEX, false, before, after));
    }

    @Test
    void thePromptOffersARestartAndMakesNoClaimAboutLosingHistory() {
        String text = McpToolRestartFlow.promptText();

        assertEquals("Tool changes apply when the session restarts. Restart now?", text);
        assertFalse(text.toLowerCase().contains("history"), "every backend that prompts resumes its conversation");
    }
}

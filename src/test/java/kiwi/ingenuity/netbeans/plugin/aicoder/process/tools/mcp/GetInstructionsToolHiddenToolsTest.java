package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpInstructionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.plugin.GetInstructionsTool;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GetInstructions is built per calling session, from that session's OWN handler map (the one {@code tools/list} answers
 * from): a tool the session has switched off contributes no instruction line, and no sentence anywhere in the guide may
 * point at it.
 */
class GetInstructionsToolHiddenToolsTest {

    private Map<McpToolEnum, McpToolInterface> priorClaudeRegistry;

    private static final class StubTool extends AbstractActionTool {

        StubTool(McpToolEnum tool) {
            super(McpSectionEnum.PLUGIN, tool.toolName(), "stub", tool.toolName() + " -> stub line");
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

    private static final class FakeSession extends AbstractAiSession {

        private final Map<McpToolEnum, McpToolInterface> handlers;

        FakeSession(AiSession session, Map<McpToolEnum, McpToolInterface> handlers) {
            super(session);
            this.handlers = handlers;
        }

        @Override
        public String getId() {
            return getAiSession().id();
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return null;
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return handlers;
        }
    }

    private static Map<McpToolEnum, McpToolInterface> handlersOf(McpToolEnum... tools) {
        Map<McpToolEnum, McpToolInterface> handlers = new EnumMap<>(McpToolEnum.class);
        handlers.put(McpToolEnum.GET_INSTRUCTIONS, new GetInstructionsTool());
        for (McpToolEnum tool : tools) {
            handlers.put(tool, new StubTool(tool));
        }
        return handlers;
    }

    private static FakeSession sessionWith(McpToolConfig config, Map<McpToolEnum, McpToolInterface> handlers) {
        AiSessionSettings settings = new AiSessionSettings();
        // Clipboard visibility is varied by the MCP config in these tests, not its config-layer setting.
        settings.setEnableClipboardAccess(true);
        settings.setMcpToolConfig(config);
        return new FakeSession(new AiSession("hidden-tools-instr", "hidden-tools-instr", null, AiTypeEnum.CLAUDE, null,
                                             settings, Instant.now(), Instant.now()), handlers);
    }

    private static FakeSession sessionWith(McpToolConfig config) {
        return sessionWith(config, handlersOf(McpToolEnum.GET_PLUGIN_VERSION, McpToolEnum.GET_CLIPBOARD,
                                              McpToolEnum.SEARCH_IN_FILES));
    }

    @BeforeEach
    void rememberRegistry() {
        priorClaudeRegistry = McpInstructionRegistry.getHandlers(AiTypeEnum.CLAUDE);
    }

    @AfterEach
    void restoreRegistry() {
        if (!priorClaudeRegistry.isEmpty()) {
            McpInstructionRegistry.registerHandlers(AiTypeEnum.CLAUDE, priorClaudeRegistry);
        }
    }

    private static String instructionsFor(FakeSession session) {
        return new GetInstructionsTool().handle(new ToolRequestArguments(new com.google.gson.JsonObject()), session);
    }

    @Test
    void aSessionWithEverythingOnSeesEveryToolsLine() {
        String text = instructionsFor(sessionWith(McpToolConfig.ALL_ENABLED));

        assertTrue(text.contains("SearchInFiles -> stub line"), text);
        assertTrue(text.contains("GetClipboard -> stub line"), text);
        assertTrue(text.contains("GetPluginVersion -> stub line"), text);
    }

    @Test
    void aSwitchedOffGroupsToolsDisappearFromTheGuide() {
        String text = instructionsFor(sessionWith(
                McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.CLIPBOARD, McpToolGroupEnum.TEXT_SEARCH))));

        assertFalse(text.contains("SearchInFiles"), "no line, and no sentence, may name a tool the session cannot see");
        assertFalse(text.contains("GetClipboard"), text);
        assertTrue(text.contains("GetPluginVersion -> stub line"), "tools still on keep their lines");
    }

    @Test
    void theGuideIsBuiltFromTheSessionsOwnHandlersNotThePerTypeRegistry() {
        // The registry holds a SearchInFiles stub; this session's own map does not. The guide must follow the session.
        McpInstructionRegistry.registerHandlers(AiTypeEnum.CLAUDE, handlersOf(McpToolEnum.SEARCH_IN_FILES));
        FakeSession session = sessionWith(McpToolConfig.ALL_ENABLED, handlersOf(McpToolEnum.GET_PLUGIN_VERSION));

        String text = instructionsFor(session);

        assertTrue(text.contains("GetPluginVersion -> stub line"), text);
        assertFalse(text.contains("SearchInFiles -> stub line"),
                    "a tool the session does not have must not be advertised just because its type does");
    }

    @Test
    void theGuideStillLoadsTheInstructionsGate() {
        FakeSession session = sessionWith(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.CLIPBOARD)));
        assertFalse(session.getAiSession().isInstructionsLoaded());

        instructionsFor(session);

        assertTrue(session.getAiSession().isInstructionsLoaded());
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.McpTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolHandlerFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpToolMemoryExposureTest {

    private static final Set<McpToolEnum> MEMORY_TOOLS = EnumSet.of(McpToolEnum.MEMORY_SAVE, McpToolEnum.MEMORY_READ,
                                                                    McpToolEnum.MEMORY_DELETE);

    private static Map<McpToolEnum, McpToolInterface> handlers() {
        return ToolHandlerFactory.getToolHandlers(null);
    }

    @Test
    @Timeout(10)
    void theMemoryToolsFormOneGroupInTheirOwnCategory() {
        assertEquals(MEMORY_TOOLS, McpToolGroupEnum.MEMORY.fixedMembers());
        assertEquals(McpToolCategoryEnum.MEMORY, McpToolGroupEnum.MEMORY.category());
        assertEquals("Memory (OpenAI Compatible only)", McpToolCategoryEnum.MEMORY.label());
        assertTrue(McpToolGroupEnum.MEMORY.switchable());
        for (McpToolEnum tool : MEMORY_TOOLS) {
            assertEquals(McpToolGroupEnum.MEMORY, McpToolGroupEnum.groupOf(tool, handlers().get(tool)));
        }
    }

    @Test
    @Timeout(10)
    void onlyAnOpenAiCompatibleSessionIsOfferedTheMemoryTools() {
        AiSessionSettings openAi = new OpenAiCompatibleSessionSettings();
        AiSessionSettings other = new AiSessionSettings();

        assertTrue(McpToolAccess.isGroupEnabled(openAi, McpToolGroupEnum.MEMORY));
        assertFalse(McpToolAccess.isGroupEnabled(other, McpToolGroupEnum.MEMORY));
        assertFalse(McpToolAccess.isGroupEnabled(null, McpToolGroupEnum.MEMORY));
        assertTrue(McpToolAccess.effectiveTools(openAi, handlers()).keySet().containsAll(MEMORY_TOOLS));
        for (McpToolEnum tool : MEMORY_TOOLS) {
            assertFalse(McpToolAccess.effectiveTools(other, handlers()).containsKey(tool), tool.toString());
        }
        assertEquals(McpToolAccess.DenialLayer.CONFIG, McpToolAccess.denialLayer(other, McpToolGroupEnum.MEMORY));
        assertTrue(McpToolAccess.unavailableMessage(other, McpToolEnum.MEMORY_SAVE,
                                                    handlers().get(McpToolEnum.MEMORY_SAVE))
                .contains("not available in this kind of session"));
    }

    @Test
    @Timeout(10)
    void everyOtherGroupIsStillOfferedToEveryBackend() {
        AiSessionSettings other = new AiSessionSettings();
        for (McpToolGroupEnum group : McpToolGroupEnum.values()) {
            if (group != McpToolGroupEnum.MEMORY) {
                assertTrue(group.availableFor(other), group.toString());
            }
        }
    }

    @Test
    @Timeout(10)
    void theGroupIsOnByDefaultAndCanBeSwitchedOffPerSession() {
        AiSessionSettings openAi = new OpenAiCompatibleSessionSettings();
        assertFalse(LegacyToolGroups.derive(openAi).isDisabled(McpToolGroupEnum.MEMORY));
        assertFalse(McpTemplate.createDefault(McpToolConfig.ALL_ENABLED).config().isDisabled(McpToolGroupEnum.MEMORY));

        openAi.setMcpToolConfig(McpToolConfig.ALL_ENABLED.with(McpToolGroupEnum.MEMORY, true));

        assertFalse(McpToolAccess.isGroupEnabled(openAi, McpToolGroupEnum.MEMORY));
        assertEquals(McpToolAccess.DenialLayer.MCP, McpToolAccess.denialLayer(openAi, McpToolGroupEnum.MEMORY));
        assertFalse(McpToolAccess.effectiveTools(openAi, handlers()).containsKey(McpToolEnum.MEMORY_SAVE));
    }

    @Test
    @Timeout(10)
    void aSessionSavedBeforeTheGroupExistedGetsTheMemoryTools() {
        AiSessionSettings openAi = new OpenAiCompatibleSessionSettings();
        openAi.setMcpToolConfig(McpToolConfig.ALL_ENABLED.with(McpToolGroupEnum.WEB, true));

        assertTrue(McpToolAccess.isGroupEnabled(openAi, McpToolGroupEnum.MEMORY));
    }

    @Test
    @Timeout(10)
    void theMinimalTemplateLeavesMemoryOff() {
        assertTrue(McpTemplate.createMinimal().config().isDisabled(McpToolGroupEnum.MEMORY));
    }
}

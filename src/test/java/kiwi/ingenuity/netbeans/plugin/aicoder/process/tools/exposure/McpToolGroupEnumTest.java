package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeToolHandlerFactory;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class McpToolGroupEnumTest {

    @Test
    void gitToolsExactlyMatchGitSectionHandlersAndDynamicPartition() {
        Map<McpToolEnum, McpToolInterface> handlers = ClaudeToolHandlerFactory.build(() -> null, null);
        Set<McpToolEnum> gitTools = handlers.entrySet().stream()
                .filter(entry -> entry.getValue().section() == McpSectionEnum.GIT)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        assertEquals(gitTools, McpToolGroupEnum.GIT_TOOLS);
        assertEquals(Set.of(McpToolEnum.GET_GIT_STATUS, McpToolEnum.GET_GIT_DIFF,
                            McpToolEnum.GIT_LOG, McpToolEnum.GIT_SHOW, McpToolEnum.GIT_BLAME),
                     McpToolGroupEnum.GIT_READ.fixedMembers());

        for (McpToolEnum tool : gitTools) {
            McpToolGroupEnum expected = handlers.get(tool).isMutating()
                                        ? McpToolGroupEnum.GIT_WRITE : McpToolGroupEnum.GIT_READ;
            assertEquals(expected, McpToolGroupEnum.groupOf(tool, handlers.get(tool)));
        }
    }

    @Test
    void everyToolResolvesToExactlyOneGroup() {
        Map<McpToolEnum, McpToolInterface> handlers = ClaudeToolHandlerFactory.build(() -> null, null);
        assertEquals(EnumSet.allOf(McpToolEnum.class), handlers.keySet());
        for (McpToolEnum tool : McpToolEnum.values()) {
            McpToolGroupEnum resolved = McpToolGroupEnum.groupOf(tool, handlers.get(tool));
            long matches = java.util.Arrays.stream(McpToolGroupEnum.values())
                    .filter(group -> group == resolved).count();
            assertEquals(1, matches, tool::toString);
        }
    }

    @Test
    void onlyAlwaysOnAndBuildQueueAreFixedGroups() {
        Set<McpToolGroupEnum> fixed = EnumSet.noneOf(McpToolGroupEnum.class);
        for (McpToolGroupEnum group : McpToolGroupEnum.values()) {
            if (!group.switchable()) {
                fixed.add(group);
            }
        }
        assertEquals(Set.of(McpToolGroupEnum.ALWAYS_ON, McpToolGroupEnum.BUILD_QUEUE), fixed);
    }
}

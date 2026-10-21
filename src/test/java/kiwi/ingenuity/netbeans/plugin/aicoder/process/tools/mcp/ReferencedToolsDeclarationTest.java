package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.HiddenToolTextFilter;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ReferencedToolsDeclarationTest {

    /**
     * Whether {@code text} names {@code tool} is decided by {@link HiddenToolTextFilter#mentions}, the very rule the
     * filter applies at render time — not a copy of it. A copy with a different word boundary (this test once treated a
     * preceding underscore as "not a mention") would let a reference the filter does remove go undeclared.
     */
    @Test
    void everyCrossToolReferenceIsDeclaredAndEveryDeclarationIsUsed() {
        Map<McpToolEnum, McpToolInterface> handlers = ToolHandlerFactory.getToolHandlers(null);
        StringBuilder failures = new StringBuilder();
        for (Map.Entry<McpToolEnum, McpToolInterface> entry : handlers.entrySet()) {
            McpToolEnum owner = entry.getKey();
            McpToolInterface handler = entry.getValue();
            String text = String.valueOf(handler.instruction(EnumSet.allOf(McpInstructionOptionEnum.class)))
                    + handler.schema(EnumSet.allOf(McpInstructionOptionEnum.class));
            for (McpToolEnum candidate : McpToolEnum.values()) {
                if (candidate == owner) {
                    continue;
                }
                boolean mentions = HiddenToolTextFilter.mentions(text, candidate);
                boolean declared = McpToolReferences.of(owner).contains(candidate);
                if (mentions && !declared) {
                    failures.append("Tool ").append(owner.toolName()).append(" mentions ")
                            .append(candidate.toolName()).append(System.lineSeparator());
                }
                if (declared && !mentions) {
                    failures.append("Tool ").append(owner.toolName()).append(" declares stale ")
                            .append(candidate.toolName()).append(System.lineSeparator());
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());
    }

    private static Set<McpToolEnum> everyToolButGetInstructions() {
        Set<McpToolEnum> hidden = EnumSet.allOf(McpToolEnum.class);
        hidden.remove(McpToolEnum.GET_INSTRUCTIONS);
        return hidden;
    }

    private static String namedTools(String text, Set<McpToolEnum> tools) {
        StringBuilder named = new StringBuilder();
        for (McpToolEnum tool : tools) {
            if (HiddenToolTextFilter.mentions(text, tool)) {
                named.append(' ').append(tool.toolName());
            }
        }
        return named.toString();
    }

    /**
     * The initialize stub is assembled entirely from tool lists, so with every tool hidden it must name none of them
     * before any sentence filtering — the lists themselves have to be built from the visible set.
     */
    @Test
    void theInitializeStubBuiltForASessionWithNoToolsNamesNoHiddenTool() {
        Set<McpToolEnum> hidden = everyToolButGetInstructions();
        StringBuilder failures = new StringBuilder();
        for (AiTypeEnum type : AiTypeEnum.values()) {
            String stub = McpHookServerUtil.getInitializeStub(type.getMcpOptions(), hidden);
            String named = namedTools(stub, hidden);
            if (!named.isEmpty()) {
                failures.append(type).append(" stub still names:").append(named).append(System.lineSeparator());
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());
    }

    /**
     * The global header still carries fixed policy sentences that name a tool (for example the edit-via-ApplyEdit
     * bullet); those are the sentence filter's job and it runs on every path that serves the header. Pinned here as the
     * combination the server really delivers: header built for the hidden set, then filtered.
     */
    @Test
    void theHeaderAsDeliveredToASessionWithNoToolsNamesNoHiddenTool() {
        Set<McpToolEnum> hidden = everyToolButGetInstructions();
        StringBuilder failures = new StringBuilder();
        for (AiTypeEnum type : AiTypeEnum.values()) {
            String delivered = HiddenToolTextFilter.omitSentencesReferencing(
                    McpHookServerUtil.getGlobalInstructionsHeader(type.getMcpOptions(), hidden), hidden);
            String named = namedTools(delivered, hidden);
            if (!named.isEmpty()) {
                failures.append(type).append(" header still names:").append(named).append(System.lineSeparator());
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());
    }

    @Test
    void theStubAsDeliveredAlsoSurvivesTheSentenceFilterWithNoHiddenToolNamed() {
        Set<McpToolEnum> hidden = everyToolButGetInstructions();
        StringBuilder failures = new StringBuilder();
        for (AiTypeEnum type : AiTypeEnum.values()) {
            String delivered = HiddenToolTextFilter.omitSentencesReferencing(
                    McpHookServerUtil.getInitializeStub(type.getMcpOptions(), hidden), hidden);
            String named = namedTools(delivered, hidden);
            if (!named.isEmpty()) {
                failures.append(type).append(" delivered stub still names:").append(named)
                        .append(System.lineSeparator());
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());
    }
}

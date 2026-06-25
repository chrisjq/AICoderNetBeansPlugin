package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.plugin;

import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpInstructionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

/**
 * Returns the full plugin usage guide on demand and marks the conversation as
 * having loaded instructions. The MCP server gates all other tools until this
 * has been called once per conversation, so the slim initialize stub can point
 * the AI here instead of shipping the whole guide on every connect.
 */
public class GetInstructionsTool extends AbstractActionTool {

    public GetInstructionsTool() {
        super(McpSectionEnum.PLUGIN,
                McpToolEnum.GET_INSTRUCTIONS.toolName(),
                "Returns the full NetBeans plugin usage guide (tool policies, the "
                + "diff-panel edit flow, refactoring and inter-AI messaging guidance). "
                + "You MUST call this once before using any other plugin tool — other "
                + "tools are rejected until you do.",
                null);
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        AiTypeEnum type = session.getType();
        Map<McpToolEnum, McpToolInterface> handlers = McpInstructionRegistry.getHandlers(type);
        String full = McpInstructionRegistry.getCachedInstructions(type);
        if (full == null) {
            full = McpInstructionRegistry.buildFullInstructions(type, handlers);
            McpInstructionRegistry.cacheInstructions(type, full);
        }
        session.getAiSession().setInstructionsLoaded(true);
        return full;
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.AbstractMcpBridge;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolAccess;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

/**
 * MCP bridge used by the OpenAI-compatible backend.
 */
public final class OpenAiCompatibleMcpBridge extends AbstractMcpBridge {

    private final AbstractAiSession session;

    public OpenAiCompatibleMcpBridge(AbstractAiSession session) {
        super(AiTypeEnum.OPENAI_COMPATIBLE);
        this.session = session;
    }

    @Override
    protected String executeTool(String toolName, JsonObject argsWithAuth) {
        McpToolEnum tool = McpToolEnum.of(toolName);
        if (tool == null) {
            return "Error: unknown tool: " + toolName;
        }
        if (session == null) {
            return "Error: OpenAI Compatible session not bound";
        }
        McpToolInterface handler = session.getMcpToolHandlers().get(tool);
        if (handler == null) {
            return "Error: unhandled tool: " + toolName;
        }
        if (!McpToolAccess.isToolEnabled(session.getSettings(), tool, handler)) {
            return "Error: " + McpToolAccess.unavailableMessage(session.getSettings(), tool, handler);
        }
        try {
            return McpToolInvoker.invoke(tool, handler, argsWithAuth, session);
        }
        catch (McpArgumentException ex) {
            return "Error: " + ex.getMessage();
        }
    }
}

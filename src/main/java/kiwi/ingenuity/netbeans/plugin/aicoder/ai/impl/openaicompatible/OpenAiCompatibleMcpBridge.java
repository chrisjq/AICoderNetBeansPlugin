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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolResult;

/**
 * MCP bridge used by the OpenAI-compatible backend. Tool results keep their images, which the turn loop sends to the
 * model when it can view them.
 */
public final class OpenAiCompatibleMcpBridge extends AbstractMcpBridge {

    private final AbstractAiSession session;

    public OpenAiCompatibleMcpBridge(AbstractAiSession session) {
        super(AiTypeEnum.OPENAI_COMPATIBLE);
        this.session = session;
    }

    @Override
    protected String executeTool(String toolName, JsonObject argsWithAuth) {
        return executeToolResult(toolName, argsWithAuth).text();
    }

    @Override
    protected McpToolResult executeToolResult(String toolName, JsonObject argsWithAuth) {
        McpToolEnum tool = McpToolEnum.of(toolName);
        if (tool == null) {
            return McpToolResult.text("Error: unknown tool: " + toolName);
        }
        if (session == null) {
            return McpToolResult.text("Error: OpenAI Compatible session not bound");
        }
        McpToolInterface handler = session.getMcpToolHandlers().get(tool);
        if (handler == null) {
            return McpToolResult.text("Error: unhandled tool: " + toolName);
        }
        if (!McpToolAccess.isToolEnabled(session.getSettings(), tool, handler)) {
            return McpToolResult.text("Error: " + McpToolAccess.unavailableMessage(session.getSettings(), tool, handler));
        }
        try {
            return McpToolInvoker.invokeResult(tool, handler, argsWithAuth, session);
        }
        catch (McpArgumentException ex) {
            return McpToolResult.text("Error: " + ex.getMessage());
        }
    }
}

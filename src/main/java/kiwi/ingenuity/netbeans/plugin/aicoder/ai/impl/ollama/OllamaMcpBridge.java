package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.AbstractMcpBridge;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

public class OllamaMcpBridge extends AbstractMcpBridge {

    private final AbstractAiSession session;

    public OllamaMcpBridge(AbstractAiSession session) {
        this.session = session;
    }

    @Override
    protected String executeTool(String toolName, JsonObject argsWithAuth) {
        McpToolEnum tool = McpToolEnum.of(toolName);
        if (tool == null) {
            return "Error: unknown tool: " + toolName;
        }
        if (session == null) {
            return "Error: Ollama session not bound";
        }
        McpToolInterface handler = session.getMcpToolHandlers().get(tool);
        if (handler == null) {
            return "Error: unhandled tool: " + toolName;
        }
        try {
            return McpToolInvoker.invoke(tool, handler, argsWithAuth, session);
        }
        catch (McpArgumentException ex) {
            return "Error: " + ex.getMessage();
        }
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolHandlerFactory;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.userinput.AskUserQuestionTool;

/**
 * Mirrors {@code ClaudeToolHandlerFactory} exactly. pi reaches these handlers indirectly, through the generated
 * extension's MCP bridge rather than a native MCP client, but the plugin-side handler map is identical for every
 * backend.
 */
public final class PiToolHandlerFactory {

    public static Map<McpToolEnum, McpToolInterface> build(Supplier<AiProcessEventListener> listenerSupplier, McpHookServer server) {
        Map<McpToolEnum, McpToolInterface> map = new LinkedHashMap<>();
        map.putAll(ToolHandlerFactory.getToolHandlers(server));
        map.put(McpToolEnum.ASK_USER_QUESTION, new AskUserQuestionTool(listenerSupplier));
        return Collections.unmodifiableMap(map);
    }

    private PiToolHandlerFactory() {
    }
}

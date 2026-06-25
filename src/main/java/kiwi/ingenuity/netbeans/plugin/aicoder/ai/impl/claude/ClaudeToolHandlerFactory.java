package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

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

public final class ClaudeToolHandlerFactory {

    public static Map<McpToolEnum, McpToolInterface> build(Supplier<AiProcessEventListener> listenerSupplier, McpHookServer server) {
        Map<McpToolEnum, McpToolInterface> map = new LinkedHashMap<>();
        map.putAll(ToolHandlerFactory.getToolHandlers(server));
        map.put(McpToolEnum.ASK_USER_QUESTION, new AskUserQuestionTool(listenerSupplier));
        return Collections.unmodifiableMap(map);
    }

    private ClaudeToolHandlerFactory() {
    }
}

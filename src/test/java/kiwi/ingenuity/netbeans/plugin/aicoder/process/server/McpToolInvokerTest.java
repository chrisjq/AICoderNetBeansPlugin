package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.plugin.GetPluginVersionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpToolInvokerTest {
    @Test
    void readOnlyToolReturnsHandlerResultWithoutLocks() throws Exception {
        McpToolEnum tool = McpToolEnum.GET_PLUGIN_VERSION;
        McpToolInterface handler = new GetPluginVersionTool();
        String result = McpToolInvoker.invoke(tool, handler, new JsonObject(), null);
        assertEquals(handler.handle(new ToolRequestArguments(new JsonObject()), null), result);
    }
}

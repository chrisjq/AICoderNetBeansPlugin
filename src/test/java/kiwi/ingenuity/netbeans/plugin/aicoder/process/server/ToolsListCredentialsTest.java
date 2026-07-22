package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptions;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help.GetProjectStructureTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolsListCredentialsTest {
    @Test
    void schemaCliMatchesLegacyInjectPath() {
        McpToolInterface tool = new GetProjectStructureTool();
        JsonObject viaOptions = tool.schema(McpInstructionOptions.cli());
        JsonObject viaLegacy = McpHookServerUtil.injectSessionParams(tool.schema());
        assertEquals(viaLegacy, viaOptions);
    }
}

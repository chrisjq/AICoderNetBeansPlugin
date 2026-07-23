package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help.GetProjectStructureTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolsListCredentialsTest {

    @Test
    void schemaCliMatchesLegacyInjectPath() {
        McpToolInterface tool = new GetProjectStructureTool();
        JsonObject viaOptions = tool.schema(AiTypeEnum.CLAUDE.getMcpOptions());
        JsonObject viaLegacy = McpHookServerUtil.injectSessionParams(tool.schema(Set.of()));
        assertEquals(viaLegacy, viaOptions);
    }
}

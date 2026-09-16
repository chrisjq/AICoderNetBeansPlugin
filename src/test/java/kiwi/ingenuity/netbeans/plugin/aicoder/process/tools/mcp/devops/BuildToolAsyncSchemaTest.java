package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.BuildAntProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.BuildGradleProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.BuildMavenProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.CleanAndBuildAntProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.CleanAndBuildGradleProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.CleanAndBuildMavenProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.DownloadMavenJavadocTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.DownloadMavenSourcesTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test.RunAntTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test.RunGradleTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test.RunMavenTestsTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class BuildToolAsyncSchemaTest {

    @Test
    void asyncIsAnOptionalBooleanOnEveryQueuedBuildTool() {
        List<McpToolInterface> tools = List.of(
                new BuildMavenProjectTool(), new CleanAndBuildMavenProjectTool(), new RunMavenTestsTool(),
                new BuildGradleProjectTool(), new CleanAndBuildGradleProjectTool(), new RunGradleTestsTool(),
                new BuildAntProjectTool(), new CleanAndBuildAntProjectTool(), new RunAntTestsTool());

        for (McpToolInterface tool : tools) {
            JsonObject schema = tool.schema(Set.of());
            String name = schema.get(ToolSchemaKeyEnum.NAME.key()).getAsString();
            JsonObject input = schema.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
            JsonObject async = input.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key())
                    .getAsJsonObject(McpToolPropertyEnum.ASYNC.key());

            assertNotNull(async, name + " must offer async");
            assertEquals("boolean", async.get(ToolSchemaKeyEnum.TYPE.key()).getAsString(), name);
            JsonArray required = input.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
            boolean asyncRequired = false;
            if (required != null) {
                for (JsonElement element : required) {
                    asyncRequired |= McpToolPropertyEnum.ASYNC.key().equals(element.getAsString());
                }
            }
            assertFalse(asyncRequired, name + " must not require async");
        }
    }

    @Test
    void theMavenDownloadToolsOfferAsyncBecauseTheyAreQueuedToo() {
        for (McpToolInterface tool : List.of(new DownloadMavenSourcesTool(), new DownloadMavenJavadocTool())) {
            JsonObject schema = tool.schema(Set.of());
            JsonObject properties = schema.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key())
                    .getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());

            assertTrue(properties.has(McpToolPropertyEnum.ASYNC.key()),
                       schema.get(ToolSchemaKeyEnum.NAME.key()).getAsString()
                       + " runs through the build queue and must offer async");
        }
    }
}

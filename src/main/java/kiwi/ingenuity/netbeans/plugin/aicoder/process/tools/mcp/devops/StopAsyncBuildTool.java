package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildQueue;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

public class StopAsyncBuildTool extends AbstractActionTool {

    public StopAsyncBuildTool() {
        super(McpSectionEnum.DEVOPS_BUILD, McpToolEnum.STOP_ASYNC_BUILD.toolName(),
              "Stops one of your queued or running asynchronous builds by id.",
              McpToolEnum.STOP_ASYNC_BUILD.toolName() + " -> cancel your own asynchronous build by buildId.");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.STOP_ASYNC_BUILD.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Stops only your own queued or running async build. Inline builds cannot be stopped; buildId comes from the queued reply or ListBuilds.");
        JsonObject input = new JsonObject();
        input.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject id = new JsonObject();
        id.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        id.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Required build ID shown by ListBuilds.");
        props.add(McpToolPropertyEnum.BUILD_ID.key(), id);
        input.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(McpToolPropertyEnum.BUILD_ID.key());
        input.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), input);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String id = args.str(McpToolPropertyEnum.BUILD_ID.key());
        return id == null || id.isBlank() ? "Error: buildId is required"
               : BuildQueue.getInstance().stop(id, session.getId());
    }
}

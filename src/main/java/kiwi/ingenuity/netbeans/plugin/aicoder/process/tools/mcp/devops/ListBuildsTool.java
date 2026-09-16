package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildQueue;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildReportFormatter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

public class ListBuildsTool extends AbstractActionTool {

    public ListBuildsTool() {
        super(McpSectionEnum.DEVOPS_BUILD, McpToolEnum.LIST_BUILDS.toolName(),
              "Lists queued, running, and recent builds. IDs are visible only for your own asynchronous builds.",
              McpToolEnum.LIST_BUILDS.toolName() + " -> inspect the shared build queue and recent results.");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.LIST_BUILDS.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "One build runs at a time, first come first served. Shows current builds in execution order and the last 5 finished builds (including cancelled); IDs appear only for your own async builds.");
        JsonObject input = new JsonObject();
        input.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        input.add(ToolSchemaKeyEnum.PROPERTIES.key(), new JsonObject());
        input.add(ToolSchemaKeyEnum.REQUIRED.key(), new JsonArray());
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), input);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildReportFormatter.listBuilds(BuildQueue.getInstance().snapshot(), session.getId());
    }
}

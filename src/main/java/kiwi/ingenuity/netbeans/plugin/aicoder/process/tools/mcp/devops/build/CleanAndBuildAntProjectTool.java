package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractBuildTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.BuildSubmitter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestAntProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestAntProvider.AntBuildOptions;

public class CleanAndBuildAntProjectTool extends AbstractBuildTool {

    public CleanAndBuildAntProjectTool() {
        super(McpSectionEnum.DEVOPS_BUILD,
              McpToolEnum.CLEAN_AND_BUILD_ANT_PROJECT.toolName(),
              "Cleans and builds the Ant project at " + CleanAndBuildAntProjectParamEnum.PROJECT_PATH.key() + " (default: ant clean jar; "
              + CleanAndBuildAntProjectParamEnum.TARGETS.key() + " and the other options below override this — "
              + CleanAndBuildAntProjectParamEnum.TARGETS.key() + " REPLACES the default entirely, so include \"clean\" yourself if you still want it with custom targets). "
              + "Returns a summary; the full log is written to a file.",
              McpToolEnum.CLEAN_AND_BUILD_ANT_PROJECT.toolName() + " -> INSTEAD OF Bash ant clean jar - requires " + CleanAndBuildAntProjectParamEnum.PROJECT_PATH.key() + "; cleans and builds Ant project (default targets: clean jar, overridable) and returns a result summary (complete log written to a file)" + BuildSubmitter.QUEUE_INSTRUCTION,
              McpToolEnum.CLEAN_AND_BUILD_ANT_PROJECT.toolName() + " - requires " + CleanAndBuildAntProjectParamEnum.PROJECT_PATH.key() + "; cleans and builds Ant project (default targets: clean jar, overridable) and returns a result summary (complete log written to a file)" + BuildSubmitter.QUEUE_INSTRUCTION);
    }

    @Override
    protected void addOptionProperties(JsonObject props, JsonArray required) {
        AntToolSchema.addProperties(props, "[\"clean\", \"jar\"]");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        List<String> targets = AntToolSchema.targetsOrDefault(args, CleanAndBuildAntProjectParamEnum.TARGETS.key(),
                                                              List.of("clean", "jar"));
        AntBuildOptions opts = AntToolSchema.optionsFrom(args, targets);
        String projectPath = args.str(CleanAndBuildAntProjectParamEnum.PROJECT_PATH.key());
        return BuildSubmitter.submit(McpToolEnum.CLEAN_AND_BUILD_ANT_PROJECT.toolName(), args, projectPath,
                                     BuildAndTestAntProvider.prepareCleanAndBuildProject(session.getId(), projectPath, opts),
                                     session);
    }
}

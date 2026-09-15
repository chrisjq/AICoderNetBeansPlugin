package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractBuildTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestAntProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestAntProvider.AntBuildOptions;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class BuildAntProjectTool extends AbstractBuildTool {

    public BuildAntProjectTool() {
        super(McpSectionEnum.DEVOPS_BUILD,
              McpToolEnum.BUILD_ANT_PROJECT.toolName(),
              "Builds the Ant project at " + BuildAntProjectParamEnum.PROJECT_PATH.key() + " (default: ant jar; "
              + BuildAntProjectParamEnum.TARGETS.key() + " and the other options below override this). "
              + "Returns a summary; the full log is written to a file.",
              McpToolEnum.BUILD_ANT_PROJECT.toolName() + " -> INSTEAD OF Bash ant jar - requires " + BuildAntProjectParamEnum.PROJECT_PATH.key() + "; builds Ant project (default target: jar, overridable) and returns a result summary (complete log written to a file)",
              McpToolEnum.BUILD_ANT_PROJECT.toolName() + " - requires " + BuildAntProjectParamEnum.PROJECT_PATH.key() + "; builds Ant project (default target: jar, overridable) and returns a result summary (complete log written to a file)");
    }

    @Override
    protected void addOptionProperties(JsonObject props, JsonArray required) {
        AntToolSchema.addProperties(props, "[\"jar\"]");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        List<String> targets = AntToolSchema.targetsOrDefault(args, BuildAntProjectParamEnum.TARGETS.key(), List.of("jar"));
        AntBuildOptions opts = AntToolSchema.optionsFrom(args, targets);
        return BuildAndTestAntProvider.buildProject(session.getId(),
                                                    args.str(BuildAntProjectParamEnum.PROJECT_PATH.key()), opts);
    }
}

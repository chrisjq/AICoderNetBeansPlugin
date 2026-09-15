package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.AntToolSchema;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestAntProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestAntProvider.AntBuildOptions;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class RunAntTestsTool extends AbstractTestsTool {

    public RunAntTestsTool() {
        super(McpSectionEnum.DEVOPS_TEST,
              McpToolEnum.RUN_ANT_TESTS.toolName(),
              "Runs the Ant project's tests at " + RunAntTestsParamEnum.PROJECT_PATH.key() + " (default: ant test; optionally filter by test class; "
              + RunAntTestsParamEnum.TARGETS.key() + " and the other options below override the default target). "
              + "Ant projects only - do not use for Maven or Gradle projects. "
              + "Returns a summary; the full log is written to a file.",
              McpToolEnum.RUN_ANT_TESTS.toolName() + " -> INSTEAD OF Bash ant test - requires " + RunAntTestsParamEnum.PROJECT_PATH.key() + "; runs Ant tests (default target: test, overridable) with optional class filter",
              McpToolEnum.RUN_ANT_TESTS.toolName() + " - requires " + RunAntTestsParamEnum.PROJECT_PATH.key() + "; runs Ant tests (default target: test, overridable) with optional class filter");
    }

    @Override
    protected void addOptionProperties(JsonObject props, JsonArray required) {
        AntToolSchema.addProperties(props, "[\"test\"]");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        List<String> targets = AntToolSchema.targetsOrDefault(args, RunAntTestsParamEnum.TARGETS.key(), List.of("test"));
        AntBuildOptions opts = AntToolSchema.optionsFrom(args, targets);
        return BuildAndTestAntProvider.runTests(session.getId(),
                                                args.str(RunAntTestsParamEnum.TEST_CLASS.key()), args.str(RunAntTestsParamEnum.PROJECT_PATH.key()), opts);
    }
}

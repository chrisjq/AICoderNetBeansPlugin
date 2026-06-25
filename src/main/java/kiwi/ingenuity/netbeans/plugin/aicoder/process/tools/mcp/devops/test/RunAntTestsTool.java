package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestAntProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class RunAntTestsTool extends AbstractTestsTool {

    public RunAntTestsTool() {
        super(McpSectionEnum.DEVOPS_TEST,
                McpToolEnum.RUN_ANT_TESTS.toolName(),
                "Runs the open Ant project's test suite (ant test). "
                + "Ant projects only - do not use for Maven or Gradle projects. "
                + "Optionally restrict to a single test class via -Dtest.includes.",
                "RunAntTests -> INSTEAD OF Bash ant test - runs Ant tests with optional class filter");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestAntProvider.runTests(args.str(RunAntTestsParamEnum.TEST_CLASS.key()), args.str(RunAntTestsParamEnum.PROJECT_PATH.key()));
    }
}

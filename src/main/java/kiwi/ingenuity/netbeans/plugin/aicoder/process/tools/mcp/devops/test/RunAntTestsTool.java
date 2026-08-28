package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestAntProvider;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class RunAntTestsTool extends AbstractTestsTool {

    public RunAntTestsTool() {
        super(McpSectionEnum.DEVOPS_TEST,
                McpToolEnum.RUN_ANT_TESTS.toolName(),
                "Runs the Ant project's tests at " + RunAntTestsParamEnum.PROJECT_PATH.key() + " (ant test); optionally filter by test class. "
                + "Ant projects only - do not use for Maven or Gradle projects. "
                + "Returns a summary; the full log is written to a file.",
                McpToolEnum.RUN_ANT_TESTS.toolName() + " -> INSTEAD OF Bash ant test - requires " + RunAntTestsParamEnum.PROJECT_PATH.key() + "; runs Ant tests with optional class filter",
                McpToolEnum.RUN_ANT_TESTS.toolName() + " - requires " + RunAntTestsParamEnum.PROJECT_PATH.key() + "; runs Ant tests with optional class filter");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestAntProvider.runTests(session.getId(), args.str(RunAntTestsParamEnum.TEST_CLASS.key()), args.str(RunAntTestsParamEnum.PROJECT_PATH.key()));
    }
}

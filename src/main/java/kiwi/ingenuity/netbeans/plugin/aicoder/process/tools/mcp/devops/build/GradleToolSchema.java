package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestGradleProvider.GradleBuildOptions;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildOptionValidator;

/**
 * Shared schema-building and argument-extraction for the three Gradle devops tools (BuildGradleProjectTool,
 * CleanAndBuildGradleProjectTool, RunGradleTestsTool) — see {@link MavenToolSchema}'s javadoc for why this is shared
 * rather than duplicated three times.
 */
public final class GradleToolSchema {

    public static void addProperties(JsonObject props, String tasksDefaultDescription, boolean skipTestsDefault) {
        MavenToolSchema.addStringArray(props, McpToolPropertyEnum.TASKS.key(),
                                       "Gradle tasks to run (e.g. \"build\", \"assemble\", \":module:build\"), each array entry one task. "
                                       + "Replaces the default entirely when given — it is not merged with it. Default: " + tasksDefaultDescription + ".");
        MavenToolSchema.addBoolean(props, McpToolPropertyEnum.SKIP_TESTS.key(),
                                   "Gradle -x test: exclude the test task. Default: " + skipTestsDefault + ".");
        MavenToolSchema.addBoolean(props, McpToolPropertyEnum.OFFLINE.key(),
                                   "Gradle --offline: resolve only from caches, without contacting remote repositories. Default: false.");
        MavenToolSchema.addBoolean(props, McpToolPropertyEnum.REFRESH_DEPENDENCIES.key(),
                                   "Gradle --refresh-dependencies: bypass the dependency cache and re-resolve everything. Default: false.");
        MavenToolSchema.addObject(props, McpToolPropertyEnum.PROPERTIES.key(),
                                  "Gradle -Pk=v project properties, as a key/value map (never a raw string).");
        MavenToolSchema.addObject(props, McpToolPropertyEnum.SYSTEM_PROPERTIES.key(),
                                  "Gradle -Dk=v JVM system properties, as a key/value map — distinct from " + McpToolPropertyEnum.PROPERTIES.key() + " above.");
        MavenToolSchema.addBoolean(props, McpToolPropertyEnum.PARALLEL.key(),
                                   "Gradle --parallel: run independent tasks concurrently. Default: false.");
        MavenToolSchema.addBoolean(props, McpToolPropertyEnum.CONTINUE_ON_FAILURE.key(),
                                   "Gradle --continue: keep running other tasks after one fails, instead of stopping immediately. Default: false.");
    }

    public static GradleBuildOptions optionsFrom(ToolRequestArguments args, List<String> tasks, boolean skipTests) {
        return new GradleBuildOptions(
                tasks,
                skipTests,
                args.bool(McpToolPropertyEnum.OFFLINE.key()),
                args.bool(McpToolPropertyEnum.REFRESH_DEPENDENCIES.key()),
                args.object(McpToolPropertyEnum.PROPERTIES.key()),
                args.object(McpToolPropertyEnum.SYSTEM_PROPERTIES.key()),
                args.bool(McpToolPropertyEnum.PARALLEL.key()),
                args.bool(McpToolPropertyEnum.CONTINUE_ON_FAILURE.key()));
    }

    public static List<String> tasksOrDefault(ToolRequestArguments args, String tasksKey, List<String> defaultTasks) {
        return args.has(tasksKey) ? BuildOptionValidator.toStringList(args.array(tasksKey)) : defaultTasks;
    }

    private GradleToolSchema() {
    }
}

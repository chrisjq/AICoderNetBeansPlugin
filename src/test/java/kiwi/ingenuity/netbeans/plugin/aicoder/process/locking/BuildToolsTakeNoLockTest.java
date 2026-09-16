package kiwi.ingenuity.netbeans.plugin.aicoder.process.locking;

import java.util.LinkedHashMap;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.build.BuildProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.build.CleanAndBuildProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.build.CleanProjectTool;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

/**
 * Every build tool must resolve to NO lock: the build queue is the one thing that serialises builds, and a tool that
 * also took {@code BUILD_LOCK} would hold it across its wait in the queue and its whole run.
 * <p>
 * This exists because that went wrong unnoticed. When the IDE build actions were moved into the queue their
 * {@code @RequiresLock} annotations were removed, but {@link ToolLockRegistry}'s fallback map still mapped them to
 * {@code BUILD_LOCK} — and {@link ToolLockRegistry#getLockType} falls through to that map when there is no annotation.
 * Nothing asserted the mappings, so the tools kept taking the lock and the suite stayed green. The assertion is made
 * through the registry's real entry point, so an annotation and a fallback entry are both covered.
 */
class BuildToolsTakeNoLockTest {

    @Test
    void noBuildToolResolvesToALockBecauseTheQueueSerialisesThemInstead() {
        everyBuildTool().forEach((tool, handler)
                -> assertNull(ToolLockRegistry.getLockType(tool, handler),
                              tool.toolName() + " must take no lock — the build queue serialises it. A lock here is"
                              + " held across its queue wait and its entire run."));
    }

    /**
     * All fourteen: the nine Maven/Gradle/Ant build, clean-and-build and test tools, the two Maven download tools, and
     * the three IDE build actions.
     */
    private static Map<McpToolEnum, McpToolInterface> everyBuildTool() {
        Map<McpToolEnum, McpToolInterface> tools = new LinkedHashMap<>();
        tools.put(McpToolEnum.BUILD_MAVEN_PROJECT, new BuildMavenProjectTool());
        tools.put(McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT, new CleanAndBuildMavenProjectTool());
        tools.put(McpToolEnum.RUN_MAVEN_TESTS, new RunMavenTestsTool());
        tools.put(McpToolEnum.BUILD_GRADLE_PROJECT, new BuildGradleProjectTool());
        tools.put(McpToolEnum.CLEAN_AND_BUILD_GRADLE_PROJECT, new CleanAndBuildGradleProjectTool());
        tools.put(McpToolEnum.RUN_GRADLE_TESTS, new RunGradleTestsTool());
        tools.put(McpToolEnum.BUILD_ANT_PROJECT, new BuildAntProjectTool());
        tools.put(McpToolEnum.CLEAN_AND_BUILD_ANT_PROJECT, new CleanAndBuildAntProjectTool());
        tools.put(McpToolEnum.RUN_ANT_TESTS, new RunAntTestsTool());
        tools.put(McpToolEnum.DOWNLOAD_MAVEN_SOURCES, new DownloadMavenSourcesTool());
        tools.put(McpToolEnum.DOWNLOAD_MAVEN_JAVADOC, new DownloadMavenJavadocTool());
        tools.put(McpToolEnum.BUILD_PROJECT, new BuildProjectTool());
        tools.put(McpToolEnum.CLEAN_PROJECT, new CleanProjectTool());
        tools.put(McpToolEnum.CLEAN_AND_BUILD_PROJECT, new CleanAndBuildProjectTool());
        return tools;
    }
}

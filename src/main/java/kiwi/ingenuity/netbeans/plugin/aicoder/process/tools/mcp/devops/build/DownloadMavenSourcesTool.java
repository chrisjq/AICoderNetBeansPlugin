package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractBuildTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestMavenProvider;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class DownloadMavenSourcesTool extends AbstractBuildTool {

    public DownloadMavenSourcesTool() {
        super(McpSectionEnum.DEVOPS_BUILD,
                McpToolEnum.DOWNLOAD_MAVEN_SOURCES.toolName(),
                "Downloads source JARs for the Maven project at " + DownloadMavenSourcesParamEnum.PROJECT_PATH.key() + " via 'mvn dependency:sources'. "
                + "Maven projects only - do not use for Ant or Gradle projects. "
                + "Returns a summary; the full log is written to a file.",
                McpToolEnum.DOWNLOAD_MAVEN_SOURCES.toolName() + " -> requires " + DownloadMavenSourcesParamEnum.PROJECT_PATH.key() + "; downloads source JARs for Maven dependencies to enable source browsing of library classes");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestMavenProvider.downloadSources(session.getId(), args.str(DownloadMavenSourcesParamEnum.PROJECT_PATH.key()));
    }
}

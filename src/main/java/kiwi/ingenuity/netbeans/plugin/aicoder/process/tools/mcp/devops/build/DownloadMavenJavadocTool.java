package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractBuildTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.BuildSubmitter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestMavenProvider;

public class DownloadMavenJavadocTool extends AbstractBuildTool {

    public DownloadMavenJavadocTool() {
        super(McpSectionEnum.DEVOPS_BUILD,
              McpToolEnum.DOWNLOAD_MAVEN_JAVADOC.toolName(),
              "Downloads Javadoc JARs for the Maven project at " + DownloadMavenJavadocParamEnum.PROJECT_PATH.key() + " via 'mvn dependency:resolve -Dclassifier=javadoc'. "
              + "Maven projects only - do not use for Ant or Gradle projects. "
              + "Returns a summary; the full log is written to a file.",
              McpToolEnum.DOWNLOAD_MAVEN_JAVADOC.toolName() + " -> requires " + DownloadMavenJavadocParamEnum.PROJECT_PATH.key() + "; run before " + McpToolEnum.GET_JAVADOC.toolName() + " to download Javadoc JARs for Maven dependencies" + BuildSubmitter.QUEUE_INSTRUCTION);
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String projectPath = args.str(DownloadMavenJavadocParamEnum.PROJECT_PATH.key());
        return BuildSubmitter.submit(McpToolEnum.DOWNLOAD_MAVEN_JAVADOC.toolName(), args, projectPath,
                                     BuildAndTestMavenProvider.prepareDownloadJavadoc(session.getId(), projectPath),
                                     session);
    }
}

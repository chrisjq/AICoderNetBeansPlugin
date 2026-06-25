package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractBuildTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestMavenProvider;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class DownloadMavenJavadocTool extends AbstractBuildTool {

    public DownloadMavenJavadocTool() {
        super(McpSectionEnum.DEVOPS_BUILD,
                McpToolEnum.DOWNLOAD_MAVEN_JAVADOC.toolName(),
                "Downloads Javadoc JARs for all dependencies via 'mvn dependency:resolve -Dclassifier=javadoc'. "
                + "Maven projects only - do not use for Ant or Gradle projects. "
                + "Run before GetJavadoc if doc comments are missing for library classes.",
                "DownloadMavenJavadoc -> run before GetJavadoc to download Javadoc JARs for Maven dependencies");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestMavenProvider.downloadJavadoc(args.str(DownloadMavenJavadocParamEnum.PROJECT_PATH.key()));
    }
}

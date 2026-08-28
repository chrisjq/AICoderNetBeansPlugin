package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.spi.project.ActionProvider;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;

public class ProjectActionProvider {

    public static String cleanProject(String sessionId, String projectPath) {
        return invokeAction(sessionId, projectPath, ActionProvider.COMMAND_CLEAN, "Clean");
    }

    public static String buildProject(String sessionId, String projectPath) {
        return invokeAction(sessionId, projectPath, ActionProvider.COMMAND_BUILD, "Build");
    }

    public static String cleanAndBuildProject(String sessionId, String projectPath) {
        return invokeAction(sessionId, projectPath, ActionProvider.COMMAND_REBUILD, "Clean and build");
    }

    private static String invokeAction(String sessionId, String projectPath, String command, String label) {
        if (projectPath == null || projectPath.isBlank()) {
            return GitCommonParamEnum.PROJECT_PATH.key() + " is required";
        }
        File requested = FileUtil.normalizeFile(new File(projectPath));
        if (!requested.isDirectory()) {
            return "Project path is not a directory: " + projectPath;
        }
        if (McpServerRegistry.getServer() == null
                || !McpServerRegistry.getServer().isFileAllowed(sessionId, requested.getPath())) {
            return "Access denied: " + projectPath;
        }
        Project project = null;
        for (Project candidate : OpenProjects.getDefault().getOpenProjects()) {
            File root = FileUtil.toFile(candidate.getProjectDirectory());
            if (root != null && FileUtil.normalizeFile(root).equals(requested)) {
                project = candidate;
                break;
            }
        }
        if (project == null) {
            return "No open project matches " + GitCommonParamEnum.PROJECT_PATH.key() + ": " + projectPath;
        }
        ActionProvider ap = project.getLookup().lookup(ActionProvider.class);
        if (ap == null) {
            return "Project does not support ActionProvider";
        }
        boolean supported = false;
        for (String cmd : ap.getSupportedActions()) {
            if (cmd.equals(command)) {
                supported = true;
                break;
            }
        }
        if (!supported) {
            return "Project does not support action: " + command;
        }
        ap.invokeAction(command, Lookup.EMPTY);
        return label + " triggered — check the Output window for results";
    }

    private ProjectActionProvider() {
    }
}

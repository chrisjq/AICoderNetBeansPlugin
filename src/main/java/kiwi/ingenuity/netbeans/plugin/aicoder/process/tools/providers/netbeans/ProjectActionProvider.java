package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.util.List;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.spi.project.ActionProvider;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;

/**
 * Runs the user's own IDE build actions. These go through the build queue like every other build, so an IDE action and
 * a Maven/Gradle/Ant build can never run at the same time.
 * <p>
 * They differ from the build tools in one way that the queue has to know about: NetBeans hands the caller of
 * {@link ActionProvider#invokeAction} no handle to cancel — the Output window's stop button belongs to the project's
 * own action code — so a started IDE action cannot be stopped from here. Only a queued one can.
 */
public class ProjectActionProvider {

    public static String cleanProject(String sessionId, String projectPath) {
        return cleanProjectResult(sessionId, projectPath).message();
    }

    public static ProjectActionResult cleanProjectResult(String sessionId, String projectPath) {
        return invokeAction(sessionId, projectPath, ActionProvider.COMMAND_CLEAN, "Clean");
    }

    public static String buildProject(String sessionId, String projectPath) {
        return buildProjectResult(sessionId, projectPath).message();
    }

    public static ProjectActionResult buildProjectResult(String sessionId, String projectPath) {
        return invokeAction(sessionId, projectPath, ActionProvider.COMMAND_BUILD, "Build");
    }

    public static String cleanAndBuildProject(String sessionId, String projectPath) {
        return cleanAndBuildProjectResult(sessionId, projectPath).message();
    }

    public static ProjectActionResult cleanAndBuildProjectResult(String sessionId, String projectPath) {
        return invokeAction(sessionId, projectPath, ActionProvider.COMMAND_REBUILD, "Clean and build");
    }

    /**
     * Validates an IDE action BEFORE it is queued, so a bad request fails at once rather than when its turn comes
     * (decision 10). The returned build carries no command: an IDE action has no command line, and the queue runs it
     * through {@link #buildProjectResult} and friends rather than through the process runner.
     */
    public static PreparedBuild prepareAction(String sessionId, String projectPath, String command) {
        Resolved resolved = resolve(sessionId, projectPath, command);
        return resolved.error() != null ? PreparedBuild.error(resolved.error())
               : new PreparedBuild(null, sessionId, resolved.root(), List.of(), null);
    }

    private static ProjectActionResult invokeAction(String sessionId, String projectPath, String command,
                                                    String label) {
        Resolved resolved = resolve(sessionId, projectPath, command);
        if (resolved.error() != null) {
            return ProjectActionResult.error(resolved.error());
        }
        if (SwingUtilities.isEventDispatchThread()) {
            // Waiting here would block the EDT on the very action it is waiting for.
            resolved.provider().invokeAction(command, Lookup.EMPTY);
            return ProjectActionResult.notAwaited(IdeActionWaiter.notWaitedMessage(label));
        }
        BlockingActionProgress progress = new BlockingActionProgress();
        resolved.provider().invokeAction(command, Lookups.fixed(progress));
        return IdeActionWaiter.await(progress, label, TimeoutEnum.IDE_ACTION_START_GRACE_MILLIS.millis(),
                                     LockTypeEnum.BUILD_LOCK.getLifetimeMillis());
    }

    /**
     * Resolves and checks everything an IDE action needs: a real, in-scope directory, an open project matching it, and
     * an ActionProvider that supports the command. Shared so the pre-queue check and the run itself apply identical
     * rules — the run repeats it because a project can be closed between queueing and starting.
     */
    private static Resolved resolve(String sessionId, String projectPath, String command) {
        if (projectPath == null || projectPath.isBlank()) {
            return Resolved.error(GitCommonParamEnum.PROJECT_PATH.key() + " is required");
        }
        File requested = FileUtils.toRealPath(new File(projectPath));
        if (!requested.isDirectory()) {
            return Resolved.error("Project path is not a directory: " + projectPath);
        }
        if (McpServerRegistry.getServer() == null
                || !McpServerRegistry.getServer().isFileAllowed(sessionId, requested.getPath())) {
            return Resolved.error("Access denied: " + projectPath);
        }
        Project project = null;
        for (Project candidate : OpenProjects.getDefault().getOpenProjects()) {
            File root = FileUtil.toFile(candidate.getProjectDirectory());
            if (root != null && FileUtils.toRealPath(root).equals(requested)) {
                project = candidate;
                break;
            }
        }
        if (project == null) {
            return Resolved.error("No open project matches " + GitCommonParamEnum.PROJECT_PATH.key() + ": " + projectPath);
        }
        ActionProvider ap = project.getLookup().lookup(ActionProvider.class);
        if (ap == null) {
            return Resolved.error("Project does not support ActionProvider");
        }
        for (String cmd : ap.getSupportedActions()) {
            if (cmd.equals(command)) {
                return new Resolved(requested, ap, null);
            }
        }
        return Resolved.error("Project does not support action: " + command);
    }

    private ProjectActionProvider() {
    }

    private record Resolved(File root, ActionProvider provider, String error) {

        static Resolved error(String message) {
            return new Resolved(null, null, message);
        }
    }
}

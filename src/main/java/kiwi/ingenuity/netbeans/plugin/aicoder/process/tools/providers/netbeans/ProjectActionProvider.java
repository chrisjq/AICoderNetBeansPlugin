package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ProjectPathParamEnum;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.spi.project.ActionProvider;
import org.netbeans.spi.project.ProjectConfigurationProvider;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;

/**
 * Runs the user's own IDE build/run actions. The build actions (Build, Clean, Clean and Build) go through the
 * build queue like every other build, so one of them and a Maven/Gradle/Ant build can never run at the same
 * time; {@link #runProject} does not — a run or debug session may never finish, so there is nothing to queue
 * it behind.
 * <p>
 * The queued actions differ from the build tools in one way the queue has to know about: NetBeans hands the
 * caller of {@link ActionProvider#invokeAction} no handle to cancel — the Output window's stop button belongs
 * to the project's own action code — so a started IDE action cannot be stopped from here. Only a queued one
 * can.
 */
public class ProjectActionProvider {

    private static final Logger LOG = Logger.getLogger(ProjectActionProvider.class.getName());

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
     * Runs or debugs the project via the user's own IDE Run Project / Debug Project action — the same thing
     * the user gets from the project's Run/Debug menu, including whatever that action itself does: it may
     * show the user a dialog (e.g. to choose a main class) if the project needs one, same as the menu would.
     * Unlike every other action here, this does NOT share {@link #invokeAction}'s wait-for-completion logic
     * and is never queued through {@code BuildQueue}: a run may launch a GUI app or a long-lived server that
     * never finishes, so there is nothing to wait for and no build slot to hold while it runs.
     * <p>
     * {@code ActionProvider.invokeAction}'s own contract says it "will be invoked in the event thread", the
     * way the menu calls it — calling it inline on the caller's thread would hold this MCP call for the
     * entire life of whatever it launches, so it runs via {@code invokeLater} and this method returns the
     * moment that is scheduled, not when it runs. {@code isActionEnabled} is cheap enough to call
     * synchronously (on the EDT if not already on it), so the "not available" case can still be reported in
     * the same call. NetBeans gives the caller of {@code invokeAction} no handle to stop either action — the
     * user stops it from the Output window (Run) or the debugger's Finish/Kill (Debug).
     */
    public static String runProject(String sessionId, String projectPath, boolean debug) {
        String command = debug ? ActionProvider.COMMAND_DEBUG : ActionProvider.COMMAND_RUN;
        String label = debug ? "Debug Project" : "Run Project";
        Resolved resolved = resolve(sessionId, projectPath, command);
        if (resolved.error() != null) {
            return resolved.error();
        }
        ActionProvider provider = resolved.provider();
        Lookup context = buildActionContext(resolved.project().getLookup());
        if (!isActionEnabledOnEdt(provider, command, context)) {
            return label + " is not currently enabled for " + resolved.root().getName()
                   + " (it is disabled in the IDE's Run menu for this project).";
        }
        SwingUtilities.invokeLater(() -> {
            try {
                provider.invokeAction(command, context);
            }
            catch (RuntimeException e) {
                // Cannot reach the AI — this runs long after runProject() itself has already returned.
                LOG.log(Level.INFO, command + " failed for " + resolved.root(), e);
            }
        });
        return "Requested " + label + " for " + resolved.root().getName() + "; the IDE takes it from here (it may "
               + "ask the user to choose a main class). Output is in the IDE's Output window. The AI cannot stop "
               + "it; the user can "
               + (debug ? "stop it with the debugger's Finish/Kill." : "stop it from the Output window.");
    }

    /**
     * Runs {@code isActionEnabled} on the event thread, the way NetBeans' own menu infrastructure would check
     * it before showing the action as enabled — call directly when already on the EDT (the common case when
     * this came from UI code), otherwise {@code invokeAndWait} it, since the check itself is cheap. An
     * interruption or a failure inside the check is logged and treated as "not enabled": that is the safer of
     * the two guesses, since {@link #runProject} would otherwise go on to call {@code invokeAction} on a
     * provider that has just demonstrated it cannot answer a simple question.
     */
    private static boolean isActionEnabledOnEdt(ActionProvider provider, String command, Lookup context) {
        if (SwingUtilities.isEventDispatchThread()) {
            return provider.isActionEnabled(command, context);
        }
        boolean[] enabled = {false};
        try {
            SwingUtilities.invokeAndWait(() -> enabled[0] = provider.isActionEnabled(command, context));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.INFO, "Interrupted while checking isActionEnabled for " + command, e);
            return false;
        }
        catch (InvocationTargetException e) {
            LOG.log(Level.INFO, "isActionEnabled failed for " + command, e.getCause() != null ? e.getCause() : e);
            return false;
        }
        return enabled[0];
    }

    /**
     * Builds the context {@code isActionEnabled}/{@code invokeAction} are called with, the way the Run/Debug
     * menu does: {@code ActionProvider}'s own contract says an implementation sensitive to
     * {@code ProjectConfiguration} must check the context lookup for one the caller supplied, so omitting it
     * (an empty context) would run the project's currently INACTIVE configuration's behaviour instead of the
     * one selected in the IDE for anything that looks at it. {@code projectLookup} is the resolved project's
     * own {@code getLookup()}; a project with no {@link ProjectConfigurationProvider} at all (most Maven/Ant
     * projects) gets an empty context, exactly as before.
     */
    static Lookup buildActionContext(Lookup projectLookup) {
        ProjectConfigurationProvider<?> configProvider = projectLookup.lookup(ProjectConfigurationProvider.class);
        if (configProvider == null) {
            return Lookup.EMPTY;
        }
        Object active = configProvider.getActiveConfiguration();
        return active != null ? Lookups.fixed(active) : Lookup.EMPTY;
    }

    /**
     * Validates an IDE action BEFORE it is queued, so a bad request fails at once rather than when its turn
     * comes (decision 10). The returned build carries no command: an IDE action has no command line, and the
     * queue runs it through {@link #buildProjectResult} and friends rather than through the process runner.
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
     * Resolves and checks everything an IDE action needs: a real, in-scope directory, an open project
     * matching it, and an ActionProvider that supports the command. Shared so the pre-queue check and the run
     * itself apply identical rules — the run repeats it because a project can be closed between queueing and
     * starting.
     */
    private static Resolved resolve(String sessionId, String projectPath, String command) {
        if (projectPath == null || projectPath.isBlank()) {
            return Resolved.error(ProjectPathParamEnum.PROJECT_PATH.key() + " is required");
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
            return Resolved.error("No open project matches " + ProjectPathParamEnum.PROJECT_PATH.key() + ": " + projectPath);
        }
        ActionProvider ap = project.getLookup().lookup(ActionProvider.class);
        if (ap == null) {
            return Resolved.error("Project does not support ActionProvider");
        }
        for (String cmd : ap.getSupportedActions()) {
            if (cmd.equals(command)) {
                return new Resolved(requested, project, ap, null);
            }
        }
        return Resolved.error("Project does not support action: " + command);
    }

    private ProjectActionProvider() {
    }

    private record Resolved(File root, Project project, ActionProvider provider, String error) {

        static Resolved error(String message) {
            return new Resolved(null, null, null, message);
        }
    }
}

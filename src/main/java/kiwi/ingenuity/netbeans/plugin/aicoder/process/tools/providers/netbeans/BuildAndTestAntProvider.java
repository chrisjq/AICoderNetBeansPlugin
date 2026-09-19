package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildControl;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;
import org.openide.modules.InstalledFileLocator;

public class BuildAndTestAntProvider {

    private static final Logger LOG = Logger.getLogger(BuildAndTestAntProvider.class.getName());
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    /**
     * Options shared by BuildAntProject, CleanAndBuildAntProject and RunAntTests. {@code targets} carries the calling
     * tool's own default ({@code jar}, {@code clean jar}, or {@code test}) when the caller omitted it — see
     * {@link BuildAndTestMavenProvider.MavenBuildOptions} for why defaulting lives in the tool, not here.
     */
    public record AntBuildOptions(List<String> targets, JsonObject properties, boolean keepGoing) {

    }

    public static String buildProject(String sessionId, String projectPath, AntBuildOptions opts) {
        return BuildProcessRunner.run(prepareBuildProject(sessionId, projectPath, opts),
                                      new BuildControl(TimeoutEnum.BUILD_PROCESS_MILLIS.millis())).result();
    }

    public static String cleanAndBuildProject(String sessionId, String projectPath, AntBuildOptions opts) {
        return BuildProcessRunner.run(prepareCleanAndBuildProject(sessionId, projectPath, opts),
                                      new kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildControl(TimeoutEnum.BUILD_PROCESS_MILLIS.millis())).result();
    }

    public static String runTests(String sessionId, String testClass, String projectPath, AntBuildOptions opts) {
        return BuildProcessRunner.run(prepareRunTests(sessionId, testClass, projectPath, opts),
                                      new kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildControl(TimeoutEnum.BUILD_PROCESS_MILLIS.millis())).result();
    }

    public static PreparedBuild prepareBuildProject(String sessionId, String projectPath, AntBuildOptions opts) {
        return prepareBuild(sessionId, projectPath, opts, null);
    }

    public static PreparedBuild prepareCleanAndBuildProject(String sessionId, String projectPath, AntBuildOptions opts) {
        return prepareBuild(sessionId, projectPath, opts, null);
    }

    public static PreparedBuild prepareRunTests(String sessionId, String testClass, String projectPath, AntBuildOptions opts) {
        return prepareBuild(sessionId, projectPath, opts, testClass);
    }

    private static PreparedBuild prepareBuild(String sessionId, String projectPath, AntBuildOptions opts, String testClass) {
        String error = validate(opts);
        if (error != null) {
            return PreparedBuild.error("Error: " + error);
        }
        if (testClass != null && !testClass.isBlank()) {
            error = BuildOptionValidator.validateTestSelector(McpToolPropertyEnum.TEST_CLASS.key(), testClass);
            if (error != null) {
                return PreparedBuild.error("Error: " + error);
            }
        }
        RootResult resolved = resolveRoot(sessionId, projectPath);
        if (resolved.error() != null) {
            return PreparedBuild.error(resolved.error());
        }
        List<String> command = new ArrayList<>();
        command.add(resolveAnt());
        command.addAll(List.of(argsFor(opts, testClass)));
        return new PreparedBuild(null, sessionId, resolved.root(), command, BuildOutputFormatter.Backend.ANT);
    }

    private static String validate(AntBuildOptions opts) {
        if (opts.targets() == null || opts.targets().isEmpty()) {
            return McpToolPropertyEnum.TARGETS.key() + " must not be empty";
        }
        String error = BuildOptionValidator.validateTokens(McpToolPropertyEnum.TARGETS.key(), opts.targets());
        if (error != null) {
            return error;
        }
        String[] errorOut = new String[1];
        BuildOptionValidator.validateProperties(McpToolPropertyEnum.PROPERTIES.key(), opts.properties(), errorOut);
        return errorOut[0];
    }

    private static String[] argsFor(AntBuildOptions opts, String testClass) {
        List<String> args = new ArrayList<>(opts.targets());
        String[] errorOut = new String[1];
        Map<String, String> props = BuildOptionValidator.validateProperties(
                McpToolPropertyEnum.PROPERTIES.key(), opts.properties(), errorOut);
        args.addAll(BuildOptionValidator.toDefineArgs(props));
        if (opts.keepGoing()) {
            args.add("-k");
        }
        if (testClass != null && !testClass.isBlank()) {
            args.add("-Dtest.includes=" + testClass);
        }
        return args.toArray(new String[0]);
    }

    private static RootResult resolveRoot(String sessionId, String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return new RootResult(null, GitCommonParamEnum.PROJECT_PATH.key() + " is required");
        }
        File dir = new File(projectPath);
        if (!dir.isDirectory()) {
            return new RootResult(null, "Not a project directory: " + projectPath);
        }
        // #3's fix, applied here: resolve to the real/canonical path before the scope check and before it becomes
        // the process's working directory, so a symlink spelling of an open project is treated identically to the
        // canonical one throughout.
        File real = FileUtils.toRealPath(dir);
        var server = McpServerRegistry.getServer();
        if (server == null || !server.isFileAllowed(sessionId, real.getAbsolutePath())) {
            return new RootResult(null, "Access denied: " + projectPath);
        }
        return new RootResult(real, null);
    }

    private static String resolveAnt() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String antBin = "ant" + File.separator + "bin" + File.separator + (windows ? "ant.bat" : "ant");
        File bundled = InstalledFileLocator.getDefault()
                .locate(antBin, "org.apache.tools.ant", false);
        if (bundled != null && bundled.exists()) {
            return bundled.getAbsolutePath();
        }
        return windows ? "ant.bat" : "ant";
    }

    private BuildAndTestAntProvider() {
    }

    private record RootResult(File root, String error) {}
}

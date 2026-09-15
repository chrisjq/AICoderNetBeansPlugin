package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;

public class BuildAndTestMavenProvider {

    private static final Logger LOG = Logger.getLogger(BuildAndTestMavenProvider.class.getName());
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    /**
     * Options shared by BuildMavenProject, CleanAndBuildMavenProject and RunMavenTests (#5 / F2). Every field arrives
     * already resolved by the calling tool — {@code goals} carries that tool's own default ({@code package},
     * {@code clean package}, or {@code test}) when the caller omitted it, and {@code skipTests} carries that tool's own
     * default (true for the two build tools, preserving today's {@code -DskipTests}; false for RunMavenTests, which has
     * never passed it — skipping tests on the tool whose entire purpose is running them would be a confusing default).
     * This record makes no decisions of its own; {@link #argsFor} only translates already- resolved values into CLI
     * flags, and {@link #validate} checks them.
     */
    public record MavenBuildOptions(
            List<String> goals, List<String> projectList, boolean alsoMake, String resumeFrom,
            boolean skipTests, boolean offline, boolean updateSnapshots, List<String> profiles,
            JsonObject properties, String threads, boolean failAtEnd) {

    }

    public static String buildProject(String sessionId, String projectPath, MavenBuildOptions opts) {
        return runBuild(sessionId, projectPath, opts, null);
    }

    public static String cleanAndBuildProject(String sessionId, String projectPath, MavenBuildOptions opts) {
        return runBuild(sessionId, projectPath, opts, null);
    }

    public static String downloadSources(String sessionId, String projectPath) {
        RootResult resolved = resolveRoot(sessionId, projectPath);
        if (resolved.error() != null) {
            return resolved.error();
        }
        return runMaven(sessionId, resolved.root(), "dependency:sources");
    }

    public static String downloadJavadoc(String sessionId, String projectPath) {
        RootResult resolved = resolveRoot(sessionId, projectPath);
        if (resolved.error() != null) {
            return resolved.error();
        }
        return runMaven(sessionId, resolved.root(), "dependency:resolve", "-Dclassifier=javadoc");
    }

    public static String runTests(String sessionId, String testClass, String projectPath, MavenBuildOptions opts) {
        return runBuild(sessionId, projectPath, opts, testClass);
    }

    private static String runBuild(String sessionId, String projectPath, MavenBuildOptions opts, String testClass) {
        String error = validate(opts);
        if (error != null) {
            return "Error: " + error;
        }
        if (testClass != null && !testClass.isBlank()) {
            error = BuildOptionValidator.validateTestSelector(McpToolPropertyEnum.TEST_CLASS.key(), testClass);
            if (error != null) {
                return "Error: " + error;
            }
        }
        RootResult resolved = resolveRoot(sessionId, projectPath);
        if (resolved.error() != null) {
            return resolved.error();
        }
        return runMaven(sessionId, resolved.root(), argsFor(opts, testClass));
    }

    /**
     * Validates every option BEFORE any file resolution or process launch — same principle as #17's targetProjectPath
     * fix: a malformed argument must not be masked by a later, unrelated failure.
     */
    private static String validate(MavenBuildOptions opts) {
        if (opts.goals() == null || opts.goals().isEmpty()) {
            return McpToolPropertyEnum.GOALS.key() + " must not be empty";
        }
        String error = BuildOptionValidator.validateTokens(McpToolPropertyEnum.GOALS.key(), opts.goals());
        if (error != null) {
            return error;
        }
        error = BuildOptionValidator.validateTokens(McpToolPropertyEnum.PROJECT_LIST.key(), opts.projectList());
        if (error != null) {
            return error;
        }
        error = BuildOptionValidator.validateTokens(McpToolPropertyEnum.PROFILES.key(), opts.profiles());
        if (error != null) {
            return error;
        }
        if (opts.resumeFrom() != null && !opts.resumeFrom().isBlank()) {
            error = BuildOptionValidator.validateToken(McpToolPropertyEnum.RESUME_FROM.key(), opts.resumeFrom());
            if (error != null) {
                return error;
            }
        }
        if (opts.threads() != null && !opts.threads().isBlank()) {
            error = BuildOptionValidator.validateToken(McpToolPropertyEnum.THREADS.key(), opts.threads());
            if (error != null) {
                return error;
            }
        }
        String[] errorOut = new String[1];
        BuildOptionValidator.validateProperties(McpToolPropertyEnum.PROPERTIES.key(), opts.properties(), errorOut);
        return errorOut[0];
    }

    private static String[] argsFor(MavenBuildOptions opts, String testClass) {
        List<String> args = new ArrayList<>(opts.goals());
        if (opts.projectList() != null && !opts.projectList().isEmpty()) {
            args.add("-pl");
            args.add(String.join(",", opts.projectList()));
        }
        if (opts.alsoMake()) {
            args.add("-am");
        }
        if (opts.resumeFrom() != null && !opts.resumeFrom().isBlank()) {
            args.add("-rf");
            args.add(opts.resumeFrom());
        }
        if (opts.skipTests()) {
            args.add("-DskipTests");
        }
        if (opts.offline()) {
            args.add("-o");
        }
        if (opts.updateSnapshots()) {
            args.add("-U");
        }
        if (opts.profiles() != null && !opts.profiles().isEmpty()) {
            args.add("-P");
            args.add(String.join(",", opts.profiles()));
        }
        String[] errorOut = new String[1];
        Map<String, String> props = BuildOptionValidator.validateProperties(
                McpToolPropertyEnum.PROPERTIES.key(), opts.properties(), errorOut);
        args.addAll(BuildOptionValidator.toDefineArgs(props));
        if (opts.threads() != null && !opts.threads().isBlank()) {
            args.add("-T");
            args.add(opts.threads());
        }
        if (opts.failAtEnd()) {
            args.add("-fae");
        }
        if (testClass != null && !testClass.isBlank()) {
            args.add("-Dtest=" + testClass);
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
        // the process's working directory, so a symlink spelling of an open project (e.g. /share/code/... aliasing
        // /Users/chris/.SyncShare/...) is treated identically to the canonical one throughout.
        File real = FileUtils.toRealPath(dir);
        var server = McpServerRegistry.getServer();
        if (server == null || !server.isFileAllowed(sessionId, real.getAbsolutePath())) {
            return new RootResult(null, "Access denied: " + projectPath);
        }
        return new RootResult(real, null);
    }

    private static String runMaven(String sessionId, File dir, String... goals) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        File wrapper = new File(dir, windows ? "mvnw.cmd" : "mvnw");
        List<String> cmd = new ArrayList<>();
        if (wrapper.exists()) {
            cmd.add(wrapper.getAbsolutePath());
        }
        else {
            cmd.add("mvn");
        }
        cmd.addAll(List.of(goals));
        cmd.add("--no-transfer-progress");
        Process p = null;
        Thread reader = null;
        AtomicReference<String> outputRef = new AtomicReference<>("");
        AtomicReference<Exception> readerFailure = new AtomicReference<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir);
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process proc = p;
            reader = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int n;
                    InputStream is = proc.getInputStream();
                    while ((n = is.read(buf)) != -1) {
                        if (baos.size() < MAX_OUTPUT_BYTES) {
                            baos.write(buf, 0, Math.min(n, MAX_OUTPUT_BYTES - baos.size()));
                        }
                    }
                    outputRef.set(baos.toString(StandardCharsets.UTF_8));
                }
                catch (Exception e) {
                    readerFailure.set(e);
                }
            }, "mvn-output-reader");
            reader.setDaemon(true);
            reader.start();
            boolean finished = p.waitFor(TimeoutEnum.BUILD_PROCESS_MILLIS.millis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
            }
            try {
                reader.join(5_000);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String output = outputRef.get();
            Exception outputError = readerFailure.get();
            if (outputError != null) {
                return BuildOutputFormatter.attachLog(sessionId, BuildOutputFormatter.Backend.MAVEN,
                                                      "Error reading Maven output: " + outputError.getMessage(), output);
            }
            if (!finished) {
                return BuildOutputFormatter.attachLog(sessionId, BuildOutputFormatter.Backend.MAVEN,
                                                      "Timed out after " + TimeUnit.MILLISECONDS.toSeconds(TimeoutEnum.BUILD_PROCESS_MILLIS.millis()) + "s",
                                                      output);
            }
            int exit = p.exitValue();
            return BuildOutputFormatter.formatResult(sessionId, BuildOutputFormatter.Backend.MAVEN,
                                                     exit == 0, exit, output);
        }
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
            if (reader != null) {
                try {
                    reader.join(2_000);
                }
                catch (InterruptedException ignored) {
                }
            }
            return "Interrupted waiting for build";
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "runMaven error", e);
            return "Error running Maven: " + e.getMessage();
        }
    }

    private BuildAndTestMavenProvider() {
    }

    private record RootResult(File root, String error) {}
}

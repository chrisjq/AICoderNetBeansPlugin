package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;

public class BuildAndTestMavenProvider {

    private static final Logger LOG = Logger.getLogger(BuildAndTestMavenProvider.class.getName());
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    public static String buildProject(String sessionId, String projectPath) {
        RootResult resolved = resolveRoot(sessionId, projectPath);
        if (resolved.error() != null) {
            return resolved.error();
        }
        File root = resolved.root();
        return runMaven(sessionId, root, "package", "-DskipTests");
    }

    public static String cleanAndBuildProject(String sessionId, String projectPath) {
        RootResult resolved = resolveRoot(sessionId, projectPath);
        if (resolved.error() != null) {
            return resolved.error();
        }
        File root = resolved.root();
        return runMaven(sessionId, root, "clean", "package", "-DskipTests");
    }

    public static String downloadSources(String sessionId, String projectPath) {
        RootResult resolved = resolveRoot(sessionId, projectPath);
        if (resolved.error() != null) {
            return resolved.error();
        }
        File root = resolved.root();
        return runMaven(sessionId, root, "dependency:sources");
    }

    public static String downloadJavadoc(String sessionId, String projectPath) {
        RootResult resolved = resolveRoot(sessionId, projectPath);
        if (resolved.error() != null) {
            return resolved.error();
        }
        File root = resolved.root();
        return runMaven(sessionId, root, "dependency:resolve", "-Dclassifier=javadoc");
    }

    public static String runTests(String sessionId, String testClass, String projectPath) {
        RootResult resolved = resolveRoot(sessionId, projectPath);
        if (resolved.error() != null) {
            return resolved.error();
        }
        File root = resolved.root();
        if (testClass != null && !testClass.isBlank()) {
            return runMaven(sessionId, root, "test", "-Dtest=" + testClass);
        }
        return runMaven(sessionId, root, "test");
    }

    private static RootResult resolveRoot(String sessionId, String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return new RootResult(null, GitCommonParamEnum.PROJECT_PATH.key() + " is required");
        }
        File dir = new File(projectPath);
        if (!dir.isDirectory()) {
            return new RootResult(null, "Not a project directory: " + projectPath);
        }
        var server = McpServerRegistry.getServer();
        if (server == null || !server.isFileAllowed(sessionId, dir.getAbsolutePath())) {
            return new RootResult(null, "Access denied: " + projectPath);
        }
        return new RootResult(dir, null);
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

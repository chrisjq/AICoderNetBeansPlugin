package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable, fully validated build invocation ready to run.
 *
 * @param countsTowardLongestSuccess whether a SUCCESSful run of this build may raise {@code
 * BuildQueue.longestSuccessFor}. True for every build and test tool. False for the Maven dependency
 *                                   downloads: a download is not a build, so how long one happened to take
 *                                   must not inflate another build's inline time limit.
 * @param environment                variables set on the build process on top of the inherited environment:
 *                                   {@code JAVA_HOME} for the project's Java platform. Empty leaves the
 *                                   inherited environment untouched.
 */
public record PreparedBuild(String error, String sessionId, File root, List<String> command,
                            BuildOutputFormatter.Backend backend, boolean countsTowardLongestSuccess,
                            Map<String, String> environment) {

    public PreparedBuild {
        command = command == null ? List.of() : List.copyOf(command);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    /**
     * Counts toward Longest OK run and sets no environment, as every build did before environments existed.
     */
    public PreparedBuild(String error, String sessionId, File root, List<String> command,
                         BuildOutputFormatter.Backend backend, boolean countsTowardLongestSuccess) {
        this(error, sessionId, root, command, backend, countsTowardLongestSuccess, Map.of());
    }

    /**
     * Every existing caller's shape, unchanged: counts toward Longest OK run by default — see
     * {@link #countsTowardLongestSuccess}.
     */
    public PreparedBuild(String error, String sessionId, File root, List<String> command,
                         BuildOutputFormatter.Backend backend) {
        this(error, sessionId, root, command, backend, true, Map.of());
    }

    public static PreparedBuild error(String error) {
        return new PreparedBuild(error, null, null, List.of(), null);
    }

    public boolean isError() {
        return error != null;
    }

    /**
     * This build with {@code environment} set on its process. An error build is returned unchanged.
     */
    public PreparedBuild withEnvironment(Map<String, String> environment) {
        return isError() ? this : new PreparedBuild(error, sessionId, root, command, backend, countsTowardLongestSuccess,
                environment);
    }

    /**
     * The command as it is shown to the user: each environment variable as {@code NAME=value} ahead of the
     * executable, the form a shell would take it in, so the result shows which JDK the build ran with.
     */
    public List<String> displayCommand() {
        if (environment.isEmpty()) {
            return command;
        }
        List<String> shown = new ArrayList<>();
        new TreeMap<>(environment).forEach((name, value) -> shown.add(name + "=" + value));
        shown.addAll(command);
        return shown;
    }
}

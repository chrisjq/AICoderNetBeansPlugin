package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.util.List;

/**
 * Immutable, fully validated build invocation ready to run.
 *
 * @param countsTowardLongestSuccess whether a SUCCESSful run of this build may raise {@code
 * BuildQueue.longestSuccessFor}. True for every build and test tool. False for the Maven dependency downloads: a
 * download is not a build, so how long one happened to take must not inflate another build's inline time limit.
 */
public record PreparedBuild(String error, String sessionId, File root, List<String> command,
                            BuildOutputFormatter.Backend backend, boolean countsTowardLongestSuccess) {

    public PreparedBuild {
        command = command == null ? List.of() : List.copyOf(command);
    }

    /**
     * Every existing caller's shape, unchanged: counts toward Longest OK run by default — see
     * {@link #countsTowardLongestSuccess}.
     */
    public PreparedBuild(String error, String sessionId, File root, List<String> command,
                         BuildOutputFormatter.Backend backend) {
        this(error, sessionId, root, command, backend, true);
    }

    public static PreparedBuild error(String error) {
        return new PreparedBuild(error, null, null, List.of(), null);
    }

    public boolean isError() {
        return error != null;
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

/**
 * A validated build waiting to be queued.
 *
 * @param toolCall the tool and its options, as shown by ListBuilds
 * @param projectKey the project's real path; at most one build per key may be queued or running
 * @param projectPath the project path as the caller gave it, for display
 * @param sessionId the calling AI session
 * @param callerName the calling AI session's name, for display
 * @param type async or inline
 * @param timeoutMillis the time limit for the build once it starts
 * @param stoppableWhileRunning whether stopping this build once it has STARTED can actually stop it. True for the build
 * tools, whose work is an external process the queue can kill. False for the IDE build actions: NetBeans hands the
 * caller of {@code ActionProvider.invokeAction} no way to cancel — the Output window's stop button belongs to the
 * project's own action code — so the queue must neither promise a stop nor mark such a build cancelled while it is in
 * fact running to completion. Queued builds are stoppable either way, since removing one from the queue needs nothing
 * from the build system.
 * @param countsTowardLongestSuccess whether a SUCCESSful run of this request may raise {@link
 * BuildQueue#longestSuccessFor}. True for every build and test tool. False for the Maven dependency downloads: a
 * download is not a build, so how long one happened to take must not inflate another build's inline time limit.
 * @param work what runs when the build's turn comes
 */
public record BuildRequest(String toolCall, String projectKey, String projectPath, String sessionId, String callerName,
                           BuildTypeEnum type, long timeoutMillis, boolean stoppableWhileRunning,
                           boolean countsTowardLongestSuccess, BuildWork work) {

    /**
     * Every existing caller's shape, unchanged: counts toward Longest OK run by default — see
     * {@link #countsTowardLongestSuccess}.
     */
    public BuildRequest(String toolCall, String projectKey, String projectPath, String sessionId, String callerName,
                        BuildTypeEnum type, long timeoutMillis, boolean stoppableWhileRunning, BuildWork work) {
        this(toolCall, projectKey, projectPath, sessionId, callerName, type, timeoutMillis, stoppableWhileRunning, true,
             work);
    }
}

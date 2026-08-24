package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.ToolLockRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

/**
 * Runs a single MCP tool call with the same locking/handler semantics for both the HTTP {@code tools/call} path and
 * in-process callers. Returns the tool result string, or a human-readable lock-contention message.
 */
public final class McpToolInvoker {

    private static final ReentrantLock MUTATION_LOCK = new ReentrantLock(true);

    public static String invoke(McpToolEnum tool, McpToolInterface handler,
            JsonObject argsObj, AbstractAiSession session) throws McpArgumentException {
        // Logged here rather than at the HTTP entry point so in-process callers
        // are covered too: Ollama reaches tools through OllamaMcpBridge, so its
        // calls never appeared in the log while this lived in McpHookServer.
        // Null-safe: a read-only tool needing no lock can be invoked without a
        // session, and logging must not make that path throw.
        McpHookServerUtil.logToolUse(
                session == null ? null : session.getSessionName(), tool.toolName(), argsObj);
        // After logging (a refused attempt is still worth a log line) and before any lock
        // is taken, so a denial cannot make a caller wait on a lock it will not get to use.
        String gitScopeDenial = gitScopeDenialOrNull(handler, argsObj, session);
        if (gitScopeDenial != null) {
            return gitScopeDenial;
        }
        LockTypeEnum requiredLock = ToolLockRegistry.getLockType(tool, handler);
        LockManager lockManager = LockManager.getInstance();
        boolean lockAcquired = false;
        try {
            if (requiredLock != null) {
                if (!lockManager.acquireLock(session.getId(), requiredLock)) {
                    String holder = lockManager.getLockHolder(requiredLock);
                    return lockedMessage(requiredLock, holder, tool.toolName());
                }
                lockAcquired = true;
            }
            if (!handler.isMutating() || handler.usesOwnFileLocking()) {
                return safe(handler.handle(new ToolRequestArguments(argsObj), session));
            }
            boolean mutLockAcquired;
            try {
                mutLockAcquired = MUTATION_LOCK.tryLock(TimeoutEnum.MUTATION_LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                mutLockAcquired = false;
            }
            if (!mutLockAcquired) {
                return mutationLockTimeoutMessage();
            }
            try {
                return safe(handler.handle(new ToolRequestArguments(argsObj), session));
            }
            finally {
                MUTATION_LOCK.unlock();
            }
        }
        finally {
            if (lockAcquired && requiredLock != null) {
                lockManager.releaseLock(session.getId(), requiredLock);
            }
        }
    }

    /**
     * Denial message when a git tool is handed a path outside the calling session's file scope, or null when the call
     * may proceed. Applied here rather than in each of the twenty-one git tools because this is the single dispatch
     * point every one of them passes through (the HTTP {@code tools/call} path and OllamaMcpBridge both funnel into
     * {@link #invoke}), so a git tool added later inherits the check instead of having to remember it.
     * <p>
     * What is validated is the CALLER-SUPPLIED path, never the repository root that {@code GitProvider#resolveRoot}
     * eventually walks up to. That distinction is the whole point: a {@code .git} directory very often sits ABOVE the
     * NetBeans project directory, and a session legitimately scoped to the project must still be able to reach the
     * repository that contains it. Scoping the resolved root would break that ordinary layout. Scoping the input does
     * not — the caller must name somewhere it is allowed to be, and the upward walk then lands wherever it lands.
     * <p>
     * Two arguments are checked. {@code projectPath} is documented as required on every git tool
     * ({@code GitCommonParamEnum}), so it is the primary gate. {@code file} is checked too, but only when it is
     * absolute: GitBlame is the one tool that lets {@code projectPath} be omitted when {@code file} is an absolute
     * path, which would otherwise leave a per-line-authorship read of any file on disk completely ungated. A RELATIVE
     * {@code file} is deliberately left alone — GitAdd/GitReset take repo-relative paths that {@code
     * GitProvider#resolveFiles} already confines with its own within-repository check, and resolving them here against
     * the JVM's working directory would reject ordinary, correct calls.
     * <p>
     * Uses {@link McpHookServer#isProjectFileAllowed} rather than {@code isFileAccessible}: a git repository is never
     * the session's own config directory, so the config-dir exemption has nothing to contribute. It fails closed when
     * the server or session id is missing, matching the build providers' existing treatment of the same question.
     * {@code GitCommit}'s {@code areCommitTargetsAllowed} is untouched and still runs — it checks the individual files
     * being committed, which is a narrower question than this one and not answered by it.
     */
    static String gitScopeDenialOrNull(McpToolInterface handler, JsonObject argsObj, AbstractAiSession session) {
        if (handler == null || handler.section() != McpSectionEnum.GIT) {
            return null;
        }
        McpHookServer server = McpServerRegistry.getServer();
        String sessionId = session == null ? null : session.getId();
        String projectPath = McpHookServerUtil.str(argsObj, McpToolPropertyEnum.PROJECT_PATH.key());
        if (projectPath != null && !projectPath.isBlank()
                && !McpHookServer.isProjectFileAllowed(server, sessionId, projectPath)) {
            return McpHookServer.fileAccessDeniedMessage(server, sessionId, projectPath);
        }
        String file = McpHookServerUtil.str(argsObj, McpToolPropertyEnum.FILE.key());
        if (file != null && !file.isBlank() && new File(file).isAbsolute()
                && !McpHookServer.isProjectFileAllowed(server, sessionId, file)) {
            return McpHookServer.fileAccessDeniedMessage(server, sessionId, file);
        }
        return null;
    }

    /**
     * Contention message on acquisition failure. By the time this is returned the acquisition has already waited the
     * lock's full configured wait and lost — the wait duration is reported straight from the {@link TimeoutEnum}
     * constant the lock uses ({@link LockTypeEnum#getWaitTimeout()}) so it can never drift when the constant is
     * retuned. Because the holder survived a full wait, it is still working, so the message steers the caller away from
     * sleeping and retrying in a loop (observed behaviour after the old "try again shortly" wording) and towards doing
     * other work or reporting the contention to the user.
     */
    static String lockedMessage(LockTypeEnum lockType, String holder, String toolName) {
        TimeoutEnum wait = lockType.getWaitTimeout();
        String waited = wait.millis() > 0
                ? "already waited " + wait.millis() / 1000 + "s (" + wait.name() + ") for this lock and lost"
                : "lost this lock immediately (no waiting is configured: " + wait.name() + " is 0)";
        return "Resource locked by session "
                + (holder != null ? holder : "another operation")
                + " performing " + lockType.getDescription()
                + ". Tool: " + toolName + " " + waited
                + ", and the holder is still active — an immediate retry will probably fail again."
                + " Do not sleep and retry in a loop: do other work and come back to this later,"
                + " or report the contention to the user.";
    }

    /**
     * Contention message when the plugin-wide mutation lock stays busy for the full
     * {@link TimeoutEnum#MUTATION_LOCK_WAIT_MILLIS} wait. The mutation lock guards a SINGLE handler invocation, so it
     * is normally brief — unlike the long-lived global locks, an immediate retry really is the right next step here, so
     * this message deliberately keeps a plain "Please try again." and carries none of the do-other-work steer (which
     * would be wrong advice over a lock that is probably already free).
     */
    static String mutationLockTimeoutMessage() {
        return "Error: mutation lock timeout — another operation held the mutation"
                + " lock through the full "
                + TimeoutEnum.MUTATION_LOCK_WAIT_MILLIS / 1000 + "s wait"
                + " (MUTATION_LOCK_WAIT_MILLIS). Please try again.";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private McpToolInvoker() {
    }
}

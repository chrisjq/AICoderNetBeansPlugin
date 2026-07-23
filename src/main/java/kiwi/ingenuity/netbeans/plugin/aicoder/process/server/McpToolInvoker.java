package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.ToolLockRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

/**
 * Runs a single MCP tool call with the same locking/handler semantics for both
 * the HTTP {@code tools/call} path and in-process callers. Returns the tool
 * result string, or a human-readable lock-contention message.
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
        LockTypeEnum requiredLock = ToolLockRegistry.getLockType(tool, handler);
        LockManager lockManager = LockManager.getInstance();
        boolean lockAcquired = false;
        try {
            if (requiredLock != null) {
                if (!lockManager.acquireLock(session.getId(), requiredLock)) {
                    String holder = lockManager.getLockHolder(requiredLock);
                    return "Resource locked by session " + holder + ". Tool: "
                            + tool.toolName() + ". Please try again shortly.";
                }
                lockAcquired = true;
            }
            if (!handler.isMutating() || handler.usesOwnFileLocking()) {
                return safe(handler.handle(new ToolRequestArguments(argsObj), session));
            }
            boolean mutLockAcquired;
            try {
                mutLockAcquired = MUTATION_LOCK.tryLock(900, TimeUnit.SECONDS);
            }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                mutLockAcquired = false;
            }
            if (!mutLockAcquired) {
                return "Error: mutation lock timeout — another operation is blocking. Please try again.";
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

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private McpToolInvoker() {
    }
}

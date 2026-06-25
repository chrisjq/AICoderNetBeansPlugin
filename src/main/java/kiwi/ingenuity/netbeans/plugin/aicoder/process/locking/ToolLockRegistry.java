package kiwi.ingenuity.netbeans.plugin.aicoder.process.locking;

import java.util.HashMap;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.Registry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

/**
 * Registry mapping tools to their required locks. Primary source: @RequiresLock
 * annotation on tool class. Fallback: Hard-coded mappings for tools without
 * annotation.
 */
public class ToolLockRegistry implements Registry {

    private static final Map<McpToolEnum, LockTypeEnum> FALLBACK_LOCKS = new HashMap<>();

    static {
        // Git operations - GIT_LOCK
        FALLBACK_LOCKS.put(McpToolEnum.GIT_COMMIT, LockTypeEnum.GIT_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.GIT_PUSH, LockTypeEnum.GIT_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.GIT_PULL, LockTypeEnum.GIT_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.GIT_REBASE, LockTypeEnum.GIT_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.GIT_MERGE, LockTypeEnum.GIT_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.GIT_RESET, LockTypeEnum.GIT_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.GIT_REVERT, LockTypeEnum.GIT_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.GIT_CHERRY_PICK, LockTypeEnum.GIT_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.GIT_BRANCH, LockTypeEnum.GIT_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.GIT_DELETE_BRANCH, LockTypeEnum.GIT_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.GIT_CHECKOUT, LockTypeEnum.GIT_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.GIT_STASH, LockTypeEnum.GIT_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.GIT_ADD, LockTypeEnum.GIT_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.GIT_TAG, LockTypeEnum.GIT_LOCK);

        // Build operations - BUILD_LOCK
        FALLBACK_LOCKS.put(McpToolEnum.BUILD_MAVEN_PROJECT, LockTypeEnum.BUILD_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT, LockTypeEnum.BUILD_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.BUILD_GRADLE_PROJECT, LockTypeEnum.BUILD_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.BUILD_ANT_PROJECT, LockTypeEnum.BUILD_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.BUILD_PROJECT, LockTypeEnum.BUILD_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.CLEAN_PROJECT, LockTypeEnum.BUILD_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.CLEAN_AND_BUILD_PROJECT, LockTypeEnum.BUILD_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.RUN_MAVEN_TESTS, LockTypeEnum.BUILD_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.RUN_GRADLE_TESTS, LockTypeEnum.BUILD_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.RUN_ANT_TESTS, LockTypeEnum.BUILD_LOCK);

        // Refactoring operations - REFACTOR_LOCK
        FALLBACK_LOCKS.put(McpToolEnum.RENAME_SYMBOL, LockTypeEnum.REFACTOR_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.MOVE_CLASS, LockTypeEnum.REFACTOR_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.INLINE_VARIABLE, LockTypeEnum.REFACTOR_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.CHANGE_METHOD_SIGNATURE, LockTypeEnum.REFACTOR_LOCK);

        // File write operations - FILE_WRITE_LOCK
        FALLBACK_LOCKS.put(McpToolEnum.SAVE_FILE, LockTypeEnum.FILE_WRITE_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.DELETE_FILE, LockTypeEnum.FILE_WRITE_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.COPY_FILE, LockTypeEnum.FILE_WRITE_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.MOVE_FILE, LockTypeEnum.FILE_WRITE_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.FIX_IMPORTS, LockTypeEnum.FILE_WRITE_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.ORGANISE_IMPORTS, LockTypeEnum.FILE_WRITE_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.ORGANISE_MEMBERS, LockTypeEnum.FILE_WRITE_LOCK);
        FALLBACK_LOCKS.put(McpToolEnum.REFORMAT_FILE, LockTypeEnum.FILE_WRITE_LOCK);
    }

    /**
     * Get the lock type required by a tool. First checks @RequiresLock
     * annotation, then fallback registry.
     *
     * @return LockType if tool requires a lock, null if no lock required
     */
    public static LockTypeEnum getLockType(McpToolEnum tool, McpToolInterface handler) {
        // Check annotation first (takes precedence)
        RequiresLock annotation = handler.getClass().getAnnotation(RequiresLock.class);
        if (annotation != null) {
            return annotation.value();
        }

        // Fall back to registry
        return FALLBACK_LOCKS.get(tool);
    }

    /**
     * Check if a tool requires a lock.
     */
    public static boolean requiresLock(McpToolEnum tool, McpToolInterface handler) {
        return getLockType(tool, handler) != null;
    }
}

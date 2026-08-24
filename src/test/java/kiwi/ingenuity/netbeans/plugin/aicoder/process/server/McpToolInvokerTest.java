package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.plugin.GetPluginVersionTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Lock-contention messages must report that the tool ALREADY WAITED (duration derived from the TimeoutEnum constant the
 * acquisition actually used, never a hardcoded number), keep the holder and refused-tool facts, and steer the reader
 * away from sleep-and-retry loops — "try again shortly" has been observed to send AI sessions into exactly such loops.
 */
class McpToolInvokerTest {

    private static String lowercase(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    @Test
    void readOnlyToolReturnsHandlerResultWithoutLocks() throws Exception {
        McpToolEnum tool = McpToolEnum.GET_PLUGIN_VERSION;
        McpToolInterface handler = new GetPluginVersionTool();
        String result = McpToolInvoker.invoke(tool, handler, new JsonObject(), null);
        assertEquals(handler.handle(new ToolRequestArguments(new JsonObject()), null), result);
    }

    @Test
    void everyLockTypeDerivesItsWaitFromATimeoutEnumConstant() {
        for (LockTypeEnum lockType : LockTypeEnum.values()) {
            assertEquals(lockType.getWaitTimeoutMillis(),
                    lockType.getWaitTimeout().millis(), lockType.name());
            assertTrue(lockType.getWaitTimeoutMillis() >= 0, lockType.name());
        }
    }

    @Test
    void buildLockMessageReportsWaitedDurationHolderAndTool() {
        String message = McpToolInvoker.lockedMessage(LockTypeEnum.BUILD_LOCK, "other-session", "BuildMavenProject");
        assertTrue(message.contains("Resource locked by session other-session performing Build Operations"));
        assertTrue(message.contains("Tool: BuildMavenProject "));
        long expectedSeconds = TimeoutEnum.BUILD_LOCK_WAIT_MILLIS.millis() / 1000;
        assertTrue(message.contains("already waited " + expectedSeconds + "s"),
                "must state it already waited the configured duration: " + message);
        assertTrue(message.contains(TimeoutEnum.BUILD_LOCK_WAIT_MILLIS.name()),
                "must name the TimeoutEnum constant so the derivation is visible: " + message);
    }

    @Test
    void buildLockMessageWorksWithoutKnownHolder() {
        String message = McpToolInvoker.lockedMessage(LockTypeEnum.BUILD_LOCK, null, "RunMavenTests");
        assertTrue(message.contains("Resource locked by session another operation performing Build Operations"));
        assertTrue(message.contains("already waited "
                + TimeoutEnum.BUILD_LOCK_WAIT_MILLIS.millis() / 1000 + "s"));
    }

    @Test
    void globalLockMessagesSteerAwayFromSleepAndRetryLoops() {
        assertFalse(lowercase(McpToolInvoker.lockedMessage(LockTypeEnum.BUILD_LOCK, "s", "T"))
                .contains("try again shortly"),
                "the old 'try again shortly' wording must not come back");
        assertTrue(McpToolInvoker.lockedMessage(LockTypeEnum.BUILD_LOCK, "s", "T")
                .contains("Do not sleep and retry in a loop"));
    }

    @Test
    void everyGlobalLockMessageNamesDurationAndAvoidsImmediateRetryAdvice() {
        for (LockTypeEnum lockType : LockTypeEnum.values()) {
            String message = McpToolInvoker.lockedMessage(lockType, "holder-session", "SomeTool");
            assertFalse(lowercase(message).contains("shortly"), lockType.name() + ": " + message);
            if (lockType.getWaitTimeoutMillis() > 0) {
                assertTrue(message.contains("already waited "
                        + lockType.getWaitTimeoutMillis() / 1000 + "s"),
                        lockType.name() + ": " + message);
            }
            else {
                // A zero-wait lock (FILE_WRITE_LOCK today) fails immediately; the
                // message must not claim a wait happened.
                assertTrue(message.contains("immediately"), lockType.name() + ": " + message);
            }
            assertTrue(message.contains("holder-session"), lockType.name() + ": " + message);
            assertTrue(message.contains("report the contention to the user"),
                    lockType.name() + ": " + message);
        }
    }

    @Test
    void mutationLockMessageStatesWaitButKeepsPlainRetryAdvice() {
        String message = McpToolInvoker.mutationLockTimeoutMessage();
        assertTrue(message.startsWith("Error: mutation lock timeout"), message);
        assertTrue(message.contains("already held") || message.contains("held the mutation lock through the full"),
                message);
        assertTrue(message.contains(TimeoutEnum.MUTATION_LOCK_WAIT_MILLIS / 1000 + "s wait"),
                "must state the full waited duration: " + message);
        assertTrue(message.contains("MUTATION_LOCK_WAIT_MILLIS"), message);
        assertTrue(message.endsWith("Please try again."),
                "the mutation lock is brief by design; plain retry advice is correct: " + message);
        assertFalse(lowercase(message).contains("do not sleep")
                || lowercase(message).contains("do other work"),
                "no anti-loop steer on the short-lived mutation lock: " + message);
    }
}

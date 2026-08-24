package kiwi.ingenuity.netbeans.plugin.aicoder.process.locking;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The shared per-file lock contention message (used by ApplyEditTool, WriteFileTool and the native edit/write hook)
 * describes a holder that is usually waiting on a HUMAN diff approval — up to
 * {@link TimeoutEnum#USER_APPROVAL_WAIT_MILLIS} — so it must point at the user decision and never encourage a retry
 * loop.
 */
class FileLockedMessageTest {

    private static String lowercase(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    @Test
    void namesTheHoldingSessionAndTheUserApprovalWait() {
        String message = LockManager.fileLockedMessage("peer-session");
        assertTrue(message.contains("File is locked by session peer-session"), message);
        long expectedSeconds = TimeoutEnum.USER_APPROVAL_WAIT_MILLIS.millis() / 1000;
        assertTrue(message.contains(expectedSeconds + "s"),
                "must state the approval window derived from the constant: " + message);
        assertTrue(message.contains(TimeoutEnum.USER_APPROVAL_WAIT_MILLIS.name()), message);
        assertTrue(lowercase(message).contains("user"), message);
    }

    @Test
    void worksWithoutKnownHolder() {
        String message = LockManager.fileLockedMessage(null);
        assertTrue(message.contains("File is locked by another in-progress edit"), message);
        assertTrue(message.contains(
                TimeoutEnum.USER_APPROVAL_WAIT_MILLIS.millis() / 1000 + "s"), message);
    }

    @Test
    void neverSuggestsRetryingShortly() {
        String withHolder = LockManager.fileLockedMessage("s");
        String withoutHolder = LockManager.fileLockedMessage(null);
        for (String message : new String[]{withHolder, withoutHolder}) {
            assertFalse(lowercase(message).contains("shortly"), message);
            assertTrue(message.contains("do not sleep and retry in a loop")
                    || message.contains("Retrying cannot succeed until the user decides"),
                    message);
            assertTrue(message.contains("report this to the user"), message);
        }
    }
}

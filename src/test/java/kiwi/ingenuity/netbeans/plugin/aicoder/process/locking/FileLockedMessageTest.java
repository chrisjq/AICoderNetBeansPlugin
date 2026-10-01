package kiwi.ingenuity.netbeans.plugin.aicoder.process.locking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The shared per-file lock contention message (used by ApplyEditTool, WriteFileTool and the native edit/write
 * hook) describes a holder blocked only on another write — never on a human diff approval, since no lock is
 * held across one — so it must say the wait is normally brief and a prompt retry is reasonable, not warn
 * against retrying.
 */
class FileLockedMessageTest {

    private static String lowercase(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    @Test
    void namesTheHoldingSessionAndStaysBrief() {
        String message = LockManager.fileLockedMessage("peer-session");
        assertTrue(message.contains("File is locked by session peer-session"), message);
        assertTrue(lowercase(message).contains("brief"), message);
    }

    @Test
    void worksWithoutKnownHolder() {
        String message = LockManager.fileLockedMessage(null);
        assertTrue(message.contains("File is locked by another in-progress write"), message);
        assertTrue(lowercase(message).contains("brief"), message);
    }

    @Test
    void suggestsRetryingShortlyRatherThanWarningAgainstIt() {
        String withHolder = LockManager.fileLockedMessage("s");
        String withoutHolder = LockManager.fileLockedMessage(null);
        for (String message : new String[]{withHolder, withoutHolder}) {
            assertTrue(lowercase(message).contains("shortly"), message);
            assertFalse(message.contains("do not sleep and retry in a loop"), message);
            assertFalse(lowercase(message).contains("user to review"), message);
            assertTrue(message.contains("report this to the user"), message);
        }
    }
}

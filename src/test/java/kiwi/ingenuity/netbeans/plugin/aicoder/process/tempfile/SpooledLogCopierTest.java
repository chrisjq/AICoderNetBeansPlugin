package kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile;

import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SpooledLogCopier}'s refusal paths, which are the ones that must never throw or corrupt a build result.
 * <p>
 * The successful-copy path is deliberately not asserted here: it needs a real session temp tree owned by
 * {@code TempFileRegistry}, and standing one up just to make this class go green would be testing the registry rather
 * than the copier. What is tested is that every way the copy can fail leaves the caller's text exactly as it was, since
 * the result body above the path line is still worth delivering.
 */
class SpooledLogCopierTest {

    @Test
    void textWithoutALogPathIsReturnedUnchanged() {
        String result = "BUILD SUCCESS\n\nTests run: 3, Failures: 0";

        assertSame(result, SpooledLogCopier.copyForSession("session-1", result),
                   "there is no path line to repoint, so the text must be handed back untouched");
    }

    @Test
    void anUnreadableLogLeavesTheResultAlone(@TempDir Path dir) {
        String missing = dir.resolve("never-written.log").toString();
        String result = "BUILD SUCCESS\n\n" + SpooledLogCopier.LOG_PATH_PREFIX + missing;

        assertEquals(result, SpooledLogCopier.copyForSession("session-1", result),
                     "a log that cannot be read must not cost the caller its result text");
        assertTrue(SpooledLogCopier.copyForSession("session-1", result).contains(missing),
                   "the original path stays, rather than being replaced by nothing");
    }

    @Test
    void aBlankPathAfterThePrefixIsIgnored() {
        String result = "BUILD SUCCESS\n\n" + SpooledLogCopier.LOG_PATH_PREFIX + "   ";

        assertEquals(result, SpooledLogCopier.copyForSession("session-1", result));
    }

    @Test
    void missingArgumentsAreToleratedRatherThanThrowing() {
        assertNull(SpooledLogCopier.copyForSession("session-1", null));
        String result = "BUILD SUCCESS\n\n" + SpooledLogCopier.LOG_PATH_PREFIX + "/tmp/whatever.log";
        assertSame(result, SpooledLogCopier.copyForSession(null, result),
                   "no recipient means there is nowhere to copy to, so nothing changes");
    }
}

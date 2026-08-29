package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The fail-closed guard on {@code applyEdit}'s content read.
 *
 * <p>
 * The defect it exists for — NetBeans returning a truncated read from a stale cached length, which a read-modify-write
 * then persists — cannot be reproduced headlessly: there is no live MasterFileSystem to hold a stale cache, so a
 * temp-file test passes whether the fix is present or not. One was written in August, observed to pass under revert,
 * and deleted rather than kept as false assurance.</p>
 *
 * <p>
 * What IS testable, and what these cover, is the guard's decision. {@code describeShortRead} is pure and takes the two
 * numbers separately, so a disagreement between "bytes read" and "size on disk" can be driven directly. That is the
 * step which turns a bad read into a refusal instead of a write.</p>
 */
class RefactoringProviderShortReadGuardTest {

    @Test
    void aReadThatAccountsForTheWholeFileIsAllowed() {
        assertNull(RefactoringProvider.describeShortRead("/p/A.java", 36240L, 36240L));
    }

    @Test
    void anEmptyFileReadAsEmptyIsAllowed() {
        assertNull(RefactoringProvider.describeShortRead("/p/Empty.java", 0L, 0L));
    }

    /**
     * The measured shape of both incidents: the read comes back short, and every byte past the boundary would be lost
     * if the write went ahead.
     */
    @Test
    void aTruncatedReadIsRefused() {
        String error = RefactoringProvider.describeShortRead("/p/AiDiffTopComponent.java", 4097L, 36240L);

        assertNotNull(error, "a read shorter than the file must never be written");
        assertTrue(error.contains("4097") && error.contains("36240"),
                "the error must name both counts so the mismatch is visible: " + error);
        assertTrue(error.contains("/p/AiDiffTopComponent.java"), error);
    }

    /**
     * The refusal has to say two things the caller acts on: that nothing was written, and that retrying is worthwhile.
     * A bare "not found" is what this replaces, and it read as "your anchor is wrong" when the truth was "the read was
     * short" — which is why the underlying defect went unnoticed through a whole session.
     */
    @Test
    void theRefusalSaysNothingWasWrittenAndIsRetryable() {
        String error = RefactoringProvider.describeShortRead("/p/A.java", 100L, 200L);

        assertTrue(error.contains("NOT applied"), error);
        assertTrue(error.toLowerCase().contains("retry"), error);
    }

    /**
     * A read LONGER than the stat'd size is equally untrustworthy — the file grew mid-read, so the content and the size
     * disagree about which version this is. Refusing costs a retry; guessing costs the difference.
     */
    @Test
    void aReadLongerThanTheFileIsAlsoRefused() {
        assertNotNull(RefactoringProvider.describeShortRead("/p/A.java", 500L, 200L));
    }
}

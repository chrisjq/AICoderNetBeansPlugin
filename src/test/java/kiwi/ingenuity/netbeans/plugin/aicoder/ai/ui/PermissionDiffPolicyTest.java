package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class PermissionDiffPolicyTest {

    @Test
    void blankPath_denies() {
        var d = PermissionDiffPolicy.decide("Write", "  ", "", null, null, "x");
        assertEquals(PermissionDiffPolicy.Outcome.DENY, d.outcome());
    }

    @Test
    void writeNewFile_showsDiff() {
        var d = PermissionDiffPolicy.decide("Write", "/tmp/a.java", "", null, null, "class A {}");
        assertEquals(PermissionDiffPolicy.Outcome.SHOW_DIFF, d.outcome());
        assertEquals("class A {}", d.proposedContent());
    }

    @Test
    void writeIdentical_allowsSilent() {
        var d = PermissionDiffPolicy.decide("Write", "/tmp/a.java", "same", null, null, "same");
        assertEquals(PermissionDiffPolicy.Outcome.ALLOW_SILENT, d.outcome());
    }

    @Test
    void editMissingOldString_denies() {
        var d = PermissionDiffPolicy.decide("Edit", "/tmp/a.java", "hello", null, "x", null);
        assertEquals(PermissionDiffPolicy.Outcome.DENY, d.outcome());
    }

    @Test
    void editOldNotFound_denies() {
        var d = PermissionDiffPolicy.decide("Edit", "/tmp/a.java", "hello", "missing", "x", null);
        assertEquals(PermissionDiffPolicy.Outcome.DENY, d.outcome());
        assertNull(d.proposedContent());
    }

    @Test
    void editAppliesAndShowsDiff() {
        var d = PermissionDiffPolicy.decide("Edit", "/tmp/a.java", "hello world", "world", "there", null);
        assertEquals(PermissionDiffPolicy.Outcome.SHOW_DIFF, d.outcome());
        assertEquals("hello there", d.proposedContent());
    }

    @Test
    void editNoop_allowsSilent() {
        var d = PermissionDiffPolicy.decide("Edit", "/tmp/a.java", "hello", "hello", "hello", null);
        assertEquals(PermissionDiffPolicy.Outcome.ALLOW_SILENT, d.outcome());
    }
}

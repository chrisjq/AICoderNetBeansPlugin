package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void editExactMatch_stillAppliesCorrectly() {
        String orig = "    public void foo() {}";
        var d = PermissionDiffPolicy.decide("Edit", "/tmp/a.java", orig, "foo", "bar", null);
        assertEquals(PermissionDiffPolicy.Outcome.SHOW_DIFF, d.outcome());
        assertEquals("    public void bar() {}", d.proposedContent());
    }

    @Test
    void editWhitespaceMismatch_producesHint() {
        String orig = "    public void foo() {\n        return;\n    }";
        String oldString = "      public void foo() {\n          return;\n      }";
        var d = PermissionDiffPolicy.decide("Edit", "/tmp/a.java", orig, oldString, "x", null);
        assertEquals(PermissionDiffPolicy.Outcome.DENY, d.outcome());
        assertTrue(d.reason().contains("A match WAS found ignoring leading whitespace"),
                "expected whitespace hint, got: " + d.reason());
        assertTrue(d.reason().contains("2 spaces"),
                "expected delta of 2 spaces, got: " + d.reason());
    }

    @Test
    void editGenuinelyAbsent_producesOriginalMessage() {
        var d = PermissionDiffPolicy.decide("Edit", "/tmp/a.java", "hello world", "xyz", "y", null);
        assertEquals(PermissionDiffPolicy.Outcome.DENY, d.outcome());
        assertTrue(d.reason().contains("not found"),
                "expected original not-found message, got: " + d.reason());
        assertFalse(d.reason().contains("A match WAS found"),
                "should NOT produce whitespace hint, got: " + d.reason());
    }

    @Test
    void editWhitespaceMismatch_internalWhitespacePreserved() {
        // strip() only removes leading/trailing; different internal whitespace prevents a false match
        String orig = "foo bar";         // single internal space
        String oldString = "  foo  bar"; // 2 extra leading spaces AND double internal space
        var d = PermissionDiffPolicy.decide("Edit", "/tmp/a.java", orig, oldString, "x", null);
        assertEquals(PermissionDiffPolicy.Outcome.DENY, d.outcome());
        assertFalse(d.reason().contains("A match WAS found"),
                "different internal whitespace must not produce a false whitespace hint; got: " + d.reason());
    }

    @Test
    void editCrlfMismatch_doesNotClaimIndentation() {
        // CRLF in orig, LF in oldString — strip() removes the \r, delta is 0 (no leading space change)
        String orig = "foo\r\nbar";
        String oldString = "foo\nbar";
        var d = PermissionDiffPolicy.decide("Edit", "/tmp/a.java", orig, oldString, "x", null);
        assertEquals(PermissionDiffPolicy.Outcome.DENY, d.outcome());
        assertTrue(d.reason().contains("A match WAS found"),
                "expected a whitespace hint, got: " + d.reason());
        assertFalse(d.reason().contains("indentation"),
                "must NOT claim indentation for a CRLF mismatch, got: " + d.reason());
    }

    @Test
    void editTrailingWhitespaceMismatch_doesNotClaimIndentation() {
        // trailing space on one line; strip() removes it, delta is 0 (no leading change)
        String orig = "foo\nbar";
        String oldString = "foo \nbar";
        var d = PermissionDiffPolicy.decide("Edit", "/tmp/a.java", orig, oldString, "x", null);
        assertEquals(PermissionDiffPolicy.Outcome.DENY, d.outcome());
        assertTrue(d.reason().contains("A match WAS found"),
                "expected a whitespace hint, got: " + d.reason());
        assertFalse(d.reason().contains("indentation"),
                "must NOT claim indentation for a trailing-whitespace mismatch, got: " + d.reason());
    }

    @Test
    void editTabVsSpaceMismatch_doesNotClaimSpaceDelta() {
        // orig uses tab indentation; oldString uses spaces — hasLeadingTab() must
        // return null from consistentLeadingSpaceDelta so we fall through to the
        // generic message rather than claiming "differs by N spaces".
        // Would FAIL without the hasLeadingTab() guard (old code counted tab as 1 space).
        String orig = "\tpublic void foo() {\n\t\treturn;\n\t}";
        String oldString = "    public void foo() {\n        return;\n    }";
        var d = PermissionDiffPolicy.decide("Edit", "/tmp/a.java", orig, oldString, "x", null);
        assertEquals(PermissionDiffPolicy.Outcome.DENY, d.outcome());
        assertTrue(d.reason().contains("A match WAS found"),
                "expected a whitespace hint, got: " + d.reason());
        assertFalse(d.reason().contains("indentation differs by"),
                "must NOT claim space-delta when tabs are involved, got: " + d.reason());
    }
}

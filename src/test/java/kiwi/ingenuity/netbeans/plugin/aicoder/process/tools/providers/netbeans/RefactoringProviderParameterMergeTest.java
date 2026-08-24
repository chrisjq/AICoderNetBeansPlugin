package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.Test;
import org.netbeans.modules.refactoring.java.api.ChangeParametersRefactoring.ParameterInfo;

/**
 * Pins {@link RefactoringProvider#mergeParameterInfos}: partial ChangeMethodSignature entries ({@code name}-only or
 * {@code type}-only — the shape the tool schema's own example shows) must be resolved against the method's current
 * signature instead of silently doing nothing, and omitted {@code defaultValue}s on existing-index entries must stay
 * null ("keep call sites") rather than become an empty-string rewrite.
 */
class RefactoringProviderParameterMergeTest {

    private static ParameterInfo[] existing() {
        return new ParameterInfo[]{
            new ParameterInfo(0, "count", "int", null),
            new ParameterInfo(1, "name", "String", null),
            new ParameterInfo(2, "flags", "boolean", null)
        };
    }

    @Test
    void omittedOrNullRequested_returnsExistingUnchanged() {
        ParameterInfo[] ex = existing();
        assertSame(ex, RefactoringProvider.mergeParameterInfos(null, ex));
        assertSame(ex, RefactoringProvider.mergeParameterInfos(new ParameterInfo[0], ex));
    }

    @Test
    void emptyExisting_returnsRequestedUntouched() {
        ParameterInfo[] req = {new ParameterInfo(-1, "x", "int", "0")};
        assertSame(req, RefactoringProvider.mergeParameterInfos(req, new ParameterInfo[0]));
    }

    // THE regression: {"originalIndex":1,"name":"newName"} used to collapse to
    // ParameterInfo(1) = "change nothing". It must rename while keeping the declared type.
    @Test
    void nameOnlyUpdate_resolvesTypeFromExistingParam() {
        ParameterInfo[] merged = RefactoringProvider.mergeParameterInfos(
                new ParameterInfo[]{new ParameterInfo(1, "newName", null, null)}, existing());

        assertEquals(1, merged.length);
        assertEquals(1, merged[0].getOriginalIndex());
        assertEquals("newName", merged[0].getName());
        assertEquals("String", merged[0].getType(), "type must come from the live signature");
        assertNull(merged[0].getDefaultValue(), "omitted default stays null = keep call sites");
    }

    @Test
    void typeOnlyUpdate_resolvesNameFromExistingParam() {
        ParameterInfo[] merged = RefactoringProvider.mergeParameterInfos(
                new ParameterInfo[]{new ParameterInfo(0, null, "long", null)}, existing());

        assertEquals("count", merged[0].getName(), "name must come from the live signature");
        assertEquals("long", merged[0].getType());
    }

    @Test
    void fullySpecifiedEntry_passesThroughIncludingExplicitDefault() {
        ParameterInfo req = new ParameterInfo(2, "options", "long", "0L");
        ParameterInfo[] merged = RefactoringProvider.mergeParameterInfos(
                new ParameterInfo[]{req}, existing());

        assertSame(req, merged[0]);
        // Empirically pinned against org.netbeans.modules.refactoring.java.api
        // .ChangeParametersRefactoring.ParameterInfo on this platform: its 4-arg constructor
        // does NOT retain defaultVal for existing-index entries — defaults are only honoured
        // for NEW (originalIndex == -1) parameters. Pass-through preserves the object; the
        // engine's own semantics decide what a default means.
        assertNull(merged[0].getDefaultValue());
    }

    @Test
    void brandNewParameter_passesThroughUntouched() {
        ParameterInfo req = new ParameterInfo(-1, "extra", "int", "42");
        ParameterInfo[] merged = RefactoringProvider.mergeParameterInfos(
                new ParameterInfo[]{req}, existing());

        assertSame(req, merged[0]);
    }

    @Test
    void outOfRangeOriginalIndex_isLeftAloneRatherThanCorrupted() {
        ParameterInfo req = new ParameterInfo(7, "renamed", null, null);
        ParameterInfo[] merged = RefactoringProvider.mergeParameterInfos(
                new ParameterInfo[]{req}, existing());

        assertSame(req, merged[0], "cannot resolve against a non-existent parameter");
    }
}

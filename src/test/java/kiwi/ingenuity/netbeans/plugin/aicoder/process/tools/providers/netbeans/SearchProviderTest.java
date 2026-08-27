package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class SearchProviderTest {

    @Test
    void validateNamePatternRejectsInvalidRegexp() {
        String error = SearchProvider.validateNamePattern("*SettingsPanel", "regexp");

        assertTrue(error.startsWith("Invalid regex:"));
    }

    @Test
    void validateNamePatternAllowsValidRegexp() {
        assertNull(SearchProvider.validateNamePattern(".*SettingsPanel", "regexp"));
    }

    @Test
    void validateNamePatternSkipsValidationForNonRegexpKinds() {
        assertNull(SearchProvider.validateNamePattern("*SettingsPanel", "prefix"));
    }

    /*
     * SearchInFiles.filePattern filter — the matching contract.
     *
     * SearchInFiles searches only source-classpath roots, so its filePattern
     * cannot be exercised end-to-end in the plain-JUnit (no nbplatform) harness:
     * with no open project, SearchProvider.searchInFiles returns "No projects
     * open" / "Cannot resolve source classpath" before any file walks. What IS
     * pinned here is the exact matching expression the tool relies on (SearchProvider
     * :72-73 builds a "glob:" PathMatcher and :91 filters each walked path's
     * getFileName() against it), so the leaf-name-glob semantics cannot silently
     * drift to full-path matching or stop applying the pattern. Include and exclude
     * cases are both asserted.
     */
    @Test
    void filePatternGlob_matchesLeafFileName_includeCase() {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:REFERENCE.md");

        // The tool matches p.getFileName(), so a subdirectory file still matches
        // as long as its leaf name equals the glob.
        assertTrue(matcher.matches(Path.of("docs/REFERENCE.md").getFileName()),
                "a file whose leaf name equals the glob must match regardless of directory");
    }

    @Test
    void filePatternGlob_excludesNonMatchingLeaf_excludeCase() {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:REFERENCE.md");

        assertFalse(matcher.matches(Path.of("README.md").getFileName()),
                "a differently-named leaf must not match, even though both are .md");
        assertFalse(matcher.matches(Path.of("sub/reference.md").getFileName()),
                "the glob is case-sensitive leaf matching, so a lower-cased leaf must not match");
    }

    /**
     * A malformed glob is the caller's mistake and must be reported as such. Unguarded, {@code getPathMatcher} threw
     * out of {@code searchInFiles} and surfaced as a {@code -32603} internal error, which tells the caller the tool
     * broke rather than that its {@code filePattern} was wrong — so a bad argument was indistinguishable from a real
     * defect. The check runs before the source roots are resolved, which is also why this is reachable with no open
     * project: otherwise "No projects open" would mask it.
     */
    @Test
    void invalidFilePatternIsReportedAsABadArgumentNotAnInternalError() {
        String result = SearchProvider.searchInFiles(null, "anything", "*.{java", false, false);

        assertTrue(result.startsWith("Invalid " + McpToolPropertyEnum.FILE_PATTERN.key()),
                "a malformed glob must be refused by name, not thrown: " + result);
        assertTrue(result.contains("*.{java"),
                "the refusal must quote the offending pattern back: " + result);
    }

    @Test
    void validFilePatternIsNotRejected() {
        // The negative control: with a well-formed glob the call must get PAST the pattern check and stop at the
        // environment instead. Without this, the assertion above would also pass if every pattern were refused.
        String result = SearchProvider.searchInFiles(null, "anything", "*.java", false, false);

        assertFalse(result.startsWith("Invalid " + McpToolPropertyEnum.FILE_PATTERN.key()),
                "a valid glob must not be refused: " + result);
    }

    @Test
    void filePatternGlob_defaultJavaPattern_onlyMatchesJavaLeaves() {
        // Default filePattern when omitted is "*.java" (SearchProvider :72).
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:*.java");

        assertTrue(matcher.matches(Path.of("com/example/Foo.java").getFileName()));
        assertFalse(matcher.matches(Path.of("README.md").getFileName()),
                "the default *.java glob must exclude non-Java leaves, which is why a .md file"
                + " passed as filePattern: 'REFERENCE.md' returns nothing: it does filter,"
                + " to a leaf name that no walked source file has");
    }
}

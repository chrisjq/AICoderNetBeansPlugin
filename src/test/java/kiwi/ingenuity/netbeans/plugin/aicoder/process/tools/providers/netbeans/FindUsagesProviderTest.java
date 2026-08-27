package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.FindUsagesProvider.RawUsage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * {@link FindUsagesProvider#findUsages} itself needs a live NetBeans project index (JavaSource/RefactoringSession),
 * which a plain JUnit run does not have — the same nbplatform limitation documented in SearchProviderTest. These tests
 * instead drive the extracted, pure {@link FindUsagesProvider#dedupe} directly.
 *
 * <p>
 * Regression guard for the live-verification finding: with {@code findSubclasses=true}, WhereUsedQuery's plain
 * reference-finder pass and its FIND_SUBCLASSES subclass-enumeration pass both reported a direct subclass's own
 * {@code extends} clause — same file, same offset — as two separate elements, so
 * {@code AiModelSessionSettings.java:5 extends AiSessionSettings} was listed twice for one real occurrence.
 */
class FindUsagesProviderTest {

    @Test
    void dedupe_collapsesTwoRenderedCopiesOfOneOccurrenceAtDifferentOffsets_toOneLine() {
        // Reproduces the live finding: the reference-finder and subclass passes produce the same
        // rendered occurrence but report different offsets for it.
        RawUsage fromReferenceFinderPass = new RawUsage(
                "/project/AiModelSessionSettings.java", 63, 5,
                "public class AiModelSessionSettings extends AiSessionSettings {");
        RawUsage fromSubclassEnumerationPass = new RawUsage(
                "/project/AiModelSessionSettings.java", 42, 5,
                "public class AiModelSessionSettings extends AiSessionSettings {");

        List<String> lines = FindUsagesProvider.dedupe(List.of(fromReferenceFinderPass, fromSubclassEnumerationPass), "AiSessionSettings");

        assertEquals(1, lines.size(), "two rendered copies of the same single target occurrence must collapse to one line");
        assertEquals("/project/AiModelSessionSettings.java:5  →  "
                + "public class AiModelSessionSettings extends AiSessionSettings {", lines.get(0));
    }

    @Test
    void dedupe_keepsTwoGenuinelyDistinctOccurrencesOnTheSameLine() {
        // The legitimate case ruled out during live verification: `Foo x = new Foo();` — two real
        // occurrences of "Foo" on one line, at two different offsets. Must NOT be collapsed.
        RawUsage declaredType = new RawUsage(
                "/project/Caller.java", 100, 10, "CodexSessionSettings settings = new CodexSessionSettings();");
        RawUsage constructorCall = new RawUsage(
                "/project/Caller.java", 121, 10, "CodexSessionSettings settings = new CodexSessionSettings();");

        List<String> lines = FindUsagesProvider.dedupe(List.of(declaredType, constructorCall), "CodexSessionSettings");

        assertEquals(2, lines.size(), "two distinct offsets on the same line must both survive");
    }

    @Test
    void dedupe_keepsDistinctPositionsAcrossDifferentFiles() {
        RawUsage inFileA = new RawUsage("/project/A.java", 5, 1, "import Foo;");
        RawUsage inFileB = new RawUsage("/project/B.java", 5, 1, "import Foo;");

        List<String> lines = FindUsagesProvider.dedupe(List.of(inFileA, inFileB), "Foo");

        assertEquals(2, lines.size(), "the same offset in two different files must not be treated as one position");
    }

    @Test
    void dedupe_neverCollapsesPositionlessEntries() {
        RawUsage noPositionA = new RawUsage("/project/Weird.java", -1, 1, "some display text");
        RawUsage noPositionB = new RawUsage("/project/Weird.java", -1, 1, "some display text");

        List<String> lines = FindUsagesProvider.dedupe(List.of(noPositionA, noPositionB), "Foo");

        assertEquals(2, lines.size(), "position-less elements have nothing to dedup against and must both survive");
    }
}

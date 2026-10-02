package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The part of OrganiseImports that can be decided without a live IDE: which imports are provably unused, the
 * order the rest are written in, and the message the tool returns.
 */
class ImportOrganisePlannerTest {

    private static ImportOrganisePlanner.ImportDecl single(String name) {
        return new ImportOrganisePlanner.ImportDecl(name, false, 0, name, null, null);
    }

    private static ImportOrganisePlanner.ImportDecl single(String name, int group) {
        return new ImportOrganisePlanner.ImportDecl(name, false, group, name, null, null);
    }

    private static ImportOrganisePlanner.ImportDecl unresolved(String name) {
        return new ImportOrganisePlanner.ImportDecl(name, false, 0, null, null, null);
    }

    private static ImportOrganisePlanner.ImportDecl onDemand(String qualifier, Set<String> owners, Set<String> members) {
        return new ImportOrganisePlanner.ImportDecl(qualifier + ".*", false, 0, null, owners, members);
    }

    private static ImportOrganisePlanner.ImportDecl staticSingle(String name, Set<String> owners) {
        return new ImportOrganisePlanner.ImportDecl(name, true, 0, null, owners, null);
    }

    private static ImportOrganisePlanner.ImportDecl staticOnDemand(String qualifier, Set<String> owners, Set<String> members) {
        return new ImportOrganisePlanner.ImportDecl(qualifier + ".*", true, 0, null, owners, members);
    }

    private static ImportOrganisePlanner.Usage usage(Set<String> typeRefs, Set<String> staticRefs, Set<String> names,
                                                     Set<String> docNames, boolean hasErrors) {
        return new ImportOrganisePlanner.Usage(typeRefs, staticRefs, names, docNames, hasErrors);
    }

    private static ImportOrganisePlanner.Usage types(String... qualifiedNames) {
        Set<String> refs = Set.of(qualifiedNames);
        Set<String> names = new java.util.HashSet<>();
        for (String qualified : qualifiedNames) {
            names.add(qualified.substring(qualified.lastIndexOf('.') + 1));
        }
        return usage(refs, Set.of(), names, Set.of(), false);
    }

    private static List<String> names(List<ImportOrganisePlanner.ImportDecl> decls) {
        return decls.stream().map(ImportOrganisePlanner.ImportDecl::name).toList();
    }

    @Test
    void aSingleImportTheCodeNamesIsKeptAndOneNothingNamesIsRemoved() {
        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(
                List.of(single("java.io.File"), single("java.util.List"), single("java.util.Map")),
                types("java.util.List"));

        assertEquals(List.of("java.util.List"), names(plan.kept()));
        assertEquals(List.of("java.io.File", "java.util.Map"), names(plan.removed()));
        assertFalse(plan.reordered());
    }

    @Test
    void aDuplicateImportIsRemovedAndTheFirstCopyKept() {
        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(
                List.of(single("java.util.List"), single("java.util.List")), types("java.util.List"));

        assertEquals(List.of("java.util.List"), names(plan.kept()));
        assertEquals(List.of("java.util.List"), names(plan.removed()));
    }

    @Test
    void anImportUsedOnlyByAJavadocReferenceIsKept() {
        ImportOrganisePlanner.Usage onlyJavadoc = usage(Set.of(), Set.of(), Set.of(), Set.of("Deque"), false);

        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(
                List.of(single("java.util.Deque"), single("java.util.Map")), onlyJavadoc);

        assertEquals(List.of("java.util.Deque"), names(plan.kept()));
        assertEquals(List.of("java.util.Map"), names(plan.removed()));
    }

    @Test
    void anOnDemandImportUsedOnlyByAJavadocReferenceIsKept() {
        ImportOrganisePlanner.ImportDecl star = onDemand("java.util", Set.of("java.util"), Set.of("List", "Map"));
        ImportOrganisePlanner.Usage onlyJavadoc = usage(Set.of(), Set.of(), Set.of(), Set.of("List"), false);

        assertTrue(ImportOrganisePlanner.plan(List.of(star), onlyJavadoc).removed().isEmpty());
        assertEquals(List.of(star.name()),
                names(ImportOrganisePlanner.plan(List.of(star), usage(Set.of(), Set.of(), Set.of(), Set.of("Other"), false))
                        .removed()));
    }

    @Test
    void anOnDemandImportIsRemovedOnlyWhenNoTypeFromItsPackageIsUsed() {
        ImportOrganisePlanner.ImportDecl util = onDemand("java.util", Set.of("java.util"), Set.of("List", "Map"));
        ImportOrganisePlanner.ImportDecl io = onDemand("java.io", Set.of("java.io"), Set.of("File"));

        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(List.of(io, util), types("java.util.Map"));

        assertEquals(List.of("java.util.*"), names(plan.kept()));
        assertEquals(List.of("java.io.*"), names(plan.removed()));
    }

    @Test
    void aNestedTypeIsNotCoveredByItsOuterTypesPackageImport() {
        ImportOrganisePlanner.ImportDecl util = onDemand("java.util", Set.of("java.util"), Set.of("Map"));

        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(List.of(util), types("java.util.Map.Entry"));

        assertEquals(List.of("java.util.*"), names(plan.removed()));
    }

    @Test
    void anOnDemandImportOfATypeIsUsedWhenAnInheritedNestedTypeIsNamed() {
        // import com.acme.Sub.*; the code names Inner, which Super declares and Sub inherits.
        ImportOrganisePlanner.ImportDecl star = onDemand("com.acme.Sub", Set.of("com.acme.Sub", "com.acme.Super"),
                Set.of("Inner"));

        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(List.of(star), types("com.acme.Super.Inner"));

        assertEquals(List.of("com.acme.Sub.*"), names(plan.kept()));
    }

    @Test
    void anImportThatCannotBeResolvedIsKept() {
        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(
                List.of(unresolved("com.acme.Gone"), onDemand("com.acme", null, null)), types("java.util.List"));

        assertEquals(List.of("com.acme.*", "com.acme.Gone"), names(plan.kept()));
        assertTrue(plan.removed().isEmpty());
    }

    @Test
    void aStaticImportIsUsedWhenAMemberOfItsOwnerIsNamed() {
        Set<String> owners = Set.of("org.junit.Assert", "java.lang.Object");
        ImportOrganisePlanner.ImportDecl assertTrue = staticSingle("org.junit.Assert.assertTrue", owners);
        ImportOrganisePlanner.ImportDecl assertFalse = staticSingle("org.junit.Assert.assertFalse", owners);

        ImportOrganisePlanner.Usage usage = usage(Set.of(), Set.of("org.junit.Assert#assertTrue"), Set.of("assertTrue"),
                Set.of(), false);
        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(List.of(assertTrue, assertFalse), usage);

        assertEquals(List.of("org.junit.Assert.assertTrue"), names(plan.kept()));
        assertEquals(List.of("org.junit.Assert.assertFalse"), names(plan.removed()));
    }

    @Test
    void aStaticImportIsNotUsedByAMemberOfTheSameNameFromAnotherOwner() {
        ImportOrganisePlanner.ImportDecl max = staticSingle("java.lang.Math.max", Set.of("java.lang.Math", "java.lang.Object"));
        ImportOrganisePlanner.Usage ownMax = usage(Set.of(), Set.of("com.acme.Mine#max"), Set.of("max"), Set.of(), false);

        assertEquals(List.of("java.lang.Math.max"), names(ImportOrganisePlanner.plan(List.of(max), ownMax).removed()));
    }

    @Test
    void aStaticImportOfAnInheritedMemberIsUsedThroughTheSubtypesOwners() {
        // import static com.acme.Sub.LIMIT; LIMIT is declared on Super, so the reference's owner is Super.
        ImportOrganisePlanner.ImportDecl limit = staticSingle("com.acme.Sub.LIMIT", Set.of("com.acme.Sub", "com.acme.Super"));
        ImportOrganisePlanner.Usage usage = usage(Set.of(), Set.of("com.acme.Super#LIMIT"), Set.of("LIMIT"), Set.of(), false);

        assertTrue(ImportOrganisePlanner.plan(List.of(limit), usage).removed().isEmpty());
    }

    @Test
    void aStaticOnDemandImportIsUsedByAnyStaticMemberOrNestedTypeOfItsOwner() {
        ImportOrganisePlanner.ImportDecl star = staticOnDemand("org.junit.Assert", Set.of("org.junit.Assert"),
                Set.of("assertTrue"));

        assertTrue(ImportOrganisePlanner.plan(List.of(star),
                usage(Set.of(), Set.of("org.junit.Assert#assertTrue"), Set.of("assertTrue"), Set.of(), false))
                .removed().isEmpty());
        assertTrue(ImportOrganisePlanner.plan(List.of(star), types("org.junit.Assert.Nested")).removed().isEmpty());
        assertEquals(List.of("org.junit.Assert.*"),
                names(ImportOrganisePlanner.plan(List.of(star), types("java.util.List")).removed()));
    }

    @Test
    void aStaticImportUsedOnlyByAJavadocReferenceIsKept() {
        ImportOrganisePlanner.ImportDecl max = staticSingle("java.lang.Math.max", Set.of("java.lang.Math"));
        ImportOrganisePlanner.Usage usage = usage(Set.of(), Set.of(), Set.of(), Set.of("max"), false);

        assertTrue(ImportOrganisePlanner.plan(List.of(max), usage).removed().isEmpty());
    }

    @Test
    void withCompileErrorsNoOnDemandOrUnresolvedImportIsRemoved() {
        ImportOrganisePlanner.ImportDecl star = onDemand("java.io", Set.of("java.io"), Set.of("File"));
        ImportOrganisePlanner.ImportDecl staticStar = staticOnDemand("java.lang.Math", Set.of("java.lang.Math"), Set.of("max"));
        ImportOrganisePlanner.ImportDecl gone = unresolved("com.acme.Gone");
        ImportOrganisePlanner.Usage broken = usage(Set.of(), Set.of(), Set.of("Missing"), Set.of(), true);

        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(List.of(star, staticStar, gone), broken);

        assertTrue(plan.removed().isEmpty());
        assertEquals(3, plan.kept().size());
    }

    @Test
    void withCompileErrorsASingleImportIsRemovedOnlyWhenItsNameAppearsNowhere() {
        ImportOrganisePlanner.ImportDecl file = single("java.io.File");
        ImportOrganisePlanner.ImportDecl map = single("java.util.Map");
        // Map is unresolved in the code, so no type ref names it, but the identifier is there.
        ImportOrganisePlanner.Usage broken = usage(Set.of(), Set.of(), Set.of("Map"), Set.of(), true);

        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(List.of(file, map), broken);

        assertEquals(List.of("java.util.Map"), names(plan.kept()));
        assertEquals(List.of("java.io.File"), names(plan.removed()));
    }

    @Test
    void withCompileErrorsAWordInTheFileTextKeepsAnImportEvenWhenNoIdentifierWasScanned() {
        // A parse error can leave Map without an identifier tree; the text still has the word.
        String text = "import java.util.Map;\nimport java.io.File;\nclass A { Map<String, String> m = ; }\n";
        List<int[]> importSpans = List.of(new int[]{0, 21}, new int[]{22, 42});
        Set<String> names = ImportOrganisePlanner.namesWithTextWords(Set.of(), text, importSpans);
        ImportOrganisePlanner.Usage broken = usage(Set.of(), Set.of(), names, Set.of(), true);

        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(
                List.of(single("java.util.Map"), single("java.io.File")), broken);

        assertEquals(List.of("java.util.Map"), names(plan.kept()));
        assertEquals(List.of("java.io.File"), names(plan.removed()));
    }

    @Test
    void anImportStatementDoesNotCountAsItsOwnUseInTheFileText() {
        String text = "import java.util.Map;\nclass A { }\n";

        Set<String> names = ImportOrganisePlanner.namesWithTextWords(Set.of(), text, List.of(new int[]{0, 21}));

        assertFalse(names.contains("Map"));
        assertTrue(names.contains("class"));
    }

    @Test
    void textWordsAreWholeWordsAndOutOfRangeSpansAreClamped() {
        String text = "class A { HashMapper x; }";

        Set<String> names = ImportOrganisePlanner.namesWithTextWords(Set.of("seen"), text, List.of(new int[]{-5, 999}));

        assertEquals(Set.of("seen"), names, "the whole text was masked, so only the scanned name remains");
        assertTrue(ImportOrganisePlanner.namesWithTextWords(Set.of(), text, List.of()).contains("HashMapper"));
        assertFalse(ImportOrganisePlanner.namesWithTextWords(Set.of(), text, List.of()).contains("Map"));
    }

    @Test
    void withCompileErrorsAStaticImportWhoseNameAppearsIsKept() {
        ImportOrganisePlanner.ImportDecl max = staticSingle("java.lang.Math.max", Set.of("java.lang.Math"));
        ImportOrganisePlanner.ImportDecl min = staticSingle("java.lang.Math.min", Set.of("java.lang.Math"));
        ImportOrganisePlanner.Usage broken = usage(Set.of(), Set.of(), Set.of("max"), Set.of(), true);

        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(List.of(max, min), broken);

        assertEquals(List.of("java.lang.Math.max"), names(plan.kept()));
        assertEquals(List.of("java.lang.Math.min"), names(plan.removed()));
    }

    @Test
    void withoutCompileErrorsAnImportWhoseNameIsOnlyAnUnrelatedIdentifierIsRemoved() {
        // A local variable called List does not use java.util.List.
        ImportOrganisePlanner.Usage shadowed = usage(Set.of(), Set.of(), Set.of("List"), Set.of(), false);

        assertEquals(List.of("java.util.List"),
                names(ImportOrganisePlanner.plan(List.of(single("java.util.List")), shadowed).removed()));
    }

    @Test
    void keptImportsAreOrderedByGroupThenQualifiedName() {
        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(
                List.of(single("org.junit.Test", 2), single("java.util.Map", 0), single("javax.swing.JButton", 1),
                        single("java.util.List", 0), single("java.util.concurrent.Callable", 0)),
                types("org.junit.Test", "java.util.Map", "javax.swing.JButton", "java.util.List",
                        "java.util.concurrent.Callable"));

        assertEquals(List.of("java.util.List", "java.util.Map", "java.util.concurrent.Callable", "javax.swing.JButton",
                "org.junit.Test"), names(plan.kept()));
        assertTrue(plan.reordered());
    }

    @Test
    void anAlreadySortedListIsNotReorderedEvenWhenAnImportBetweenIsRemoved() {
        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(
                List.of(single("java.io.File"), single("java.util.List"), single("java.util.Map")),
                types("java.io.File", "java.util.Map"));

        assertEquals(List.of("java.io.File", "java.util.Map"), names(plan.kept()));
        assertFalse(plan.reordered());
        assertTrue(plan.changesAnything());
    }

    @Test
    void staticAndNonStaticImportsInTheSameGroupAreOrderedByNameAlone() {
        ImportOrganisePlanner.ImportDecl assertions = new ImportOrganisePlanner.ImportDecl(
                "org.junit.jupiter.api.Assertions.*", true, 0, null, Set.of("org.junit.jupiter.api.Assertions"), null);
        ImportOrganisePlanner.ImportDecl test = single("org.junit.jupiter.api.Test");

        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(List.of(test, assertions),
                usage(Set.of("org.junit.jupiter.api.Test"), Set.of("org.junit.jupiter.api.Assertions#assertEquals"),
                        Set.of("Test", "assertEquals"), Set.of(), false));

        assertEquals(List.of("org.junit.jupiter.api.Assertions.*", "org.junit.jupiter.api.Test"), names(plan.kept()));
        assertTrue(plan.reordered());
    }

    @Test
    void anAlreadyOrganisedFileSaysSo() {
        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(
                List.of(single("java.util.List"), single("java.util.Map")), types("java.util.List", "java.util.Map"));

        assertFalse(plan.changesAnything());
        assertEquals("Imports already organised.", ImportOrganisePlanner.format(plan, false));
    }

    @Test
    void theMessageNamesWhatWasRemovedAndWhetherTheOrderChanged() {
        ImportOrganisePlanner.Plan removedOnly = ImportOrganisePlanner.plan(
                List.of(single("java.util.Map"), single("java.io.File"), single("java.util.List")),
                types("java.util.List"));
        assertEquals("Removed 2 unused imports: java.util.Map, java.io.File.", ImportOrganisePlanner.format(removedOnly, false));

        ImportOrganisePlanner.Plan one = ImportOrganisePlanner.plan(List.of(single("java.io.File")), types());
        assertEquals("Removed 1 unused import: java.io.File.", ImportOrganisePlanner.format(one, false));

        ImportOrganisePlanner.Plan both = ImportOrganisePlanner.plan(
                List.of(single("java.util.Map"), single("java.io.File")), types("java.util.Map", "java.io.File"));
        assertEquals("Imports reordered.", ImportOrganisePlanner.format(both, false));

        ImportOrganisePlanner.Plan removedAndSorted = ImportOrganisePlanner.plan(
                List.of(single("java.util.Map"), single("java.io.File"), single("java.awt.List")), types("java.util.Map", "java.io.File"));
        assertEquals("Removed 1 unused import: java.awt.List. Imports reordered.",
                ImportOrganisePlanner.format(removedAndSorted, false));
    }

    @Test
    void aRemovedStaticImportIsNamedAsStatic() {
        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(
                List.of(staticSingle("java.lang.Math.max", Set.of("java.lang.Math"))), types());

        assertEquals("Removed 1 unused import: static java.lang.Math.max.", ImportOrganisePlanner.format(plan, false));
    }

    @Test
    void aFileWithErrorsSaysOnlyProvablyUnusedImportsWereRemoved() {
        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(List.of(single("java.util.List")),
                usage(Set.of("java.util.List"), Set.of(), Set.of("List"), Set.of(), true));

        String message = ImportOrganisePlanner.format(plan, true);

        assertTrue(message.startsWith("Imports already organised."), message);
        assertTrue(message.contains("compile errors"), message);
        assertTrue(message.contains("provably unused"), message);
    }
}

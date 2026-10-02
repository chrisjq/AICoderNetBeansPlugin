package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The part of FixImports that can be decided without a live IDE: which candidates survive a use site, which
 * one ranks first, and what the tool tells the caller when some names resolve and others do not.
 */
class ImportCandidatePlannerTest {

    @Test
    void constructedUseDropsInterfacesAbstractTypesAnnotationsAndEnums() {
        ImportCandidatePlanner.Usage usage = ImportCandidatePlanner.Usage.ofConstructed();
        ImportCandidatePlanner.Candidate iface = type("java.util.List", ImportCandidatePlanner.TypeKind.INTERFACE, false, 1);
        ImportCandidatePlanner.Candidate abs = type("com.foo.Abs", ImportCandidatePlanner.TypeKind.CLASS, true, 0);
        ImportCandidatePlanner.Candidate concrete = type("java.util.ArrayList", ImportCandidatePlanner.TypeKind.CLASS, false, 1);
        ImportCandidatePlanner.Candidate record = type("com.foo.Rec", ImportCandidatePlanner.TypeKind.RECORD, false, 0);
        ImportCandidatePlanner.Candidate enumeration = type("com.foo.Kind", ImportCandidatePlanner.TypeKind.ENUM, false, 0);
        ImportCandidatePlanner.Candidate annotation = type("com.foo.Ann", ImportCandidatePlanner.TypeKind.ANNOTATION, false, 0);
        assertEquals(List.of(concrete, record),
                ImportCandidatePlanner.filterByUsage(usage, List.of(iface, abs, concrete, record, enumeration, annotation)));
    }

    @Test
    void genericUseDropsNonGenericTypesAndTheWrongTypeArgumentCount() {
        ImportCandidatePlanner.Usage usage = ImportCandidatePlanner.Usage.generic(1);
        ImportCandidatePlanner.Candidate raw = type("com.foo.Raw", ImportCandidatePlanner.TypeKind.CLASS, false, 0);
        ImportCandidatePlanner.Candidate one = type("java.util.List", ImportCandidatePlanner.TypeKind.INTERFACE, false, 1);
        ImportCandidatePlanner.Candidate two = type("java.util.Map", ImportCandidatePlanner.TypeKind.INTERFACE, false, 2);
        ImportCandidatePlanner.Candidate unknown = type("com.foo.Mystery", ImportCandidatePlanner.TypeKind.UNKNOWN, false, null);
        assertEquals(List.of(one, unknown), ImportCandidatePlanner.filterByUsage(usage, List.of(raw, one, two, unknown)));
    }

    @Test
    void contradictoryTypeArgumentCountsDropEveryone() {
        ImportCandidatePlanner.Usage usage = ImportCandidatePlanner.Usage.generic(1).merge(ImportCandidatePlanner.Usage.generic(2));
        ImportCandidatePlanner.Candidate one = type("java.util.List", ImportCandidatePlanner.TypeKind.INTERFACE, false, 1);
        ImportCandidatePlanner.Candidate two = type("java.util.Map", ImportCandidatePlanner.TypeKind.INTERFACE, false, 2);
        assertEquals(List.of(), ImportCandidatePlanner.filterByUsage(usage, List.of(one, two)));
    }

    @Test
    void annotationUseKeepsOnlyAnnotationTypes() {
        ImportCandidatePlanner.Usage usage = ImportCandidatePlanner.Usage.annotationUse();
        ImportCandidatePlanner.Candidate annotation = type("com.foo.Ann", ImportCandidatePlanner.TypeKind.ANNOTATION, false, 0);
        ImportCandidatePlanner.Candidate clazz = type("com.foo.Foo", ImportCandidatePlanner.TypeKind.CLASS, false, 0);
        ImportCandidatePlanner.Candidate unknown = type("com.foo.Mystery", ImportCandidatePlanner.TypeKind.UNKNOWN, false, null);
        assertEquals(List.of(annotation, unknown),
                ImportCandidatePlanner.filterByUsage(usage, List.of(annotation, clazz, unknown)));
    }

    @Test
    void extendsKeepsClassesIncludingAbstractOnes() {
        ImportCandidatePlanner.Usage usage = ImportCandidatePlanner.Usage.ofExtended();
        ImportCandidatePlanner.Candidate abs = type("com.foo.Base", ImportCandidatePlanner.TypeKind.CLASS, true, 0);
        ImportCandidatePlanner.Candidate iface = type("com.foo.Api", ImportCandidatePlanner.TypeKind.INTERFACE, false, 0);
        ImportCandidatePlanner.Candidate record = type("com.foo.Rec", ImportCandidatePlanner.TypeKind.RECORD, false, 0);
        ImportCandidatePlanner.Candidate enumeration = type("com.foo.Kind", ImportCandidatePlanner.TypeKind.ENUM, false, 0);
        assertEquals(List.of(abs), ImportCandidatePlanner.filterByUsage(usage, List.of(abs, iface, record, enumeration)));
    }

    @Test
    void implementsKeepsInterfaces() {
        ImportCandidatePlanner.Usage usage = ImportCandidatePlanner.Usage.ofImplemented();
        ImportCandidatePlanner.Candidate iface = type("com.foo.Api", ImportCandidatePlanner.TypeKind.INTERFACE, false, 0);
        ImportCandidatePlanner.Candidate clazz = type("com.foo.Foo", ImportCandidatePlanner.TypeKind.CLASS, false, 0);
        assertEquals(List.of(iface), ImportCandidatePlanner.filterByUsage(usage, List.of(iface, clazz)));
    }

    @Test
    void memberNameKeepsATypeThatDeclaresItAndDropsOneThatDoesNot() {
        ImportCandidatePlanner.Usage usage = ImportCandidatePlanner.Usage.member("Inner");
        ImportCandidatePlanner.Candidate has = type("com.foo.Outer", ImportCandidatePlanner.TypeKind.CLASS, false, 0, true, Set.of("Inner"));
        ImportCandidatePlanner.Candidate lacks = type("com.foo.Other", ImportCandidatePlanner.TypeKind.CLASS, false, 0, true, Set.of());
        ImportCandidatePlanner.Candidate unknown = type("com.foo.Unknown", ImportCandidatePlanner.TypeKind.CLASS, false, 0, false, Set.of());
        assertEquals(List.of(has, unknown), ImportCandidatePlanner.filterByUsage(usage, List.of(has, lacks, unknown)));
    }

    @Test
    void importedPackageRanksAheadOfProjectSourceWhichRanksAheadOfALibrary() {
        ImportCandidatePlanner.FileFit fit = new ImportCandidatePlanner.FileFit("com.example", Set.of("java.util"));
        ImportCandidatePlanner.Candidate library = lib("java.awt.List");
        ImportCandidatePlanner.Candidate project = type("com.mine.List", ImportCandidatePlanner.TypeKind.CLASS, false, 1, false, Set.of(), true);
        ImportCandidatePlanner.Candidate imported = lib("java.util.List");
        List<ImportCandidatePlanner.Ranked> ranked = ImportCandidatePlanner.rank(List.of(library, project, imported), fit);
        assertEquals("java.util.List", ranked.get(0).qualifiedName());
        assertEquals("package already imported", ranked.get(0).reason());
        assertEquals("com.mine.List", ranked.get(1).qualifiedName());
        assertEquals("project source", ranked.get(1).reason());
        assertEquals("java.awt.List", ranked.get(2).qualifiedName());
        assertNull(ranked.get(2).reason());
    }

    @Test
    void ownPackageAndAnImportedPackageTieSoPickBestDoesNotChoose() {
        ImportCandidatePlanner.FileFit fit = new ImportCandidatePlanner.FileFit("com.example", Set.of("java.util"));
        ImportCandidatePlanner.Decision decision = ImportCandidatePlanner.decide("List", ImportCandidatePlanner.Usage.none(),
                List.of(lib("java.util.List"), lib("com.example.List")), fit, true);
        assertEquals(ImportCandidatePlanner.Status.AMBIGUOUS, decision.status());
        assertEquals(List.of(), ImportCandidatePlanner.importsToApply(List.of(decision)));
        assertTrue(ImportCandidatePlanner.format(List.of(decision), true).contains("tied"));
    }

    @Test
    void projectSourceSelectsTheOwnPackageTypeAndThatNeedsNoImport() {
        ImportCandidatePlanner.FileFit fit = new ImportCandidatePlanner.FileFit("com.example", Set.of("java.util"));
        ImportCandidatePlanner.Candidate own = type("com.example.List", ImportCandidatePlanner.TypeKind.CLASS, false, 1, false, Set.of(), true);
        ImportCandidatePlanner.Decision decision = ImportCandidatePlanner.decide("List", ImportCandidatePlanner.Usage.none(),
                List.of(lib("java.util.List"), own), fit, true);
        assertEquals(ImportCandidatePlanner.Status.NOT_NEEDED, decision.status());
        assertEquals(List.of(), ImportCandidatePlanner.importsToApply(List.of(decision)));
    }

    @Test
    void oneCandidateIsImportedAndSeveralAreNot() {
        ImportCandidatePlanner.FileFit fit = new ImportCandidatePlanner.FileFit("com.example", Set.of());
        ImportCandidatePlanner.Decision single = ImportCandidatePlanner.decide("ArrayList", ImportCandidatePlanner.Usage.none(),
                List.of(lib("java.util.ArrayList")), fit, false);
        ImportCandidatePlanner.Decision several = ImportCandidatePlanner.decide("List", ImportCandidatePlanner.Usage.none(),
                List.of(lib("java.util.List"), lib("java.awt.List")), fit, false);
        assertEquals(ImportCandidatePlanner.Status.ADDED, single.status());
        assertEquals("java.util.ArrayList", single.importedQualifiedName());
        assertEquals(ImportCandidatePlanner.Status.AMBIGUOUS, several.status());
        assertEquals(List.of("java.util.ArrayList"), ImportCandidatePlanner.importsToApply(List.of(single, several)));
        String message = ImportCandidatePlanner.format(List.of(several), true);
        assertTrue(message.contains("java.util.List"));
        assertTrue(message.contains("java.awt.List"));
        assertTrue(message.contains("ApplyEdit"));
        assertFalse(message.contains("picked best"));
    }

    @Test
    void noCandidateIsReportedUnresolved() {
        ImportCandidatePlanner.Decision decision = ImportCandidatePlanner.decide("Frobnicator", ImportCandidatePlanner.Usage.none(),
                List.of(), new ImportCandidatePlanner.FileFit("com.example", Set.of()), false);
        assertEquals(ImportCandidatePlanner.Status.UNRESOLVED, decision.status());
        assertEquals("no matching type on the classpath", decision.unresolvedReason());
        assertFalse(decision.hadCandidates());
    }

    @Test
    void pickBestFalseDoesNotImportAUniqueTop() {
        ImportCandidatePlanner.FileFit fit = new ImportCandidatePlanner.FileFit("com.example", Set.of("java.util"));
        ImportCandidatePlanner.Decision decision = ImportCandidatePlanner.decide("List", ImportCandidatePlanner.Usage.none(),
                List.of(lib("java.awt.List"), lib("java.util.List")), fit, false);
        assertEquals(ImportCandidatePlanner.Status.AMBIGUOUS, decision.status());
        assertEquals(List.of(), ImportCandidatePlanner.importsToApply(List.of(decision)));
        String message = ImportCandidatePlanner.format(List.of(decision), true);
        assertTrue(message.contains("java.util.List (package already imported)"));
        assertTrue(message.contains("java.awt.List"));
        assertTrue(message.contains("pickBest=true"));
        assertFalse(message.contains("picked best"));
    }

    @Test
    void pickBestImportsTheHigherScoreNotTheEarlierIndex() {
        ImportCandidatePlanner.FileFit fit = new ImportCandidatePlanner.FileFit("com.example", Set.of("java.util"));
        ImportCandidatePlanner.Candidate earlierInTheIndex = lib("java.awt.List");
        ImportCandidatePlanner.Candidate preferred = lib("java.util.List");
        ImportCandidatePlanner.Decision decision = ImportCandidatePlanner.decide("List", ImportCandidatePlanner.Usage.none(),
                List.of(earlierInTheIndex, preferred), fit, true);
        assertEquals(ImportCandidatePlanner.Status.PICKED, decision.status());
        assertEquals("java.util.List", decision.importedQualifiedName());
        assertTrue(ImportCandidatePlanner.format(List.of(decision), true).contains("List -> java.util.List (picked best of 2)"));
        assertEquals(List.of("java.util.List"), ImportCandidatePlanner.importsToApply(List.of(decision)));

        ImportCandidatePlanner.Decision reversed = ImportCandidatePlanner.decide("List", ImportCandidatePlanner.Usage.none(),
                List.of(preferred, earlierInTheIndex), fit, true);
        assertEquals("java.util.List", reversed.importedQualifiedName());
    }

    @Test
    void tiedCandidatesStayUnimportedEvenWhenPickBestIsTrue() {
        ImportCandidatePlanner.FileFit fit = new ImportCandidatePlanner.FileFit("com.example", Set.of());
        ImportCandidatePlanner.Decision decision = ImportCandidatePlanner.decide("List", ImportCandidatePlanner.Usage.none(),
                List.of(lib("java.util.List"), lib("java.awt.List")), fit, true);
        assertEquals(ImportCandidatePlanner.Status.AMBIGUOUS, decision.status());
        assertEquals(List.of(), ImportCandidatePlanner.importsToApply(List.of(decision)));
        String message = ImportCandidatePlanner.format(List.of(decision), true);
        assertTrue(message.indexOf("java.awt.List") < message.indexOf("java.util.List"));
        assertTrue(message.contains("tied"));
        assertFalse(message.contains("picked best"));
    }

    @Test
    void mixAppliesTheResolvedNamesAndListsTheRest() {
        ImportCandidatePlanner.FileFit fit = new ImportCandidatePlanner.FileFit("com.example", Set.of("java.util"));
        ImportCandidatePlanner.Decision map = ImportCandidatePlanner.decide("Map", ImportCandidatePlanner.Usage.none(),
                List.of(lib("java.util.Map")), fit, false);
        ImportCandidatePlanner.Decision arrayList = ImportCandidatePlanner.decide("ArrayList", ImportCandidatePlanner.Usage.none(),
                List.of(lib("java.util.ArrayList")), fit, false);
        ImportCandidatePlanner.Decision bar = ImportCandidatePlanner.decide("Bar", ImportCandidatePlanner.Usage.none(),
                List.of(lib("com.foo.Bar")), fit, false);
        ImportCandidatePlanner.Decision list = ImportCandidatePlanner.decide("List", ImportCandidatePlanner.Usage.none(),
                List.of(lib("java.util.List"), lib("java.awt.List")), fit, false);
        ImportCandidatePlanner.Decision missing = ImportCandidatePlanner.decide("Frobnicator", ImportCandidatePlanner.Usage.none(),
                List.of(), fit, false);
        List<ImportCandidatePlanner.Decision> all = List.of(map, arrayList, bar, list, missing);
        assertEquals(List.of("java.util.Map", "java.util.ArrayList", "com.foo.Bar"), ImportCandidatePlanner.importsToApply(all));
        assertEquals("Added 3 imports: java.util.Map, java.util.ArrayList, com.foo.Bar. "
                     + "Could not decide 1: List \u2014 java.util.List (package already imported), java.awt.List "
                     + "(add the one you mean with ApplyEdit, or call again with pickBest=true). "
                     + "Unresolved 1: Frobnicator (no matching type on the classpath).",
                ImportCandidatePlanner.format(all, true));
    }

    @Test
    void pickedNameIsAppliedAlongsideAnUnambiguousOne() {
        ImportCandidatePlanner.FileFit fit = new ImportCandidatePlanner.FileFit("com.example", Set.of("java.util"));
        ImportCandidatePlanner.Decision map = ImportCandidatePlanner.decide("Map", ImportCandidatePlanner.Usage.none(),
                List.of(lib("java.util.Map")), fit, true);
        ImportCandidatePlanner.Decision list = ImportCandidatePlanner.decide("List", ImportCandidatePlanner.Usage.none(),
                List.of(lib("java.awt.List"), lib("java.util.List")), fit, true);
        assertEquals(List.of("java.util.Map", "java.util.List"), ImportCandidatePlanner.importsToApply(List.of(map, list)));
        String message = ImportCandidatePlanner.format(List.of(map, list), true);
        assertTrue(message.contains("Added 2 imports: java.util.Map, java.util.List."));
        assertTrue(message.contains("List -> java.util.List (picked best of 2)."));
    }

    @Test
    void ownPackageAndStarImportAreNotReportedAsAdded() {
        ImportCandidatePlanner.Candidate own = lib("com.example.Widget");
        ImportCandidatePlanner.Decision samePackage = ImportCandidatePlanner.decide("Widget", ImportCandidatePlanner.Usage.none(),
                List.of(own), new ImportCandidatePlanner.FileFit("com.example", Set.of()), false);
        assertEquals(ImportCandidatePlanner.Status.NOT_NEEDED, samePackage.status());
        assertEquals(List.of(), ImportCandidatePlanner.importsToApply(List.of(samePackage)));
        assertEquals("No missing imports.", ImportCandidatePlanner.format(List.of(samePackage), false));

        ImportCandidatePlanner.FileFit star = new ImportCandidatePlanner.FileFit("com.example", Set.of("java.util"), Set.of("java.util"));
        ImportCandidatePlanner.Decision covered = ImportCandidatePlanner.decide("List", ImportCandidatePlanner.Usage.none(),
                List.of(lib("java.util.List")), star, false);
        assertEquals(ImportCandidatePlanner.Status.NOT_NEEDED, covered.status());
        assertEquals("No missing imports.", ImportCandidatePlanner.format(List.of(covered), false));

        ImportCandidatePlanner.FileFit namedOnly = new ImportCandidatePlanner.FileFit("com.example", Set.of("java.util"));
        ImportCandidatePlanner.Decision stillMissing = ImportCandidatePlanner.decide("List", ImportCandidatePlanner.Usage.none(),
                List.of(lib("java.util.List")), namedOnly, false);
        assertEquals(ImportCandidatePlanner.Status.ADDED, stillMissing.status());
        assertEquals("java.util.List", stillMissing.importedQualifiedName());

        ImportCandidatePlanner.Decision bestAlreadyVisible = ImportCandidatePlanner.decide("List",
                ImportCandidatePlanner.Usage.none(),
                List.of(lib("java.util.List"), lib("java.awt.List")), star, true);
        assertEquals(ImportCandidatePlanner.Status.NOT_NEEDED, bestAlreadyVisible.status());
        assertEquals(List.of(), ImportCandidatePlanner.importsToApply(List.of(bestAlreadyVisible)));

        ImportCandidatePlanner.FileFit bothVisible = new ImportCandidatePlanner.FileFit(
                "com.example", Set.of("java.util"), Set.of("java.util"));
        ImportCandidatePlanner.Decision stillAmbiguous = ImportCandidatePlanner.decide("Widget",
                ImportCandidatePlanner.Usage.none(),
                List.of(lib("com.example.Widget"), lib("java.util.Widget")), bothVisible, true);
        assertEquals(ImportCandidatePlanner.Status.AMBIGUOUS, stillAmbiguous.status());
    }

    @Test
    void errorsWithoutAReadableTypeNameAreNotReportedAsNoMissingImports() {
        assertEquals("The file has compile errors, but no unresolved type name could be read from them.",
                ImportCandidatePlanner.format(List.of(), true));
        assertEquals("No missing imports.", ImportCandidatePlanner.format(List.of(), false));
    }

    /**
     * {@code import com.acme.Outer.Helper} belongs to package {@code com.acme}. Recording
     * {@code com.acme.Outer} would let a project-source {@code other.Widget} outrank library
     * {@code com.acme.Widget} when pickBest is true.
     */
    @Test
    void nestedTypeImportUsesTheEnclosingPackageSoPickBestPrefersItOverProjectSource() {
        assertEquals("com.acme", ImportCandidatePlanner.importedPackage("com.acme", "com.acme.Outer.Helper", false));
        assertEquals("com.acme", ImportCandidatePlanner.importedPackage("com.acme", "com.acme.Outer.Helper.FOO", true));
        assertEquals("com.acme.Outer", ImportCandidatePlanner.importedPackage(null, "com.acme.Outer.Helper", false));
        assertEquals("com.acme", ImportCandidatePlanner.importedPackage(null, "com.acme.Widget", false));
        assertEquals("com.acme", ImportCandidatePlanner.importedPackage("  ", "com.acme.Widget", false));
        assertEquals("com.acme", ImportCandidatePlanner.importedPackage(null, "com.acme.*", false));
        assertEquals("com.acme", ImportCandidatePlanner.importedPackage(null, "com.acme.Outer.FOO", true));
        assertEquals("com.acme", ImportCandidatePlanner.importedPackage(null, "com.acme.Outer.*", true));
        assertNull(ImportCandidatePlanner.importedPackage(null, "Widget", false));

        String pkg = ImportCandidatePlanner.importedPackage("com.acme", "com.acme.Outer.Helper", false);
        ImportCandidatePlanner.FileFit fit = new ImportCandidatePlanner.FileFit("com.example", Set.of(pkg));
        ImportCandidatePlanner.Candidate project = type("other.Widget", ImportCandidatePlanner.TypeKind.CLASS,
                false, 0, false, Set.of(), true);
        ImportCandidatePlanner.Decision decision = ImportCandidatePlanner.decide("Widget", ImportCandidatePlanner.Usage.none(),
                List.of(project, lib("com.acme.Widget")), fit, true);
        assertEquals(ImportCandidatePlanner.Status.PICKED, decision.status());
        assertEquals("com.acme.Widget", decision.importedQualifiedName());
    }

    private static ImportCandidatePlanner.Candidate lib(String qualifiedName) {
        return type(qualifiedName, ImportCandidatePlanner.TypeKind.CLASS, false, 0, false, Set.of(), false);
    }

    private static ImportCandidatePlanner.Candidate type(String qualifiedName, ImportCandidatePlanner.TypeKind kind,
                                                         boolean abstractType, Integer typeParameterCount) {
        return type(qualifiedName, kind, abstractType, typeParameterCount, false, Set.of(), false);
    }

    private static ImportCandidatePlanner.Candidate type(String qualifiedName, ImportCandidatePlanner.TypeKind kind,
                                                         boolean abstractType, Integer typeParameterCount,
                                                         boolean memberNamesKnown, Set<String> memberNames) {
        return type(qualifiedName, kind, abstractType, typeParameterCount, memberNamesKnown, memberNames, false);
    }

    private static ImportCandidatePlanner.Candidate type(String qualifiedName, ImportCandidatePlanner.TypeKind kind,
                                                         boolean abstractType, Integer typeParameterCount,
                                                         boolean memberNamesKnown, Set<String> memberNames,
                                                         boolean projectSource) {
        int dot = qualifiedName.lastIndexOf('.');
        String pkg = dot < 0 ? "" : qualifiedName.substring(0, dot);
        return new ImportCandidatePlanner.Candidate(qualifiedName, pkg, kind, abstractType, typeParameterCount,
                memberNamesKnown, memberNames, projectSource);
    }
}

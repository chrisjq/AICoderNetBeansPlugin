package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Decisions in {@link HeadlessImportFixer} that do not need a live JavaSource: which diagnostic belongs to
 * this file, which diagnostic code is an unresolved type, which members count, and which planned imports
 * actually resolved.
 */
class HeadlessImportFixerTest {

    @Test
    void onlyAProvablyDifferentFileIsExcluded() {
        Path file = Path.of("/A.java");
        assertFalse(HeadlessImportFixer.sameFile(file, URI.create("file:///B.java")));
        assertTrue(HeadlessImportFixer.sameFile(file, URI.create("file:///A.java")));
        assertTrue(HeadlessImportFixer.sameFile(file, URI.create("file:/A.java")));
        assertTrue(HeadlessImportFixer.sameFile(Path.of("/tmp/A.java"), URI.create("file:///tmp/foo/../A.java")));
    }

    /**
     * The live failure: the diagnostic's source could not be identified and every error was dropped, so the
     * tool answered "No missing imports." on a file with four. An unidentifiable source must count.
     */
    @Test
    void anUnidentifiedSourceCountsAsThisFile() {
        Path file = Path.of("/A.java");
        assertTrue(HeadlessImportFixer.sameFile(file, null));
        assertTrue(HeadlessImportFixer.sameFile(null, URI.create("file:///A.java")));
        assertTrue(HeadlessImportFixer.sameFile(file, URI.create("nbjrt://A.java")));
        assertTrue(HeadlessImportFixer.sameFile(file, URI.create("jar:file:///lib.jar!/A.java")));
    }

    @Test
    void symlinkedPathsToTheSameFileMatch(@TempDir Path dir) throws Exception {
        Path real = Files.createFile(dir.resolve("A.java"));
        Path link;
        try {
            link = Files.createSymbolicLink(dir.resolve("link.java"), real);
        }
        catch (UnsupportedOperationException | IOException ex) {
            return;
        }
        assertTrue(HeadlessImportFixer.sameFile(real, link.toUri()));
        assertFalse(HeadlessImportFixer.sameFile(real, Files.createFile(dir.resolve("B.java")).toUri()));
    }

    private interface Middle extends java.util.function.Supplier<String> {
    }

    private static final class HiddenDiagnostic implements Middle {

        @Override
        public String get() {
            return "read";
        }
    }

    /**
     * The live failure behind "No missing imports.": NetBeans' diagnostics are a non-public class, so a
     * method looked up on the concrete class cannot be invoked from here. The method must come from the
     * public interface, found by name even when it is inherited through another interface.
     */
    @Test
    void publicMethodComesFromTheNamedInterfaceNotTheHiddenClass() throws Exception {
        Object hidden = new HiddenDiagnostic();
        Method get = HeadlessImportFixer.publicMethod(hidden, "java.util.function.Supplier", "get");
        assertEquals(java.util.function.Supplier.class, get.getDeclaringClass());
        assertEquals("read", get.invoke(hidden));
        assertThrows(NoSuchMethodException.class,
                () -> HeadlessImportFixer.publicMethod(hidden, "javax.tools.Diagnostic", "getKind"));
    }

    /**
     * The live result offered java.util.Arrays.ArrayList (a private nested class) next to
     * java.util.ArrayList. A type that cannot be seen from the file must never be a candidate.
     */
    @Test
    void privateOrPackageHiddenTypesAreNotImportable() {
        Set<Modifier> publicType = Set.of(Modifier.PUBLIC);
        Set<Modifier> privateNested = Set.of(Modifier.PRIVATE, Modifier.STATIC);
        Set<Modifier> packagePrivate = Set.of();
        assertTrue(HeadlessImportFixer.accessibleChain(List.of(publicType), false));
        assertFalse(HeadlessImportFixer.accessibleChain(List.of(privateNested, publicType), false));
        assertFalse(HeadlessImportFixer.accessibleChain(List.of(privateNested, publicType), true));
        assertFalse(HeadlessImportFixer.accessibleChain(List.of(packagePrivate), false));
        assertTrue(HeadlessImportFixer.accessibleChain(List.of(packagePrivate), true));
        assertFalse(HeadlessImportFixer.accessibleChain(List.of(Set.of(Modifier.PUBLIC), packagePrivate), false));
    }

    /**
     * The second live test: RetentionPolicy.RUNTIME, Collectors.toList() and new AbstractMap.SimpleEntry<>()
     * were neither imported nor reported, because javac names a missing qualifier as a variable or a package.
     */
    @Test
    void qualifierAndOuterTypeFormsAreRead() {
        assertEquals("List", HeadlessImportFixer.typeNameFromMessage(
                "cannot find symbol\n  symbol:   class List\n  location: class A", true, false));
        assertEquals("Collectors", HeadlessImportFixer.typeNameFromMessage(
                "cannot find symbol\n  symbol:   variable Collectors\n  location: class A", true, false));
        assertEquals("AbstractMap", HeadlessImportFixer.typeNameFromMessage(
                "package AbstractMap does not exist", false, true));
        assertNull(HeadlessImportFixer.typeNameFromMessage(
                "cannot find symbol\n  symbol:   variable count\n  location: class A", true, false));
        assertNull(HeadlessImportFixer.typeNameFromMessage("package com.acme does not exist", false, true));
        assertNull(HeadlessImportFixer.typeNameFromMessage("package acme does not exist", false, true));
        assertNull(HeadlessImportFixer.typeNameFromMessage(
                "cannot find symbol\n  symbol:   method foo()\n  location: class A", true, false));
        assertTrue(HeadlessImportFixer.isMissingPackageCode("compiler.err.doesnt.exist"));
        assertFalse(HeadlessImportFixer.isMissingPackageCode("compiler.err.cant.resolve"));
    }

    @Test
    void unresolvedTypeCodeIsTheJavacCantResolveFamily() {
        assertTrue(HeadlessImportFixer.isUnresolvedTypeCode("compiler.err.cant.resolve"));
        assertTrue(HeadlessImportFixer.isUnresolvedTypeCode("compiler.err.cant.resolve.location"));
        assertTrue(HeadlessImportFixer.isUnresolvedTypeCode("compiler.err.cant.resolve.args"));
        assertFalse(HeadlessImportFixer.isUnresolvedTypeCode("compiler.err.expected"));
        assertFalse(HeadlessImportFixer.isUnresolvedTypeCode(null));
    }

    @Test
    void unresolvedHandleIsDropped() {
        assertFalse(HeadlessImportFixer.includeResolvedType(null));
    }

    @Test
    void nestedTypeOrStaticMemberCountsAndAPlainMemberDoesNot() {
        assertTrue(HeadlessImportFixer.countsAsNestedOrStaticMember(ElementKind.CLASS, false));
        assertTrue(HeadlessImportFixer.countsAsNestedOrStaticMember(ElementKind.INTERFACE, false));
        assertTrue(HeadlessImportFixer.countsAsNestedOrStaticMember(ElementKind.ENUM, false));
        assertTrue(HeadlessImportFixer.countsAsNestedOrStaticMember(ElementKind.RECORD, false));
        assertTrue(HeadlessImportFixer.countsAsNestedOrStaticMember(ElementKind.ANNOTATION_TYPE, false));
        assertTrue(HeadlessImportFixer.countsAsNestedOrStaticMember(ElementKind.METHOD, true));
        assertTrue(HeadlessImportFixer.countsAsNestedOrStaticMember(ElementKind.FIELD, true));
        assertFalse(HeadlessImportFixer.countsAsNestedOrStaticMember(ElementKind.METHOD, false));
        assertFalse(HeadlessImportFixer.countsAsNestedOrStaticMember(ElementKind.FIELD, false));
        assertFalse(HeadlessImportFixer.countsAsNestedOrStaticMember(null, false));
    }

    @Test
    void starImportCoversTypesOnlyWhenItIsNotStatic() {
        assertTrue(HeadlessImportFixer.isTypeOnDemandImport(false, "*"));
        assertFalse(HeadlessImportFixer.isTypeOnDemandImport(true, "*"));
        assertFalse(HeadlessImportFixer.isTypeOnDemandImport(false, "List"));
        assertFalse(HeadlessImportFixer.isTypeOnDemandImport(false, null));
    }

    @Test
    void plannedImportThatDidNotResolveBecomesUnresolvedAndTheSiblingStays() {
        ImportCandidatePlanner.Decision added = new ImportCandidatePlanner.Decision("List",
                ImportCandidatePlanner.Status.ADDED, "java.util.List", List.of(), 1, null, null, true);
        ImportCandidatePlanner.Decision picked = new ImportCandidatePlanner.Decision("Map",
                ImportCandidatePlanner.Status.PICKED, "java.util.Map", List.of(), 2, null, null, true);
        List<ImportCandidatePlanner.Decision> adjusted = HeadlessImportFixer.withOnlyApplied(
                List.of(added, picked),
                List.of("java.util.List", "java.util.Map"),
                Set.of("java.util.List"));
        assertEquals(ImportCandidatePlanner.Status.ADDED, adjusted.get(0).status());
        assertEquals("java.util.List", adjusted.get(0).importedQualifiedName());
        assertEquals(ImportCandidatePlanner.Status.UNRESOLVED, adjusted.get(1).status());
        assertEquals("not resolvable in this compilation", adjusted.get(1).unresolvedReason());
        assertTrue(adjusted.get(1).hadCandidates());
        assertNull(adjusted.get(1).importedQualifiedName());

        List<ImportCandidatePlanner.Decision> untouched = List.of(added);
        assertSame(untouched, HeadlessImportFixer.withOnlyApplied(untouched, List.of(), Set.of()));
    }
}

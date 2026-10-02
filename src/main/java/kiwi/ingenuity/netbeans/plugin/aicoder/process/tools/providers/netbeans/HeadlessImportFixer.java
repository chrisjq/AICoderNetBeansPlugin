package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.GeneratorUtilities;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.ModificationResult;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Adds missing imports for one Java file without opening a dialog.
 * <p>
 * Unresolved type names come from error diagnostics on this file whose code starts with
 * {@code compiler.err.cant.resolve}. The English message is used only to read the simple name. Candidates
 * come from {@link ClassIndex#getDeclaredTypes} on the source and dependency indexes. Which of those names
 * are imported is {@link ImportCandidatePlanner}'s decision. Every name that narrows to one candidate is
 * written in the same edit; an ambiguous or unresolved neighbour does not skip the others and does not roll
 * them back. The result is always a normal message, including when some names could not be decided.
 * <p>
 * NetBeans' code-completion exclude list is not consulted. Nothing in the public API exposes it.
 */
public final class HeadlessImportFixer {

    private static final Logger LOG = Logger.getLogger(HeadlessImportFixer.class.getName());

    /**
     * Javac's English "cannot find symbol" text names the missing type on its own line, for example
     * {@code symbol: class List}. Variable and method misses use a different word and do not match.
     */
    private static final Pattern TYPE_SYMBOL = Pattern.compile(
            "symbol:\\s*(?:class|interface|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * A missing type used as a qualifier ({@code Collectors.toList()}, {@code RetentionPolicy.RUNTIME}) is
     * reported as {@code symbol: variable Collectors}, because javac reads it in expression position.
     */
    private static final Pattern QUALIFIER_SYMBOL = Pattern.compile("symbol:\\s*variable\\s+([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * A missing outer type in a qualified type name ({@code new AbstractMap.SimpleEntry<>()}) is reported as
     * {@code package AbstractMap does not exist}, code {@code compiler.err.doesnt.exist}.
     */
    private static final Pattern MISSING_PACKAGE = Pattern.compile("package\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+does not exist");

    private HeadlessImportFixer() {
    }

    public record Outcome(String message, boolean changed) {

    }

    private record Scan(List<ImportCandidatePlanner.Decision> decisions, boolean errorsWithoutNames) {

    }

    public static Outcome fix(FileObject fo, boolean pickBest) {
        if (fo == null) {
            return new Outcome("File not found", false);
        }
        JavaSource javaSource = JavaSource.forFileObject(fo);
        if (javaSource == null) {
            return new Outcome("Not a Java file: " + displayPath(fo), false);
        }
        boolean committed = false;
        List<ImportCandidatePlanner.Decision> decisions = List.of();
        boolean errorsWithoutNames = false;
        try {
            Scan scan = analyse(javaSource, pickBest);
            decisions = scan.decisions();
            errorsWithoutNames = scan.errorsWithoutNames();
            List<String> planned = ImportCandidatePlanner.importsToApply(decisions);
            Set<String> applied = Set.of();
            if (!planned.isEmpty()) {
                AtomicBoolean rewrote = new AtomicBoolean();
                AtomicReference<List<String>> appliedRef = new AtomicReference<>(List.of());
                ModificationResult modification = javaSource.runModificationTask(copy -> {
                    copy.toPhase(JavaSource.Phase.RESOLVED);
                    CompilationUnitTree unit = copy.getCompilationUnit();
                    Set<Element> elements = new LinkedHashSet<>();
                    List<String> added = new ArrayList<>();
                    for (String qualifiedName : planned) {
                        TypeElement type = copy.getElements().getTypeElement(qualifiedName);
                        if (type == null) {
                            continue;
                        }
                        elements.add(type);
                        added.add(qualifiedName);
                    }
                    appliedRef.set(List.copyOf(added));
                    if (elements.isEmpty()) {
                        return;
                    }
                    CompilationUnitTree updated = GeneratorUtilities.get(copy).addImports(unit, elements);
                    if (updated != unit) {
                        copy.rewrite(unit, updated);
                        rewrote.set(true);
                    }
                });
                applied = Set.copyOf(appliedRef.get());
                if (rewrote.get()) {
                    modification.commit();
                    committed = true;
                }
            }
            String message = ImportCandidatePlanner.format(withOnlyApplied(decisions, planned, applied), errorsWithoutNames);
            return new Outcome(message, committed);
        }
        catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "headless fix imports failed for " + displayPath(fo), e);
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (committed) {
                return new Outcome(ImportCandidatePlanner.format(decisions, errorsWithoutNames)
                                   + " Error after writing imports: " + detail, true);
            }
            return new Outcome("Error: " + detail, false);
        }
    }

    private static Scan analyse(JavaSource javaSource, boolean pickBest) throws IOException {
        AtomicReference<Scan> ref = new AtomicReference<>(new Scan(List.of(), false));
        javaSource.runUserActionTask(cc -> ref.set(analyse(cc, pickBest)), true);
        return ref.get();
    }

    private static Scan analyse(CompilationController cc, boolean pickBest) throws IOException {
        cc.toPhase(JavaSource.Phase.RESOLVED);
        CompilationUnitTree unit = cc.getCompilationUnit();
        String filePackage = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        Set<String> imported = new LinkedHashSet<>();
        Set<String> onDemand = new LinkedHashSet<>();
        for (ImportTree imp : unit.getImports()) {
            String pkg = packageOfImport(cc, imp);
            if (pkg != null) {
                imported.add(pkg);
                if (isTypeOnDemand(imp)) {
                    onDemand.add(pkg);
                }
            }
        }
        Path thisFile = normalizedFile(cc.getFileObject());
        IndexScope scope = indexScope(cc);
        ClassIndex bootIndex = null;
        boolean searchBoot = !jdkVisible(scope.classpath().getClassIndex());
        if (searchBoot) {
            bootIndex = bootIndex();
        }
        Map<String, ImportCandidatePlanner.Usage> unresolved = new LinkedHashMap<>();
        Map<String, String> candidateCounts = new LinkedHashMap<>();
        int totalDiagnostics = 0;
        int matched = 0;
        int errorsHere = 0;
        String firstSource = "none";
        int rejected = 0;
        String firstRejected = null;
        boolean trace = LOG.isLoggable(Level.FINE);
        try {
            for (Object diagnostic : cc.getDiagnostics()) {
                totalDiagnostics++;
                URI source = diagnosticUri(diagnostic);
                if (trace && totalDiagnostics == 1) {
                    firstSource = sourceDescription(diagnostic, source);
                }
                if (!sameFile(thisFile, source)) {
                    rejected++;
                    if (trace && firstRejected == null) {
                        firstRejected = sourceDescription(diagnostic, source);
                    }
                    continue;
                }
                matched++;
                if (!isError(diagnostic)) {
                    continue;
                }
                errorsHere++;
                String name = unresolvedTypeName(diagnostic);
                if (name == null) {
                    continue;
                }
                unresolved.merge(name, usageAt(cc, diagnostic, name), ImportCandidatePlanner.Usage::merge);
            }
            ImportCandidatePlanner.FileFit fit = new ImportCandidatePlanner.FileFit(filePackage, imported, onDemand);
            List<ImportCandidatePlanner.Decision> decisions = new ArrayList<>();
            for (Map.Entry<String, ImportCandidatePlanner.Usage> entry : unresolved.entrySet()) {
                NameLookup found = lookup(cc, scope.classpath(), bootIndex, entry.getKey(), entry.getValue().memberNames(),
                        filePackage);
                candidateCounts.put(entry.getKey(), found.indexHits() + " index, " + found.candidates().size() + " kept");
                decisions.add(ImportCandidatePlanner.decide(entry.getKey(), entry.getValue(), found.candidates(), fit, pickBest));
            }
            return new Scan(List.copyOf(decisions), errorsHere > 0 && unresolved.isEmpty());
        }
        finally {
            LOG.log(Level.FINE,
                    "FixImports {0}: diagnostics={1}, matched={2}, errors={3}, unresolved={4}, candidates={5}, classpath={6}, bootSearch={7}, file={8}, firstSource={9}, rejectedAsOtherFile={10}, firstRejected={11}",
                    new Object[]{displayPath(cc.getFileObject()), totalDiagnostics, matched, errorsHere,
                                 unresolved.keySet(), candidateCounts, scope.projectWide() ? "project" : "file", searchBoot,
                                 thisFile, firstSource, rejected, firstRejected == null ? "none" : firstRejected});
        }
    }

    private record IndexScope(ClasspathInfo classpath, boolean projectWide) {

    }

    private record NameLookup(List<ImportCandidatePlanner.Candidate> candidates, int indexHits) {

    }

    /**
     * False only when the diagnostic provably belongs to another file.
     * {@code CompilationInfo.getDiagnostics()} already reports this file's errors, so this is a guard rather
     * than the primary filter: a source that cannot be identified, or is not a plain file, counts as this
     * file. Paths are compared after resolving symlinks, falling back to normalized absolute paths when a
     * path does not exist.
     */
    static boolean sameFile(Path filePath, URI diagnosticUri) {
        if (filePath == null || diagnosticUri == null || !"file".equalsIgnoreCase(diagnosticUri.getScheme())) {
            return true;
        }
        try {
            return canonical(filePath).equals(canonical(Path.of(diagnosticUri)));
        }
        catch (IllegalArgumentException | FileSystemNotFoundException ex) {
            return true;
        }
    }

    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        }
        catch (IOException | SecurityException ex) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static Path normalizedFile(FileObject fo) {
        if (fo == null) {
            return null;
        }
        java.io.File disk = FileUtil.toFile(fo);
        if (disk == null) {
            return null;
        }
        return disk.toPath().toAbsolutePath().normalize();
    }

    /**
     * Javac's public diagnostic code for an unresolved symbol. The English message is used only to read the
     * simple name.
     */
    static boolean isUnresolvedTypeCode(String code) {
        return code != null && code.startsWith("compiler.err.cant.resolve");
    }

    static boolean isMissingPackageCode(String code) {
        return "compiler.err.doesnt.exist".equals(code);
    }

    /**
     * The missing type's simple name from javac's English message, or null. A plain type use names it as a
     * class, interface or enum. A type used as a qualifier is named as a variable, and a missing outer type
     * as a package; those two forms are taken only for a capitalised simple name, the Java convention for
     * types, so a missing local variable ({@code foo.bar()}) or a real missing package ({@code com.acme}) is
     * not reported as a missing import.
     */
    static String typeNameFromMessage(String message, boolean cantResolve, boolean missingPackage) {
        if (message == null) {
            return null;
        }
        if (cantResolve) {
            Matcher type = TYPE_SYMBOL.matcher(message);
            if (type.find()) {
                return type.group(1);
            }
            Matcher qualifier = QUALIFIER_SYMBOL.matcher(message);
            if (qualifier.find() && looksLikeTypeName(qualifier.group(1))) {
                return qualifier.group(1);
            }
            return null;
        }
        if (missingPackage) {
            Matcher pkg = MISSING_PACKAGE.matcher(message);
            if (pkg.find() && looksLikeTypeName(pkg.group(1))) {
                return pkg.group(1);
            }
        }
        return null;
    }

    private static boolean looksLikeTypeName(String name) {
        return name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private static final String DIAGNOSTIC_INTERFACE = "javax.tools.Diagnostic";
    private static final String FILE_OBJECT_INTERFACE = "javax.tools.FileObject";

    /**
     * A public interface method to call reflectively on {@code target}. Diagnostics and their sources are
     * {@code javax.tools} types from javac's class loader, so they cannot be cast here; and the concrete
     * classes are not public (NetBeans wraps each diagnostic in {@code CompilationInfoImpl$RichDiagnostic}),
     * so a method looked up on {@code target.getClass()} throws IllegalAccessException when invoked. The
     * method is therefore taken from the named public interface the object implements, found by name so no
     * {@code javax.tools} class from this loader is involved.
     */
    static Method publicMethod(Object target, String interfaceName, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> found = findInterface(target.getClass(), interfaceName);
        if (found == null) {
            throw new NoSuchMethodException(interfaceName + "." + name + " on " + target.getClass().getName());
        }
        return found.getMethod(name, parameterTypes);
    }

    private static Class<?> findInterface(Class<?> type, String interfaceName) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> candidate : c.getInterfaces()) {
                if (candidate.getName().equals(interfaceName)) {
                    return candidate;
                }
                Class<?> inherited = findInterface(candidate, interfaceName);
                if (inherited != null) {
                    return inherited;
                }
            }
        }
        return null;
    }

    /**
     * The diagnostic's source class and URI, for the diagnostic log line only.
     */
    private static String sourceDescription(Object diagnostic, URI uri) {
        try {
            Object source = publicMethod(diagnostic, DIAGNOSTIC_INTERFACE, "getSource").invoke(diagnostic);
            return (source == null ? "null" : source.getClass().getName()) + " " + uri;
        }
        catch (ReflectiveOperationException | RuntimeException ex) {
            return ex.getClass().getSimpleName() + " " + uri;
        }
    }

    private static URI diagnosticUri(Object diagnostic) {
        try {
            return uriOf(publicMethod(diagnostic, DIAGNOSTIC_INTERFACE, "getSource").invoke(diagnostic));
        }
        catch (ReflectiveOperationException ex) {
            LOG.log(Level.FINE, "FixImports could not read a diagnostic of type " + diagnostic.getClass().getName(), ex);
            return null;
        }
    }

    private static URI uriOf(Object source) {
        if (source == null) {
            return null;
        }
        try {
            Object uri = publicMethod(source, FILE_OBJECT_INTERFACE, "toUri").invoke(source);
            if (uri instanceof URI found) {
                return found;
            }
            return uri == null ? null : URI.create(uri.toString());
        }
        catch (ReflectiveOperationException | IllegalArgumentException ex) {
            LOG.log(Level.FINE, "FixImports could not read the URI of a diagnostic source of type "
                                + source.getClass().getName(), ex);
            return null;
        }
    }

    static boolean isError(Object diagnostic) {
        try {
            Object kind = publicMethod(diagnostic, DIAGNOSTIC_INTERFACE, "getKind").invoke(diagnostic);
            return kind != null && "ERROR".equals(kind.toString());
        }
        catch (ReflectiveOperationException ex) {
            LOG.log(Level.FINE, "FixImports could not read a diagnostic of type " + diagnostic.getClass().getName(), ex);
            return false;
        }
    }

    private static String codeOf(Object diagnostic) {
        try {
            Object code = publicMethod(diagnostic, DIAGNOSTIC_INTERFACE, "getCode").invoke(diagnostic);
            return code == null ? null : code.toString();
        }
        catch (ReflectiveOperationException ex) {
            LOG.log(Level.FINE, "FixImports could not read a diagnostic of type " + diagnostic.getClass().getName(), ex);
            return null;
        }
    }

    private static String unresolvedTypeName(Object diagnostic) {
        try {
            String code = codeOf(diagnostic);
            boolean cantResolve = isUnresolvedTypeCode(code);
            boolean missingPackage = isMissingPackageCode(code);
            if (!cantResolve && !missingPackage) {
                return null;
            }
            String message = (String) publicMethod(diagnostic, DIAGNOSTIC_INTERFACE, "getMessage", Locale.class)
                    .invoke(diagnostic, Locale.ENGLISH);
            return typeNameFromMessage(message, cantResolve, missingPackage);
        }
        catch (ReflectiveOperationException ex) {
            LOG.log(Level.FINE, "FixImports could not read a diagnostic of type " + diagnostic.getClass().getName(), ex);
            return null;
        }
    }

    private static ImportCandidatePlanner.Usage usageAt(CompilationInfo cc, Object diagnostic, String simpleName) {
        try {
            Object position = publicMethod(diagnostic, DIAGNOSTIC_INTERFACE, "getStartPosition").invoke(diagnostic);
            long start = position instanceof Number number ? number.longValue() : -1L;
            if (start < 0 || start > Integer.MAX_VALUE) {
                return ImportCandidatePlanner.Usage.none();
            }
            // pathFor at a tree's exact start position returns the enclosing tree, so look one character into
            // the name to land on the identifier itself; otherwise List<String> is never seen as generic.
            TreePath path = cc.getTreeUtilities().pathFor((int) start + 1);
            if (path == null || !simpleName.equals(simpleNameOf(path.getLeaf()))) {
                path = cc.getTreeUtilities().pathFor((int) start);
            }
            if (path == null) {
                return ImportCandidatePlanner.Usage.none();
            }
            return usageOf(path, simpleName);
        }
        catch (ReflectiveOperationException | RuntimeException ex) {
            LOG.log(Level.FINE, "no usage for " + simpleName, ex);
            return ImportCandidatePlanner.Usage.none();
        }
    }

    /**
     * Walks from the missing name up through the parents that say what kind of type it has to be. A generic
     * {@code new} is both constructed and parameterised, because the identifier sits under the type arguments
     * which sit under the {@code new}.
     */
    private static ImportCandidatePlanner.Usage usageOf(TreePath path, String simpleName) {
        boolean constructed = false;
        boolean annotation = false;
        boolean extended = false;
        boolean implemented = false;
        Integer typeArgumentCount = null;
        // Nested type (static or not) or a static member selected on this name, as in Outer.Inner or Type.FOO.
        String selectedMember = null;
        TreePath current = path;
        while (current.getParentPath() != null) {
            Tree node = current.getLeaf();
            Tree parent = current.getParentPath().getLeaf();
            if (parent instanceof ParameterizedTypeTree parameterized && parameterized.getType() == node) {
                // A diamond (new ArrayList<>()) has no type arguments written, so it says nothing about arity.
                int written = parameterized.getTypeArguments().size();
                if (written > 0) {
                    typeArgumentCount = written;
                }
            }
            if (parent instanceof NewClassTree created && created.getIdentifier() == node) {
                constructed = true;
            }
            if (parent instanceof AnnotationTree annotated && annotated.getAnnotationType() == node) {
                annotation = true;
            }
            if (parent instanceof ClassTree type) {
                if (type.getExtendsClause() == node) {
                    extended = true;
                }
                if (type.getImplementsClause().contains(node)) {
                    implemented = true;
                }
            }
            if (parent instanceof MemberSelectTree select && select.getExpression() == node && selectedMember == null) {
                String member = select.getIdentifier().toString();
                if (!"*".equals(member) && simpleName.equals(simpleNameOf(node))) {
                    selectedMember = member;
                    // The name is only a qualifier: any new, type arguments, extends or annotation further up
                    // belong to the selected member (SimpleEntry in new AbstractMap.SimpleEntry<>()), not to it.
                    break;
                }
            }
            current = current.getParentPath();
        }
        return new ImportCandidatePlanner.Usage(constructed, annotation, extended, implemented,
                typeArgumentCount, false, selectedMember == null ? Set.of() : Set.of(selectedMember));
    }

    private static String simpleNameOf(Tree node) {
        if (node instanceof IdentifierTree identifier) {
            return identifier.getName().toString();
        }
        if (node instanceof MemberSelectTree select) {
            return select.getIdentifier().toString();
        }
        return null;
    }

    /**
     * A non-static star import ({@code import java.util.*}) makes every type in that package visible. A
     * static star ({@code import static java.util.Map.*}) brings in members, not type names, and a
     * single-type import does not cover its siblings.
     */
    static boolean isTypeOnDemandImport(boolean staticImport, String selectedName) {
        return !staticImport && "*".equals(selectedName);
    }

    private static boolean isTypeOnDemand(ImportTree imp) {
        Tree qualified = imp.getQualifiedIdentifier();
        String selected = qualified instanceof MemberSelectTree select ? select.getIdentifier().toString() : null;
        return isTypeOnDemandImport(imp.isStatic(), selected);
    }

    /**
     * The package a single or on-demand import already brings in. The import is resolved to an element and
     * the package is that element's real package, so {@code import com.acme.Outer.Helper} records
     * {@code com.acme}. A star import resolves the type or package before the star. The text parse runs only
     * when the element cannot be resolved. Returns null when the import has no package.
     */
    private static String packageOfImport(CompilationInfo cc, ImportTree imp) {
        Tree qualified = imp.getQualifiedIdentifier();
        String text = qualified == null ? null : qualified.toString();
        return ImportCandidatePlanner.importedPackage(resolvedPackage(cc, imp), text, imp.isStatic());
    }

    private static String resolvedPackage(CompilationInfo cc, ImportTree imp) {
        try {
            Tree target = imp.getQualifiedIdentifier();
            if (target instanceof MemberSelectTree select && "*".equals(select.getIdentifier().toString())) {
                target = select.getExpression();
            }
            if (target == null) {
                return null;
            }
            TreePath path = TreePath.getPath(cc.getCompilationUnit(), target);
            if (path == null) {
                return null;
            }
            Element element = cc.getTrees().getElement(path);
            if (element == null) {
                return null;
            }
            PackageElement pkg = cc.getElements().getPackageOf(element);
            if (pkg == null || pkg.isUnnamed()) {
                return null;
            }
            String name = pkg.getQualifiedName().toString();
            return name.isBlank() ? null : name;
        }
        catch (RuntimeException ex) {
            LOG.log(Level.FINE, "import package unresolved", ex);
            return null;
        }
    }

    private static NameLookup lookup(CompilationInfo cc, ClasspathInfo classpath, ClassIndex bootIndex,
                                     String simpleName, Set<String> memberNames, String filePackage) {
        Map<String, ElementHandle<TypeElement>> byName = new LinkedHashMap<>();
        Set<String> projectNames = new LinkedHashSet<>();
        search(classpath.getClassIndex(), simpleName, ClassIndex.SearchScope.SOURCE, byName, projectNames, true);
        search(classpath.getClassIndex(), simpleName, ClassIndex.SearchScope.DEPENDENCIES, byName, projectNames, false);
        if (bootIndex != null) {
            search(bootIndex, simpleName, ClassIndex.SearchScope.DEPENDENCIES, byName, projectNames, false);
        }
        List<ImportCandidatePlanner.Candidate> candidates = new ArrayList<>();
        int indexHits = 0;
        for (Map.Entry<String, ElementHandle<TypeElement>> entry : byName.entrySet()) {
            if (!simpleName.equals(simpleNameOf(entry.getKey()))) {
                continue;
            }
            indexHits++;
            ImportCandidatePlanner.Candidate candidate = toCandidate(cc, entry.getValue(),
                    projectNames.contains(entry.getKey()), memberNames, filePackage);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return new NameLookup(List.copyOf(candidates), indexHits);
    }

    private static void search(ClassIndex index, String simpleName, ClassIndex.SearchScope scope,
                               Map<String, ElementHandle<TypeElement>> byName, Set<String> projectNames,
                               boolean projectSource) {
        if (index == null) {
            return;
        }
        try {
            addHandles(byName, projectNames,
                    index.getDeclaredTypes(simpleName, ClassIndex.NameKind.SIMPLE_NAME, EnumSet.of(scope)),
                    projectSource);
        }
        catch (RuntimeException ex) {
            LOG.log(Level.FINE, "index search failed for " + simpleName, ex);
        }
    }

    /**
     * True when {@code java.lang.Object} is already in the dependency index. {@code SearchScope} has no boot
     * constant, so a miss means the JDK has to be queried from {@link ProjectClasspath#bootOnly()}.
     */
    private static boolean jdkVisible(ClassIndex index) {
        if (index == null) {
            return false;
        }
        try {
            Set<ElementHandle<TypeElement>> found = index.getDeclaredTypes("Object", ClassIndex.NameKind.SIMPLE_NAME,
                    EnumSet.of(ClassIndex.SearchScope.DEPENDENCIES));
            if (found == null) {
                return false;
            }
            for (ElementHandle<TypeElement> handle : found) {
                if ("java.lang.Object".equals(handle.getQualifiedName())) {
                    return true;
                }
            }
            return false;
        }
        catch (RuntimeException ex) {
            LOG.log(Level.FINE, "JDK dependency probe failed", ex);
            return false;
        }
    }

    private static ClassIndex bootIndex() {
        try {
            return ProjectClasspath.bootOnly().getClassIndex();
        }
        catch (RuntimeException ex) {
            LOG.log(Level.FINE, "boot classpath unavailable", ex);
            return null;
        }
    }

    private static IndexScope indexScope(CompilationInfo cc) {
        FileObject fo = cc.getFileObject();
        try {
            Project project = fo == null ? null : FileOwnerQuery.getOwner(fo);
            if (project != null) {
                ClasspathInfo projectClasspath = ProjectClasspath.forProject(project);
                if (projectClasspath != null) {
                    return new IndexScope(projectClasspath, true);
                }
            }
        }
        catch (Throwable ex) {
            LOG.log(Level.FINE, "owning project unavailable", ex);
        }
        return new IndexScope(cc.getClasspathInfo(), false);
    }

    private static void addHandles(Map<String, ElementHandle<TypeElement>> byName, Set<String> projectNames,
                                   Set<ElementHandle<TypeElement>> found, boolean projectSource) {
        if (found == null) {
            return;
        }
        for (ElementHandle<TypeElement> handle : found) {
            String qualifiedName = handle.getQualifiedName();
            if (qualifiedName == null || qualifiedName.isBlank()) {
                continue;
            }
            byName.putIfAbsent(qualifiedName, handle);
            if (projectSource) {
                projectNames.add(qualifiedName);
            }
        }
    }

    /**
     * Drops a handle that will not resolve. An unresolved handle has no trustworthy package or kind, so it
     * must not be scored and must not make the name ambiguous.
     */
    private static ImportCandidatePlanner.Candidate toCandidate(CompilationInfo cc, ElementHandle<TypeElement> handle,
                                                                boolean projectSource, Set<String> memberNames,
                                                                String filePackage) {
        String qualifiedName = handle.getQualifiedName();
        TypeElement type = null;
        try {
            type = handle.resolve(cc);
        }
        catch (RuntimeException ex) {
            LOG.log(Level.FINE, "resolve failed for " + qualifiedName, ex);
        }
        if (!includeResolvedType(type)) {
            return null;
        }
        if (!accessibleFrom(type, filePackage)) {
            return null;
        }
        boolean membersKnown = memberNames.isEmpty();
        Set<String> foundMembers = Set.of();
        if (!memberNames.isEmpty()) {
            try {
                Set<String> matches = new LinkedHashSet<>();
                for (Element member : cc.getElements().getAllMembers(type)) {
                    if (countsAsNestedOrStaticMember(member.getKind(), member.getModifiers().contains(Modifier.STATIC))
                        && memberNames.contains(member.getSimpleName().toString())) {
                        matches.add(member.getSimpleName().toString());
                    }
                }
                foundMembers = Set.copyOf(matches);
                membersKnown = true;
            }
            catch (RuntimeException ex) {
                LOG.log(Level.FINE, "members unavailable for " + qualifiedName, ex);
            }
        }
        return new ImportCandidatePlanner.Candidate(qualifiedName, packageNameOf(type), kindOf(type.getKind()),
                type.getModifiers().contains(Modifier.ABSTRACT), type.getTypeParameters().size(),
                membersKnown, foundMembers, projectSource);
    }

    /**
     * An element handle that will not resolve is dropped. Keeping it would block a candidate that did
     * resolve.
     */
    static boolean includeResolvedType(TypeElement type) {
        return type != null;
    }

    /**
     * A type can only be imported when it and every type enclosing it are visible from the file:
     * {@code java.util.Arrays.ArrayList} is a private nested class and must not compete with
     * {@code java.util.ArrayList}.
     */
    private static boolean accessibleFrom(TypeElement type, String filePackage) {
        List<Set<Modifier>> chain = new ArrayList<>();
        String typePackage = null;
        for (Element e = type; e != null; e = e.getEnclosingElement()) {
            if (e instanceof PackageElement pkg) {
                typePackage = pkg.getQualifiedName().toString();
                break;
            }
            if (e instanceof TypeElement) {
                chain.add(e.getModifiers());
            }
        }
        return accessibleChain(chain, typePackage != null && typePackage.equals(filePackage));
    }

    /**
     * {@code chain} holds the modifiers of the type and each enclosing type. Private anywhere hides it; a
     * level that is not public is visible only from the same package (protected nested types are treated as
     * not importable from another package, since this file is not known to be a subclass).
     */
    static boolean accessibleChain(List<Set<Modifier>> chain, boolean samePackage) {
        for (Set<Modifier> modifiers : chain) {
            if (modifiers.contains(Modifier.PRIVATE)) {
                return false;
            }
            if (!modifiers.contains(Modifier.PUBLIC) && !samePackage) {
                return false;
            }
        }
        return true;
    }

    /**
     * A member select counts when it names a nested type, static or not, or any static member.
     * {@link ElementKind#isClass()} covers only CLASS and ENUM, so RECORD, INTERFACE and ANNOTATION_TYPE are
     * named here. A non-static field or method does not count.
     */
    static boolean countsAsNestedOrStaticMember(ElementKind kind, boolean isStatic) {
        if (isStatic) {
            return true;
        }
        if (kind == null) {
            return false;
        }
        return switch (kind) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE ->
                true;
            default ->
                false;
        };
    }

    private static ImportCandidatePlanner.TypeKind kindOf(ElementKind kind) {
        return switch (kind) {
            case CLASS ->
                ImportCandidatePlanner.TypeKind.CLASS;
            case INTERFACE ->
                ImportCandidatePlanner.TypeKind.INTERFACE;
            case ANNOTATION_TYPE ->
                ImportCandidatePlanner.TypeKind.ANNOTATION;
            case ENUM ->
                ImportCandidatePlanner.TypeKind.ENUM;
            case RECORD ->
                ImportCandidatePlanner.TypeKind.RECORD;
            default ->
                ImportCandidatePlanner.TypeKind.UNKNOWN;
        };
    }

    private static String packageNameOf(TypeElement type) {
        Element enclosing = type.getEnclosingElement();
        while (enclosing != null && enclosing.getKind() != ElementKind.PACKAGE) {
            enclosing = enclosing.getEnclosingElement();
        }
        if (enclosing instanceof PackageElement pkg) {
            return pkg.getQualifiedName().toString();
        }
        return "";
    }

    private static String simpleNameOf(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }

    /**
     * A planned import that the modification task could not resolve is reported as unresolved instead of
     * added. The names that did resolve stay in the list, so a failure on one does not discard the rest.
     */
    static List<ImportCandidatePlanner.Decision> withOnlyApplied(List<ImportCandidatePlanner.Decision> decisions,
                                                                 List<String> planned, Set<String> applied) {
        if (planned.isEmpty()) {
            return decisions;
        }
        List<ImportCandidatePlanner.Decision> adjusted = new ArrayList<>();
        for (ImportCandidatePlanner.Decision decision : decisions) {
            boolean wanted = decision.status() == ImportCandidatePlanner.Status.ADDED
                             || decision.status() == ImportCandidatePlanner.Status.PICKED;
            if (wanted && (decision.importedQualifiedName() == null || !applied.contains(decision.importedQualifiedName()))) {
                adjusted.add(new ImportCandidatePlanner.Decision(decision.simpleName(),
                        ImportCandidatePlanner.Status.UNRESOLVED, null, List.of(), 0, null,
                        "not resolvable in this compilation", true));
            }
            else {
                adjusted.add(decision);
            }
        }
        return adjusted;
    }

    private static String displayPath(FileObject fo) {
        if (fo == null) {
            return "(no file)";
        }
        java.io.File disk = FileUtil.toFile(fo);
        return disk != null ? disk.getPath() : fo.getPath();
    }
}

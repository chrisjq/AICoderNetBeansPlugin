package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.ReferenceTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTreeScanner;
import com.sun.source.util.DocTrees;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.netbeans.api.java.source.CodeStyle;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.ModificationResult;
import org.netbeans.api.java.source.TreeMaker;
import org.netbeans.api.java.source.WorkingCopy;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Removes unused imports and sorts the rest for one Java file without opening an editor or a dialog.
 * <p>
 * One modification task scans the compilation unit for every type and static member it names, including the
 * references in javadoc comments, and asks {@link ImportOrganisePlanner} which imports are provably unused
 * and in what order the others go. The change is made with the targeted import operations of
 * {@link TreeMaker} and a single rewrite of the compilation unit, so only the import block is touched. Which
 * import each group belongs to, and so the order, comes from the file's {@link CodeStyle}. Imports are never
 * added, and a single import stays a single import: this does not turn several imports into an on-demand
 * import or the reverse.
 */
public final class HeadlessImportOrganiser {

    private static final Logger LOG = Logger.getLogger(HeadlessImportOrganiser.class.getName());

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private static final String ALREADY_ORGANISED = "Imports already organised.";

    private HeadlessImportOrganiser() {
    }

    public record Outcome(String message, boolean changed) {

    }

    private record Result(String message, boolean rewrote) {

    }

    public static Outcome organise(FileObject fo) {
        if (fo == null) {
            return new Outcome("File not found", false);
        }
        JavaSource javaSource = JavaSource.forFileObject(fo);
        if (javaSource == null) {
            return new Outcome("Not a Java file: " + displayPath(fo), false);
        }
        AtomicReference<String> message = new AtomicReference<>(ALREADY_ORGANISED);
        AtomicBoolean rewrote = new AtomicBoolean();
        try {
            ModificationResult modification = javaSource.runModificationTask(copy -> {
                copy.toPhase(JavaSource.Phase.RESOLVED);
                Result result = organise(copy, fo);
                message.set(result.message());
                rewrote.set(result.rewrote());
            });
            if (rewrote.get()) {
                modification.commit();
            }
            return new Outcome(message.get(), rewrote.get());
        }
        catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "headless organise imports failed for " + displayPath(fo), e);
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new Outcome("Error: " + detail, false);
        }
    }

    private static Result organise(WorkingCopy copy, FileObject fo) {
        CompilationUnitTree unit = copy.getCompilationUnit();
        List<? extends ImportTree> trees = unit.getImports();
        if (trees.isEmpty()) {
            return new Result(ALREADY_ORGANISED, false);
        }
        boolean hasErrors = hasErrors(copy);
        CodeStyle.ImportGroups groups = importGroups(fo);
        List<ImportOrganisePlanner.ImportDecl> decls = new ArrayList<>();
        Map<String, ImportTree> treeByKey = new LinkedHashMap<>();
        for (ImportTree imp : trees) {
            ImportOrganisePlanner.ImportDecl decl = describe(copy, unit, imp, groups);
            decls.add(decl);
            treeByKey.putIfAbsent(key(decl), imp);
        }
        ImportOrganisePlanner.Usage usage = scan(copy, unit, hasErrors);
        ImportOrganisePlanner.Plan plan = ImportOrganisePlanner.plan(decls, usage);
        LOG.log(Level.FINE,
                "OrganiseImports {0}: imports={1}, removed={2}, reordered={3}, errors={4}, typeRefs={5}, staticRefs={6}, docNames={7}, separateGroups={8}",
                new Object[]{displayPath(fo), decls.size(), plan.removed().size(), plan.reordered(), hasErrors,
                             usage.typeRefs().size(), usage.staticRefs().size(), usage.docNames().size(),
                             separatesGroups(fo)});
        String message = ImportOrganisePlanner.format(plan, hasErrors);
        if (!plan.changesAnything()) {
            return new Result(message, false);
        }
        copy.rewrite(unit, applyPlan(copy, unit, decls, plan, treeByKey));
        return new Result(message, true);
    }

    /**
     * Applies the plan with the targeted import operations, never by building a new compilation unit, so the
     * printer only has the import block to touch. Removals alone delete just the unused imports. A reorder
     * takes every import out and inserts the kept ones, in order, as the same tree objects.
     */
    private static CompilationUnitTree applyPlan(WorkingCopy copy, CompilationUnitTree unit,
                                                 List<ImportOrganisePlanner.ImportDecl> decls,
                                                 ImportOrganisePlanner.Plan plan, Map<String, ImportTree> treeByKey) {
        TreeMaker make = copy.getTreeMaker();
        CompilationUnitTree result = unit;
        if (!plan.reordered()) {
            Set<String> keptKeys = new HashSet<>();
            plan.kept().forEach(kept -> keptKeys.add(key(kept)));
            for (int i = decls.size() - 1; i >= 0; i--) {
                String key = key(decls.get(i));
                boolean first = decls.subList(0, i).stream().noneMatch(earlier -> key(earlier).equals(key));
                if (!(first && keptKeys.contains(key))) {
                    result = make.removeCompUnitImport(result, i);
                }
            }
            return result;
        }
        for (int i = decls.size() - 1; i >= 0; i--) {
            result = make.removeCompUnitImport(result, i);
        }
        int index = 0;
        for (ImportOrganisePlanner.ImportDecl kept : plan.kept()) {
            result = make.insertCompUnitImport(result, index++, treeByKey.get(key(kept)));
        }
        return result;
    }

    private static String key(ImportOrganisePlanner.ImportDecl decl) {
        return decl.isStatic() + " " + decl.name();
    }

    private static boolean hasErrors(WorkingCopy copy) {
        for (Object diagnostic : copy.getDiagnostics()) {
            if (HeadlessImportFixer.isError(diagnostic)) {
                return true;
            }
        }
        return false;
    }

    private static CodeStyle.ImportGroups importGroups(FileObject fo) {
        try {
            CodeStyle style = CodeStyle.getDefault(fo);
            return style == null ? null : style.getImportGroups();
        }
        catch (RuntimeException ex) {
            LOG.log(Level.FINE, "import groups unavailable", ex);
            return null;
        }
    }

    /**
     * Whether the code style wants a blank line between import groups. Nothing here writes blank lines: the
     * public API gives no way to ask for them, so the printer's own handling of this setting decides how a
     * reordered import block looks. It is read so the setting is visible in the log.
     */
    private static boolean separatesGroups(FileObject fo) {
        try {
            CodeStyle style = CodeStyle.getDefault(fo);
            return style != null && style.separateImportGroups();
        }
        catch (RuntimeException ex) {
            LOG.log(Level.FINE, "separateImportGroups unavailable", ex);
            return false;
        }
    }

    private static int groupIdOf(CodeStyle.ImportGroups groups, String name, boolean isStatic) {
        if (groups == null) {
            return 0;
        }
        try {
            return groups.getGroupId(name, isStatic);
        }
        catch (RuntimeException ex) {
            LOG.log(Level.FINE, "import group unavailable for " + name, ex);
            return 0;
        }
    }

    /**
     * What one import names. Anything that cannot be worked out stays null, which the planner reads as
     * "unknown, so keep the import".
     */
    private static ImportOrganisePlanner.ImportDecl describe(WorkingCopy copy, CompilationUnitTree unit, ImportTree imp,
                                                             CodeStyle.ImportGroups groups) {
        Tree qualified = imp.getQualifiedIdentifier();
        String name = qualified.toString();
        boolean isStatic = imp.isStatic();
        boolean onDemand = name.endsWith(".*");
        int group = groupIdOf(groups, name, isStatic);
        String resolved = null;
        Set<String> owners = null;
        Set<String> members = null;
        try {
            if (qualified instanceof MemberSelectTree select) {
                if (!isStatic && !onDemand) {
                    if (elementAt(copy, unit, qualified, name) instanceof TypeElement type) {
                        resolved = type.getQualifiedName().toString();
                    }
                }
                else {
                    Tree target = select.getExpression();
                    Element element = elementAt(copy, unit, target, target.toString());
                    if (element instanceof PackageElement pkg && !isStatic) {
                        owners = Set.of(pkg.getQualifiedName().toString());
                        members = typeNamesIn(pkg);
                    }
                    else if (element instanceof TypeElement type) {
                        owners = typeAndSupertypes(copy, type);
                        if (onDemand) {
                            members = isStatic ? staticMemberNames(copy, type) : memberTypeNames(copy, type);
                        }
                    }
                }
            }
        }
        catch (RuntimeException ex) {
            LOG.log(Level.FINE, "import not resolved: " + name, ex);
            resolved = null;
            owners = null;
            members = null;
        }
        return new ImportOrganisePlanner.ImportDecl(name, isStatic, group, resolved, owners, members);
    }

    /**
     * The element a tree in the import names. When the tree carries no element, the qualified name is looked
     * up as a type and then as a package.
     */
    private static Element elementAt(WorkingCopy copy, CompilationUnitTree unit, Tree tree, String qualifiedName) {
        TreePath path = TreePath.getPath(unit, tree);
        Element element = path == null ? null : copy.getTrees().getElement(path);
        if (element != null && element.asType() != null && element.asType().getKind() == TypeKind.ERROR) {
            element = null;
        }
        if (element == null) {
            element = copy.getElements().getTypeElement(qualifiedName);
        }
        if (element == null) {
            element = copy.getElements().getPackageElement(qualifiedName);
        }
        return element;
    }

    private static Set<String> typeAndSupertypes(WorkingCopy copy, TypeElement type) {
        Set<String> names = new LinkedHashSet<>();
        Deque<TypeElement> pending = new ArrayDeque<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            TypeElement next = pending.remove();
            if (!names.add(next.getQualifiedName().toString())) {
                continue;
            }
            for (TypeMirror supertype : copy.getTypes().directSupertypes(next.asType())) {
                if (supertype instanceof DeclaredType declared && declared.asElement() instanceof TypeElement parent) {
                    pending.add(parent);
                }
            }
        }
        return names;
    }

    private static Set<String> typeNamesIn(PackageElement pkg) {
        Set<String> names = new LinkedHashSet<>();
        for (Element enclosed : pkg.getEnclosedElements()) {
            if (enclosed instanceof TypeElement) {
                names.add(enclosed.getSimpleName().toString());
            }
        }
        return names;
    }

    private static Set<String> memberTypeNames(WorkingCopy copy, TypeElement type) {
        Set<String> names = new LinkedHashSet<>();
        for (Element member : copy.getElements().getAllMembers(type)) {
            if (isType(member.getKind())) {
                names.add(member.getSimpleName().toString());
            }
        }
        return names;
    }

    private static Set<String> staticMemberNames(WorkingCopy copy, TypeElement type) {
        Set<String> names = new LinkedHashSet<>();
        for (Element member : copy.getElements().getAllMembers(type)) {
            if (member.getModifiers().contains(Modifier.STATIC) || isType(member.getKind())) {
                names.add(member.getSimpleName().toString());
            }
        }
        return names;
    }

    private static boolean isType(ElementKind kind) {
        return kind.isClass() || kind.isInterface();
    }

    private static ImportOrganisePlanner.Usage scan(WorkingCopy copy, CompilationUnitTree unit, boolean hasErrors) {
        UsageScanner scanner = new UsageScanner(copy.getTrees(), copy.getDocTrees());
        scanner.collectDoc(new TreePath(unit));
        scanner.scan(new TreePath(unit), null);
        Set<String> names = scanner.names;
        if (hasErrors) {
            names = ImportOrganisePlanner.namesWithTextWords(names, copy.getText(), importSpans(copy, unit));
        }
        return new ImportOrganisePlanner.Usage(scanner.typeRefs, scanner.staticRefs, names, scanner.docNames,
                hasErrors);
    }

    private static List<int[]> importSpans(WorkingCopy copy, CompilationUnitTree unit) {
        SourcePositions positions = copy.getTrees().getSourcePositions();
        List<int[]> spans = new ArrayList<>();
        for (ImportTree imp : unit.getImports()) {
            long start = positions.getStartPosition(unit, imp);
            long end = positions.getEndPosition(unit, imp);
            if (start >= 0 && end >= start && end <= Integer.MAX_VALUE) {
                spans.add(new int[]{(int) start, (int) end});
            }
        }
        return spans;
    }

    /**
     * Collects what the code names by a simple identifier and what its javadoc refers to. The imports are
     * skipped, so an import cannot count as its own use.
     */
    private static final class UsageScanner extends TreePathScanner<Void, Void> {

        private final Trees trees;
        private final DocTrees docTrees;
        final Set<String> typeRefs = new LinkedHashSet<>();
        final Set<String> staticRefs = new LinkedHashSet<>();
        final Set<String> names = new LinkedHashSet<>();
        final Set<String> docNames = new LinkedHashSet<>();

        UsageScanner(Trees trees, DocTrees docTrees) {
            this.trees = trees;
            this.docTrees = docTrees;
        }

        @Override
        public Void visitImport(ImportTree node, Void unused) {
            return null;
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            reference(getCurrentPath(), node.getName().toString());
            return super.visitIdentifier(node, unused);
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            collectDoc(getCurrentPath());
            return super.visitClass(node, unused);
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            collectDoc(getCurrentPath());
            return super.visitMethod(node, unused);
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            TreePath parent = getCurrentPath().getParentPath();
            if (parent != null && parent.getLeaf() instanceof ClassTree) {
                collectDoc(getCurrentPath());
            }
            return super.visitVariable(node, unused);
        }

        private void reference(TreePath path, String name) {
            names.add(name);
            Element element;
            try {
                element = trees.getElement(path);
            }
            catch (RuntimeException ex) {
                return;
            }
            if (element == null || element.asType() == null || element.asType().getKind() == TypeKind.ERROR) {
                return;
            }
            ElementKind kind = element.getKind();
            if (isType(kind) && element instanceof TypeElement type) {
                addType(type);
            }
            else if (kind == ElementKind.CONSTRUCTOR && element.getEnclosingElement() instanceof TypeElement type) {
                addType(type);
            }
            else if ((kind == ElementKind.FIELD || kind == ElementKind.ENUM_CONSTANT || kind == ElementKind.METHOD)
                     && element.getModifiers().contains(Modifier.STATIC)
                     && element.getEnclosingElement() instanceof TypeElement owner) {
                staticRefs.add(ImportOrganisePlanner.Usage.staticRef(owner.getQualifiedName().toString(), name));
            }
        }

        private void addType(TypeElement type) {
            String qualifiedName = type.getQualifiedName().toString();
            if (!qualifiedName.isEmpty()) {
                typeRefs.add(qualifiedName);
            }
        }

        void collectDoc(TreePath path) {
            try {
                DocCommentTree doc = docTrees.getDocCommentTree(path);
                if (doc == null) {
                    return;
                }
                new DocTreeScanner<Void, Void>() {
                    @Override
                    public Void visitReference(ReferenceTree node, Void unused) {
                        Matcher matcher = IDENTIFIER.matcher(node.getSignature());
                        while (matcher.find()) {
                            docNames.add(matcher.group());
                        }
                        return super.visitReference(node, unused);
                    }
                }.scan(doc, null);
            }
            catch (RuntimeException ex) {
                LOG.log(Level.FINE, "javadoc not scanned", ex);
            }
        }
    }

    private static String displayPath(FileObject fo) {
        java.io.File disk = FileUtil.toFile(fo);
        return disk != null ? disk.getPath() : fo.getPath();
    }
}

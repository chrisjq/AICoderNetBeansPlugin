package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.sun.source.util.TreePath;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.SourceUtils;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

public class SearchProvider {

    private static final Logger LOG = Logger.getLogger(SearchProvider.class.getName());
    private static final int MAX_FILE_HITS = 200;
    private static final int MAX_TYPE_HITS = 100;

    public static String searchInFiles(String filePath, String query, String filePattern,
            boolean caseSensitive, boolean isRegex) {
        if (query == null || query.isBlank()) {
            return "query is required";
        }

        String glob = (filePattern == null || filePattern.isBlank()) ? "*.java" : filePattern;
        PathMatcher pathMatcher;
        try {
            pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        }
        catch (IllegalArgumentException e) {
            // A malformed glob is the caller's mistake, exactly like the invalid regex handled below, and gets the same
            // treatment. Left unguarded this escaped as an internal error (-32603), which tells the caller the tool
            // broke rather than that its filePattern was wrong — so it could not tell a bad argument from a real bug.
            // IllegalArgumentException is the widest of the documented failures: getPathMatcher throws
            // PatternSyntaxException (a subclass) for a bad pattern and plain IllegalArgumentException for malformed
            // syntax, and catching the supertype covers both without swallowing anything else.
            //
            // Checked HERE, before the roots are resolved, so a bad argument is reported on its own terms rather than
            // being masked by "No projects open" — and so it is reachable without a live project.
            return "Invalid " + McpToolPropertyEnum.FILE_PATTERN.key() + ": " + glob + " — " + e.getMessage();
        }

        List<FileObject> roots;
        if (filePath != null && !filePath.isBlank()) {
            FileObject fo = resolveFileObject(filePath);
            if (fo == null) {
                return "File not found: " + filePath;
            }
            List<FileObject> projectRoots = owningProjectSourceRoots(fo);
            if (!projectRoots.isEmpty()) {
                roots = projectRoots;
            }
            else {
                ClassPath cp = ClassPath.getClassPath(fo, ClassPath.SOURCE);
                if (cp == null) {
                    return "Cannot resolve source classpath for: " + filePath;
                }
                roots = List.of(cp.getRoots());
            }
        }
        else {
            roots = openProjectSourceRoots();
            if (roots.isEmpty()) {
                return "No projects open";
            }
        }

        Pattern pattern;
        try {
            pattern = LineMatcher.compile(query, isRegex, caseSensitive);
        }
        catch (PatternSyntaxException e) {
            return "Invalid regex: " + e.getMessage();
        }

        List<SearchResultFormatter.Hit> hits = new ArrayList<>();
        // Both counters span every match, not just the ones that fit under the
        // cap, so the header can say how much was left out. matchedFiles is
        // tracked here rather than in the formatter because the formatter only
        // sees hits that survived truncation — it would report "in 3 file(s)"
        // for matches actually spread across 40.
        Set<String> matchedFiles = new LinkedHashSet<>();
        int totalHits = 0;
        for (FileObject root : roots) {
            File rootDir = FileUtil.toFile(root);
            if (rootDir == null) {
                continue;
            }
            try {
                List<Path> files;
                try (java.util.stream.Stream<Path> stream = Files.walk(rootDir.toPath())) {
                    files = stream.filter(p -> pathMatcher.matches(p.getFileName()))
                            .sorted()
                            .toList();
                }
                for (Path p : files) {
                    try {
                        List<String> lines = Files.readAllLines(p);
                        for (int i = 0; i < lines.size(); i++) {
                            if (LineMatcher.findWithTimeout(pattern, lines.get(i))) {
                                totalHits++;
                                matchedFiles.add(p.toString());
                                if (hits.size() < MAX_FILE_HITS) {
                                    hits.add(new SearchResultFormatter.Hit(
                                            p.toString(), i + 1, lines.get(i).strip()));
                                }
                            }
                        }
                    }
                    catch (LineMatcher.RegexTimeoutException e) {
                        // A pathological pattern must not hang the handler thread; report and
                        // stop so the caller learns the query (not the corpus) is the problem.
                        return "Regex timed out after " + e.timeoutMillis()
                                + " ms — the pattern backtracks catastrophically; simplify it.";
                    }
                    catch (IOException e) {
                        // Skip unreadable / non-UTF-8 files (e.g. MalformedInputException)
                        // without aborting the rest of the source root.
                        LOG.log(Level.FINE, "Skipping unreadable file: {0}", p);
                    }
                }
            }
            catch (IOException e) {
                LOG.log(Level.FINE, "Error walking source root", e);
            }
        }

        if (hits.isEmpty()) {
            return "No matches found for: " + query;
        }
        return SearchResultFormatter.groupByFile(hits, totalHits, matchedFiles.size(), MAX_FILE_HITS);
    }

    public static String searchTypes(String filePath, String name, String kind, boolean includeDeps) {
        if (name == null || name.isBlank()) {
            return "name is required";
        }
        String invalidPattern = validateNamePattern(name, kind);
        if (invalidPattern != null) {
            return invalidPattern;
        }
        SearchAnchors anchors = resolveSearchAnchors(filePath);
        if (anchors.error() != null) {
            return anchors.error();
        }

        Set<ClassIndex.SearchScope> scopes = includeDeps
                ? EnumSet.of(ClassIndex.SearchScope.SOURCE, ClassIndex.SearchScope.DEPENDENCIES)
                : EnumSet.of(ClassIndex.SearchScope.SOURCE);

        // Keyed by qualified name so a type reachable from more than one root is counted and printed ONCE. Every root
        // resolves the same dependency jars, so without this every dependency hit appeared once per root: a five-type
        // answer was printed as ten rows under two separate "Found 5" headers. Project source types never duplicated,
        // which is why only includeDeps=true showed it.
        Map<String, TypeHit> hits = new LinkedHashMap<>();
        for (FileObject anchor : anchors.anchors()) {
            JavaSource js = JavaSource.forFileObject(anchor);
            if (js == null) {
                continue;
            }
            ClasspathInfo classpath = js.getClasspathInfo();
            Set<ElementHandle<TypeElement>> found
                    = classpath.getClassIndex().getDeclaredTypes(name, toNameKind(kind), scopes);
            if (found == null) {
                continue;
            }
            for (ElementHandle<TypeElement> handle : found) {
                hits.putIfAbsent(handle.getQualifiedName(), new TypeHit(handle, classpath));
            }
        }
        if (hits.isEmpty()) {
            return "No types found matching: " + name;
        }

        List<TypeHit> sorted = hits.values().stream()
                .sorted((a, b) -> a.handle().getQualifiedName().compareTo(b.handle().getQualifiedName()))
                .toList();

        // ONE header for the whole answer. Results used to be produced per root and concatenated, so the output carried
        // several "Found N" lines and no aggregate — 2 in one block and 5 in another when the true answer was 7. A
        // caller parsing the first total, which is the obvious thing to do, read 2.
        //
        // Report the true total, not the capped one. Saying "Found 100 (showing first 100)" tells the caller it was
        // truncated but not by how much, so it cannot judge whether to narrow the query or accept the sample — 101
        // results and 10,000 read identically.
        StringBuilder sb = new StringBuilder("Found ").append(sorted.size()).append(" type(s)");
        if (sorted.size() > MAX_TYPE_HITS) {
            sb.append(" (showing first ").append(MAX_TYPE_HITS).append(")");
        }
        sb.append(":\n\n");
        sorted.stream().limit(MAX_TYPE_HITS).forEach(hit -> {
            FileObject src = SourceUtils.getFile(hit.handle(), hit.classpath());
            File f = src != null ? FileUtil.toFile(src) : null;
            sb.append(hit.handle().getQualifiedName()).append("  →  ")
                    .append(f != null ? f.getPath() : "[binary]").append("\n");
        });
        return sb.toString();
    }

    public static String searchSymbols(String filePath, String name, String kind, boolean includeDeps) {
        if (name == null || name.isBlank()) {
            return "name is required";
        }
        String invalidPattern = validateNamePattern(name, kind);
        if (invalidPattern != null) {
            return invalidPattern;
        }
        SearchAnchors anchors = resolveSearchAnchors(filePath);
        if (anchors.error() != null) {
            return anchors.error();
        }

        Set<ClassIndex.SearchScope> scopes = includeDeps
                ? EnumSet.of(ClassIndex.SearchScope.SOURCE, ClassIndex.SearchScope.DEPENDENCIES)
                : EnumSet.of(ClassIndex.SearchScope.SOURCE);

        // De-duplicated by enclosing type for the same reason as searchTypes: dependencies resolve from every root, so
        // the identical block was previously emitted once per root under its own "Found N" header.
        Map<String, SymbolHit> hits = new LinkedHashMap<>();
        for (FileObject anchor : anchors.anchors()) {
            JavaSource js = JavaSource.forFileObject(anchor);
            if (js == null) {
                continue;
            }
            ClasspathInfo classpath = js.getClasspathInfo();
            Iterable<ClassIndex.Symbols> found
                    = classpath.getClassIndex().getDeclaredSymbols(name, toNameKind(kind), scopes);
            if (found == null) {
                continue;
            }
            for (ClassIndex.Symbols symbols : found) {
                ElementHandle<TypeElement> enclosing = symbols.getEnclosingType();
                hits.putIfAbsent(enclosing.getQualifiedName(),
                        new SymbolHit(enclosing, List.copyOf(symbols.getSymbols()), classpath));
            }
        }
        if (hits.isEmpty()) {
            return "No symbols found matching: " + name;
        }

        // Count every hit, cap only what is printed, and emit ONE total for the whole answer. Resolving the source file
        // is the expensive part and still happens only for rows that are printed.
        List<SymbolHit> sorted = hits.values().stream()
                .sorted((a, b) -> a.enclosing().getQualifiedName().compareTo(b.enclosing().getQualifiedName()))
                .toList();
        StringBuilder sb = new StringBuilder();
        sorted.stream().limit(MAX_TYPE_HITS).forEach(hit -> {
            FileObject src = SourceUtils.getFile(hit.enclosing(), hit.classpath());
            File f = src != null ? FileUtil.toFile(src) : null;
            sb.append(hit.enclosing().getQualifiedName()).append(": [")
                    .append(String.join(", ", hit.symbols())).append("]  →  ")
                    .append(f != null ? f.getPath() : "[binary]").append("\n");
        });
        return "Found " + sorted.size() + " type(s) with matching symbols"
                + (sorted.size() > MAX_TYPE_HITS ? " (showing first " + MAX_TYPE_HITS + ")" : "")
                + ":\n\n" + sb;
    }

    public static String findDeclaration(String filePath, int line, int column) {
        String missingPath = requireFilePathForLineLookup(filePath);
        if (missingPath != null) {
            return missingPath;
        }
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath : "No projects open";
        }
        JavaSource js = JavaSource.forFileObject(fo);
        if (js == null) {
            return "Not a Java source file: " + filePath;
        }

        AtomicReference<String> result = new AtomicReference<>();
        try {
            js.runUserActionTask(ci -> {
                ci.toPhase(JavaSource.Phase.RESOLVED);
                if (JavaSourceUtils.lineStart(ci, line) < 0) {
                    result.set("Line " + line + " is out of range");
                    return;
                }
                // An explicit column is honoured exactly; column <= 1 means the caller did not specify one (the tool
                // defaults it to 1), so find the line's first resolvable identifier rather than its first token.
                int offset = column > 1
                        ? JavaSourceUtils.lineOffset(ci, line, column)
                        : JavaSourceUtils.firstElementOffsetOnLine(ci, line);
                if (offset < 0) {
                    result.set("No Java element at line " + line);
                    return;
                }
                Element method = column > 1 ? null : JavaSourceUtils.methodDeclaredOnLine(ci, line);
                Element element = method != null ? method : JavaSourceUtils.elementAt(ci, offset);
                if (element == null) {
                    result.set("No Java element at line " + line);
                    return;
                }
                com.sun.source.tree.LineMap lm = ci.getCompilationUnit().getLineMap();

                // Same-file declaration
                com.sun.source.tree.Tree declTree = ci.getTrees().getTree(element);
                if (declTree != null) {
                    long pos = ci.getTrees().getSourcePositions()
                            .getStartPosition(ci.getCompilationUnit(), declTree);
                    if (pos >= 0) {
                        File f = FileUtil.toFile(fo);
                        result.set((f != null ? f.getPath() : fo.getPath())
                                + ":" + lm.getLineNumber(pos));
                        return;
                    }
                }

                // Cross-file declaration
                FileObject srcFile = SourceUtils.getFile(ElementHandle.create(element), ci.getClasspathInfo());
                if (srcFile == null) {
                    result.set("[binary] " + element);
                    return;
                }
                JavaSource declJs = JavaSource.forFileObject(srcFile);
                if (declJs == null) {
                    File f = FileUtil.toFile(srcFile);
                    result.set((f != null ? f.getPath() : srcFile.getPath()) + ":1");
                    return;
                }
                ElementHandle<?> handle = ElementHandle.create(element);
                AtomicReference<String> inner = new AtomicReference<>();
                try {
                    declJs.runUserActionTask(declCi -> {
                        declCi.toPhase(JavaSource.Phase.RESOLVED);
                        Element resolved = handle.resolve(declCi);
                        if (resolved == null) {
                            return;
                        }
                        com.sun.source.tree.Tree t = declCi.getTrees().getTree(resolved);
                        if (t == null) {
                            return;
                        }
                        long pos = declCi.getTrees().getSourcePositions()
                                .getStartPosition(declCi.getCompilationUnit(), t);
                        if (pos < 0) {
                            return;
                        }
                        long ln = declCi.getCompilationUnit().getLineMap().getLineNumber(pos);
                        File f = FileUtil.toFile(srcFile);
                        inner.set((f != null ? f.getPath() : srcFile.getPath()) + ":" + ln);
                    }, true);
                }
                catch (IOException e) {
                    LOG.log(Level.FINE, "FindDeclaration inner task error", e);
                }
                File f = FileUtil.toFile(srcFile);
                result.set(inner.get() != null ? inner.get()
                        : (f != null ? f.getPath() : srcFile.getPath()) + ":1");
            }, true);
        }
        catch (IOException e) {
            return "Error: " + e.getMessage();
        }
        return result.get() != null ? result.get() : "Declaration not found";
    }

    public static String findImplementations(String filePath, int line) {
        String missingPath = requireFilePathForLineLookup(filePath);
        if (missingPath != null) {
            return missingPath;
        }
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath : "No projects open";
        }
        JavaSource js = JavaSource.forFileObject(fo);
        if (js == null) {
            return "Not a Java source file: " + filePath;
        }

        AtomicReference<ElementHandle<TypeElement>> typeHandleRef = new AtomicReference<>();
        try {
            js.runUserActionTask(ci -> {
                ci.toPhase(JavaSource.Phase.RESOLVED);
                TreePath tp = JavaSourceUtils.classAtLine(ci, line);
                if (tp == null) {
                    return;
                }
                Element el = ci.getTrees().getElement(tp);
                if (el instanceof TypeElement te) {
                    String fqn = te.getQualifiedName().toString();
                    TypeElement resolved = ci.getElements().getTypeElement(fqn);
                    typeHandleRef.set(ElementHandle.create(resolved != null ? resolved : te));
                }
            }, true);
        }
        catch (IOException e) {
            return "Error: " + e.getMessage();
        }

        ElementHandle<TypeElement> typeHandle = typeHandleRef.get();
        if (typeHandle == null) {
            return "No type declaration found at line " + line;
        }

        ClassIndex ci = js.getClasspathInfo().getClassIndex();
        Set<ElementHandle<TypeElement>> implementors = ci.getElements(
                typeHandle,
                EnumSet.of(ClassIndex.SearchKind.IMPLEMENTORS),
                EnumSet.of(ClassIndex.SearchScope.SOURCE));

        if (implementors == null || implementors.isEmpty()) {
            return "No implementations found for " + typeHandle.getQualifiedName() + " in project source";
        }

        StringBuilder sb = new StringBuilder("Found ").append(implementors.size())
                .append(" implementation(s) of ").append(typeHandle.getQualifiedName())
                .append(" (direct subtypes only):\n\n");
        implementors.stream()
                .sorted((a, b) -> a.getQualifiedName().compareTo(b.getQualifiedName()))
                .forEach(h -> {
                    FileObject src = SourceUtils.getFile(h, js.getClasspathInfo());
                    File f = src != null ? FileUtil.toFile(src) : null;
                    sb.append(h.getQualifiedName()).append("  →  ")
                            .append(f != null ? f.getPath() : "[binary]").append("\n");
                });
        return sb.toString();
    }

    static String validateNamePattern(String name, String kind) {
        if (name == null || name.isBlank() || kind == null || !"regexp".equalsIgnoreCase(kind)) {
            return null;
        }
        try {
            Pattern.compile(name);
            return null;
        }
        catch (PatternSyntaxException e) {
            return "Invalid regex: " + e.getMessage();
        }
    }

    /**
     * Decides what a search covers.
     * <p>
     * With no {@code filePath} this is every open project's Java source roots — unchanged, and independently confirmed
     * to work: a token occurring only in {@code src/main} is found from the default path.
     * <p>
     * With a {@code filePath} it is every source root of the project that OWNS that file, which is the fix. Previously
     * the anchor file's own {@code ClasspathInfo} was used, meaning its single source root, so anchoring on a main file
     * silently discarded every test type and vice versa — while the schema described the parameter as scoping to a
     * project. Widening to the owning project keeps the parameter's real purpose, which is choosing ONE project when
     * several are open, without dropping half of it.
     */
    private static SearchAnchors resolveSearchAnchors(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            List<FileObject> anchors = javaAnchors(openProjectSourceRoots());
            return anchors.isEmpty()
                    ? new SearchAnchors("No projects open", List.of())
                    : new SearchAnchors(null, anchors);
        }
        FileObject fo = FileUtils.resolveByPath(filePath);
        if (fo == null) {
            return new SearchAnchors("File not found: " + filePath, List.of());
        }
        if (JavaSource.forFileObject(fo) == null) {
            return new SearchAnchors("Not a Java source file: " + filePath, List.of());
        }
        // Fall back to the supplied file itself when the project's roots cannot be resolved: that is exactly the old
        // narrower behaviour, which is worse but never wrong-by-crash.
        List<FileObject> anchors = javaAnchors(owningProjectSourceRoots(fo));
        return new SearchAnchors(null, anchors.isEmpty() ? List.of(fo) : anchors);
    }

    private static FileObject findJavaSource(FileObject folder) {
        if (folder == null) {
            return null;
        }
        if (!folder.isFolder()) {
            return "java".equals(folder.getExt()) ? folder : null;
        }
        for (FileObject child : folder.getChildren()) {
            FileObject found = findJavaSource(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static ClassIndex.NameKind toNameKind(String kind) {
        if (kind == null) {
            return ClassIndex.NameKind.PREFIX;
        }
        return switch (kind.toLowerCase()) {
            case "exact" ->
                ClassIndex.NameKind.SIMPLE_NAME;
            case "camelcase" ->
                ClassIndex.NameKind.CAMEL_CASE;
            case "regexp" ->
                ClassIndex.NameKind.REGEXP;
            default ->
                ClassIndex.NameKind.PREFIX;
        };
    }

    /**
     * Java source roots of every open project, used when no {@code filePath} narrows the search.
     *
     * <p>
     * This used to fall back to whatever file the editor happened to have focused and take its SOURCE classpath. That
     * made a project-wide search depend on unrelated editor state: with a non-source file in front — a pom, a README —
     * {@code ClassPath.getClassPath} returns null and every search failed, reporting "Cannot resolve source classpath
     * for: null" because the caller's own filePath was still null at that point. Searching the open projects is what
     * the caller asked for when they omitted a path.
     */
    /**
     * Every Java source root of the project that owns {@code fo}.
     * <p>
     * This is the fix for a silent-omission defect. An anchored search used to take
     * {@code JavaSource.forFileObject(fo)} and query that ONE file's {@code ClasspathInfo}, which is its own source
     * root — so anchoring on a {@code src/main} file dropped every {@code src/test} type, and anchoring on a test file
     * dropped every main type, despite both being the same Maven project and the schema calling {@code filePath} "any
     * source file in the target project". Nothing in the output said a root had been excluded. Two peers hit this
     * independently on three different tools: 2 types instead of 7, and a main-only query returning 0 from a test
     * anchor.
     * <p>
     * Widening to the owning project — rather than to every open project — is what keeps the parameter meaningful: its
     * purpose is to pick ONE project when several are open.
     * <p>
     * {@code FileOwnerQuery.getOwner} is contained by {@code catch(Throwable)} for the same reason as
     * {@code GitProvider.resolveRootForFile}: it throws
     * {@code ExceptionInInitializerError}/{@code NoClassDefFoundError} — Errors, not Exceptions — when the IDE's
     * ProjectManager Lookup is unavailable. Falling back to the file's own root then restores exactly the old
     * behaviour, which is narrower than ideal but never wrong-by-crash.
     */
    private static List<FileObject> owningProjectSourceRoots(FileObject fo) {
        try {
            Project owner = FileOwnerQuery.getOwner(fo);
            if (owner != null) {
                List<FileObject> roots = new ArrayList<>();
                for (SourceGroup group : ProjectUtils.getSources(owner).getSourceGroups("java")) {
                    roots.add(group.getRootFolder());
                }
                if (!roots.isEmpty()) {
                    return roots;
                }
            }
        }
        catch (Throwable t) {
            LOG.log(Level.FINE, "Project owner lookup unavailable; anchoring the search on the file's own root", t);
        }
        return List.of();
    }

    /**
     * One Java file per root, to anchor a {@code ClasspathInfo} query at each. A root with no Java source in it is
     * skipped rather than failing the whole search.
     */
    private static List<FileObject> javaAnchors(List<FileObject> roots) {
        List<FileObject> anchors = new ArrayList<>();
        for (FileObject root : roots) {
            FileObject anchor = findJavaSource(root);
            if (anchor != null) {
                anchors.add(anchor);
            }
        }
        return anchors;
    }

    private static List<FileObject> openProjectSourceRoots() {
        List<FileObject> roots = new ArrayList<>();
        for (Project project : OpenProjects.getDefault().getOpenProjects()) {
            SourceGroup[] groups = ProjectUtils.getSources(project).getSourceGroups("java");
            for (SourceGroup group : groups) {
                roots.add(group.getRootFolder());
            }
            if (groups.length == 0) {
                // Non-Java project, or one whose source groups are not registered:
                // walking the project directory still beats returning nothing.
                roots.add(project.getProjectDirectory());
            }
        }
        return roots;
    }

    /**
     * Resolves the file that anchors a search to a project's classpath.
     *
     * <p>
     * When no path is given this used to take whatever file the editor had focused. That made results depend on where
     * the user's cursor happened to be — a caller asking the same question twice could get different answers, and the
     * caller had no way to know which file it had actually searched. The anchor is now the first open project's source
     * root: still a fallback, but a deterministic one that does not move while the user clicks around. Callers that
     * need a specific project should pass {@code filePath}.
     */
    /**
     * Refuses a line-based lookup that was given no file.
     * <p>
     * {@link #resolveFileObject} falls back to the first open project's SOURCE ROOT, which is a directory.
     * {@code JavaSource.forFileObject} returns null for a directory, so the documented "omit filePath" fallback could
     * never work for the two line-based tools: it reported {@code "Not a Java source file: null"}, quoting the null
     * path back at the caller. Picking some arbitrary first Java file instead would be worse — a line number resolved
     * against a file the caller never named produces a confident WRONG answer rather than an error. A line only means
     * something relative to a specific file, so the honest contract is to require one.
     * <p>
     * {@code SearchTypes}/{@code SearchSymbols} never reach this: they route a null path to
     * {@link #searchAcrossOpenProjects} first, where searching every root genuinely is meaningful.
     */
    private static String requireFilePathForLineLookup(String filePath) {
        return filePath == null || filePath.isBlank()
                ? McpToolPropertyEnum.FILE_PATH.key() + " is required — a line number can only be resolved against a "
                + "specific file. Call " + McpToolEnum.GET_CURRENT_FILE.toolName()
                + " if you want the file the user is looking at."
                : null;
    }

    private static FileObject resolveFileObject(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            List<FileObject> roots = openProjectSourceRoots();
            return roots.isEmpty() ? null : roots.get(0);
        }
        return FileUtils.resolveByPath(filePath);
    }

    private SearchProvider() {
    }

    /**
     * A type hit plus the classpath it was found through, which {@code SourceUtils.getFile} needs to resolve its source
     * file. Once results are merged across roots the two can no longer be assumed to come from the same anchor.
     */
    private record TypeHit(ElementHandle<TypeElement> handle, ClasspathInfo classpath) {

    }

    private record SymbolHit(ElementHandle<TypeElement> enclosing, List<String> symbols, ClasspathInfo classpath) {

    }

    /**
     * The set of files to anchor {@code ClasspathInfo} queries at, or the error to return instead.
     */
    private record SearchAnchors(String error, List<FileObject> anchors) {

    }
}

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.SourceUtils;
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
        List<FileObject> roots;
        if (filePath != null && !filePath.isBlank()) {
            FileObject fo = resolveFileObject(filePath);
            if (fo == null) {
                return "File not found: " + filePath;
            }
            ClassPath cp = ClassPath.getClassPath(fo, ClassPath.SOURCE);
            if (cp == null) {
                return "Cannot resolve source classpath for: " + filePath;
            }
            roots = List.of(cp.getRoots());
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

        String glob = (filePattern == null || filePattern.isBlank()) ? "*.java" : filePattern;
        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);

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
        if (filePath == null || filePath.isBlank()) {
            return searchAcrossOpenProjects(name, kind, includeDeps, true);
        }
        if (name == null || name.isBlank()) {
            return "name is required";
        }
        String invalidPattern = validateNamePattern(name, kind);
        if (invalidPattern != null) {
            return invalidPattern;
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

        Set<ClassIndex.SearchScope> scopes = includeDeps
                ? EnumSet.of(ClassIndex.SearchScope.SOURCE, ClassIndex.SearchScope.DEPENDENCIES)
                : EnumSet.of(ClassIndex.SearchScope.SOURCE);

        ClassIndex ci = js.getClasspathInfo().getClassIndex();
        Set<ElementHandle<TypeElement>> results = ci.getDeclaredTypes(name, toNameKind(kind), scopes);
        if (results == null || results.isEmpty()) {
            return "No types found matching: " + name;
        }

        List<ElementHandle<TypeElement>> sorted = results.stream()
                .sorted((a, b) -> a.getQualifiedName().compareTo(b.getQualifiedName()))
                .limit(MAX_TYPE_HITS)
                .toList();

        // Report the true total, not the capped one. Saying "Found 100 (showing
        // first 100)" tells the caller it was truncated but not by how much, so
        // it cannot judge whether to narrow the query or accept the sample —
        // 101 results and 10,000 read identically. SearchInFiles already counts
        // every match and caps only what it prints; this now matches.
        StringBuilder sb = new StringBuilder("Found ").append(results.size())
                .append(" type(s)");
        if (results.size() > MAX_TYPE_HITS) {
            sb.append(" (showing first ").append(MAX_TYPE_HITS).append(")");
        }
        sb.append(":\n\n");
        for (ElementHandle<TypeElement> h : sorted) {
            FileObject src = SourceUtils.getFile(h, js.getClasspathInfo());
            File f = src != null ? FileUtil.toFile(src) : null;
            sb.append(h.getQualifiedName()).append("  →  ")
                    .append(f != null ? f.getPath() : "[binary]").append("\n");
        }
        return sb.toString();
    }

    public static String searchSymbols(String filePath, String name, String kind, boolean includeDeps) {
        if (filePath == null || filePath.isBlank()) {
            return searchAcrossOpenProjects(name, kind, includeDeps, false);
        }
        if (name == null || name.isBlank()) {
            return "name is required";
        }
        String invalidPattern = validateNamePattern(name, kind);
        if (invalidPattern != null) {
            return invalidPattern;
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

        Set<ClassIndex.SearchScope> scopes = includeDeps
                ? EnumSet.of(ClassIndex.SearchScope.SOURCE, ClassIndex.SearchScope.DEPENDENCIES)
                : EnumSet.of(ClassIndex.SearchScope.SOURCE);

        ClassIndex ci = js.getClasspathInfo().getClassIndex();
        Iterable<ClassIndex.Symbols> results = ci.getDeclaredSymbols(name, toNameKind(kind), scopes);
        if (results == null) {
            return "No symbols found matching: " + name;
        }

        // Keep counting past the display cap so the total is the real one. The
        // loop used to break at MAX_TYPE_HITS, which meant the answer was always
        // "Found 100 ... showing first 100" — truncation was visible but its
        // scale was not, and the caller could not tell 101 matches from 10,000.
        // Only the formatting is capped; resolving the source file is the
        // expensive part and still happens only for rows that are printed.
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        int total = 0;
        for (ClassIndex.Symbols sym : results) {
            total++;
            if (shown >= MAX_TYPE_HITS) {
                continue;
            }
            ElementHandle<TypeElement> enclosing = sym.getEnclosingType();
            FileObject src = SourceUtils.getFile(enclosing, js.getClasspathInfo());
            File f = src != null ? FileUtil.toFile(src) : null;
            sb.append(enclosing.getQualifiedName()).append(": [")
                    .append(String.join(", ", sym.getSymbols())).append("]  →  ")
                    .append(f != null ? f.getPath() : "[binary]").append("\n");
            shown++;
        }
        if (total == 0) {
            return "No symbols found matching: " + name;
        }
        return "Found " + total + " type(s) with matching symbols"
                + (total > MAX_TYPE_HITS ? " (showing first " + MAX_TYPE_HITS + ")" : "")
                + ":\n\n" + sb;
    }

    public static String findDeclaration(String filePath, int line, int column) {
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
                int offset = JavaSourceUtils.lineOffset(ci, line, column);
                if (offset < 0) {
                    result.set("Line " + line + " is out of range");
                    return;
                }
                Element element = JavaSourceUtils.elementAt(ci, offset);
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
                int sp = JavaSourceUtils.lineStart(ci, line);
                if (sp < 0) {
                    return;
                }
                TreePath tp = JavaSourceUtils.enclosingClass(
                        ci.getTreeUtilities().pathFor(sp));
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

    private static String searchAcrossOpenProjects(String name, String kind, boolean includeDeps,
            boolean types) {
        if (name == null || name.isBlank()) {
            return "name is required";
        }
        List<String> results = new ArrayList<>();
        for (FileObject root : openProjectSourceRoots()) {
            FileObject anchor = findJavaSource(root);
            if (anchor == null) {
                continue;
            }
            String result = types
                    ? searchTypes(anchor.getPath(), name, kind, includeDeps)
                    : searchSymbols(anchor.getPath(), name, kind, includeDeps);
            if (!result.startsWith("No types found") && !result.startsWith("No symbols found")) {
                results.add(result);
            }
        }
        if (results.isEmpty()) {
            return types ? "No types found matching: " + name
                    : "No symbols found matching: " + name;
        }
        return String.join("\n", results);
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
    private static FileObject resolveFileObject(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            List<FileObject> roots = openProjectSourceRoots();
            return roots.isEmpty() ? null : roots.get(0);
        }
        return FileUtils.resolveByPath(filePath);
    }

    private SearchProvider() {
    }
}

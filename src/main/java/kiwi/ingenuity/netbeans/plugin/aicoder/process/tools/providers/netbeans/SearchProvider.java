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
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath : "No editor focused";
        }
        ClassPath cp = ClassPath.getClassPath(fo, ClassPath.SOURCE);
        if (cp == null) {
            return "Cannot resolve source classpath for: " + filePath;
        }

        Pattern pattern;
        try {
            String expr = isRegex ? query : Pattern.quote(query);
            pattern = caseSensitive ? Pattern.compile(expr)
                    : Pattern.compile(expr, Pattern.CASE_INSENSITIVE);
        }
        catch (PatternSyntaxException e) {
            return "Invalid regex: " + e.getMessage();
        }

        String glob = (filePattern == null || filePattern.isBlank()) ? "*.java" : filePattern;
        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);

        List<SearchResultFormatter.Hit> hits = new ArrayList<>();
        int totalHits = 0;
        for (FileObject root : cp.getRoots()) {
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
                            if (pattern.matcher(lines.get(i)).find()) {
                                totalHits++;
                                if (hits.size() < MAX_FILE_HITS) {
                                    hits.add(new SearchResultFormatter.Hit(
                                            p.toString(), i + 1, lines.get(i).strip()));
                                }
                            }
                        }
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
        return SearchResultFormatter.groupByFile(hits, totalHits, MAX_FILE_HITS);
    }

    public static String searchTypes(String filePath, String name, String kind, boolean includeDeps) {
        if (name == null || name.isBlank()) {
            return "name is required";
        }
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath : "No editor focused";
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

        StringBuilder sb = new StringBuilder("Found ").append(Math.min(results.size(), MAX_TYPE_HITS))
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
        if (name == null || name.isBlank()) {
            return "name is required";
        }
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath : "No editor focused";
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

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (ClassIndex.Symbols sym : results) {
            if (count >= MAX_TYPE_HITS) {
                break;
            }
            ElementHandle<TypeElement> enclosing = sym.getEnclosingType();
            FileObject src = SourceUtils.getFile(enclosing, js.getClasspathInfo());
            File f = src != null ? FileUtil.toFile(src) : null;
            sb.append(enclosing.getQualifiedName()).append(": [")
                    .append(String.join(", ", sym.getSymbols())).append("]  →  ")
                    .append(f != null ? f.getPath() : "[binary]").append("\n");
            count++;
        }
        if (count == 0) {
            return "No symbols found matching: " + name;
        }
        return "Found " + count + " type(s) with matching symbols"
                + (count >= MAX_TYPE_HITS ? " (showing first " + MAX_TYPE_HITS + ")" : "")
                + ":\n\n" + sb;
    }

    public static String findDeclaration(String filePath, int line, int column) {
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath : "No editor focused";
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
                    ? "File not found: " + filePath : "No editor focused";
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

    private static FileObject resolveFileObject(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            filePath = EditorContextProvider.getCurrentFilePath();
            if (filePath == null) {
                return null;
            }
        }
        return FileUtils.resolveByPath(filePath);
    }

    private SearchProvider() {
    }
}

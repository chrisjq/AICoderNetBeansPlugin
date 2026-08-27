package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.concurrent.atomic.AtomicReference;
import javax.lang.model.element.Element;
import org.netbeans.api.java.source.CompilationController;

class JavaSourceUtils {

    /**
     * Returns the source offset of the first character of the given 1-based line, or -1 if the line is out of range.
     */
    static int lineStart(CompilationController cc, int line) {
        try {
            long pos = cc.getCompilationUnit().getLineMap().getStartPosition(line);
            return pos < 0 ? -1 : (int) pos;
        }
        catch (Exception e) {
            // javac's LineMap throws (e.g. ArrayIndexOutOfBounds) for out-of-range lines
            return -1;
        }
    }

    /**
     * Returns the source offset for a 1-based line and column. When column is <= 1 (unspecified), advances past leading
     * whitespace so pathFor() lands on a real token rather than block-level indentation. Returns -1 if the line is out
     * of range.
     */
    static int lineOffset(CompilationController cc, int line, int column) {
        int start = lineStart(cc, line);
        if (start < 0) {
            return -1;
        }
        int offset = start + Math.max(0, column - 1);
        if (column <= 1) {
            CharSequence src = cc.getSnapshot().getText();
            while (offset < src.length()
                    && (src.charAt(offset) == ' ' || src.charAt(offset) == '\t')) {
                offset++;
            }
        }
        return offset;
    }

    /**
     * Offset of the first identifier on a 1-based line that actually resolves to a Java element, or -1 if the line has
     * none. Used when the caller omits {@code column}.
     * <p>
     * {@link #lineOffset} only skips leading whitespace, so it lands on the first TOKEN. On most real lines that token
     * is a keyword or modifier — {@code public}, {@code return}, {@code if} — which resolves to no element, so omitting
     * column reported "No Java element" on lines that plainly contain several. The documented contract is the first
     * IDENTIFIER, so scan the line and take the first position that genuinely resolves.
     * <p>
     * Only identifier STARTS are probed, and a failed identifier is skipped whole, so the scan costs one lookup per
     * identifier rather than one per character.
     */
    static int firstElementOffsetOnLine(CompilationController cc, int line) {
        int start = lineStart(cc, line);
        if (start < 0) {
            return -1;
        }
        CharSequence src = cc.getSnapshot().getText();
        int i = start;
        while (i < src.length() && src.charAt(i) != '\n') {
            if (Character.isJavaIdentifierStart(src.charAt(i))
                    && (i == start || !Character.isJavaIdentifierPart(src.charAt(i - 1)))) {
                if (elementAt(cc, i) != null) {
                    return i;
                }
                while (i < src.length() && Character.isJavaIdentifierPart(src.charAt(i))) {
                    i++;
                }
                continue;
            }
            i++;
        }
        return -1;
    }

    /**
     * Returns a method declared on the requested line. This takes precedence when callers omit a column: the first
     * resolvable identifier on {@code public String method(...)} is often {@code String}, whose declaration is the
     * type, not the method the caller pointed at.
     */
    static Element methodDeclaredOnLine(CompilationController cc, int line) {
        CompilationUnitTree cu = cc.getCompilationUnit();
        SourcePositions positions = cc.getTrees().getSourcePositions();
        LineMap lineMap = cu.getLineMap();
        AtomicReference<Element> result = new AtomicReference<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                long start = positions.getStartPosition(cu, node);
                long end = positions.getEndPosition(cu, node);
                if (result.get() == null && start >= 0 && end >= 0
                        && line >= lineMap.getLineNumber(start) && line <= lineMap.getLineNumber(end)) {
                    int lineStart = JavaSourceUtils.lineStart(cc, line);
                    int lineEnd = lineStart;
                    CharSequence source = cc.getSnapshot().getText();
                    while (lineEnd < source.length() && source.charAt(lineEnd) != '\n') {
                        lineEnd++;
                    }
                    String lineText = source.subSequence(lineStart, lineEnd).toString();
                    if (lineText.contains(node.getName().toString())) {
                        result.set(cc.getTrees().getElement(getCurrentPath()));
                    }
                }
                return super.visitMethod(node, unused);
            }
        }.scan(cu, null);
        return result.get();
    }

    /**
     * Returns the Java element at the given source offset, or null if none.
     */
    static Element elementAt(CompilationController cc, int offset) {
        TreePath tp = cc.getTreeUtilities().pathFor(offset);
        return tp != null ? cc.getTrees().getElement(tp) : null;
    }

    /**
     * Returns the class or interface declared at, or enclosing, the given 1-based line.
     * <p>
     * Resolving this by asking {@code pathFor(lineStart(line))} does NOT work and was a real defect: at the class
     * declaration line the offset is column 0, which sits on the ClassTree's own start boundary, and the path returned
     * from there has no enclosing class. The effect was that pointing at the declaration line — exactly what the tool
     * documentation tells callers to do — always failed, while pointing one line lower (inside the body) always worked.
     * Verified across six files, two interfaces and four abstract classes.
     * <p>
     * Matching on the tree's own line span instead removes the dependency on {@code pathFor}'s boundary behaviour. A
     * class whose declaration starts on {@code line} wins outright; otherwise the innermost class whose span contains
     * the line is used, so a caller pointing anywhere inside a type still resolves it, and a nested class beats its
     * outer class. The span fallback also covers a declaration preceded by javadoc or annotations, where javac reports
     * the tree as starting at the comment rather than at the {@code class} keyword.
     */
    static TreePath classAtLine(CompilationController cc, int line) {
        CompilationUnitTree cu = cc.getCompilationUnit();
        SourcePositions positions = cc.getTrees().getSourcePositions();
        LineMap lineMap = cu.getLineMap();
        AtomicReference<TreePath> exact = new AtomicReference<>();
        AtomicReference<TreePath> spanning = new AtomicReference<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree node, Void unused) {
                long start = positions.getStartPosition(cu, node);
                long end = positions.getEndPosition(cu, node);
                if (start >= 0 && end >= 0) {
                    long startLine = lineMap.getLineNumber(start);
                    long endLine = lineMap.getLineNumber(end);
                    if (startLine == line && exact.get() == null) {
                        exact.set(getCurrentPath());
                    }
                    if (line >= startLine && line <= endLine) {
                        // Scanning descends, so the innermost containing class is written last and wins.
                        spanning.set(getCurrentPath());
                    }
                }
                return super.visitClass(node, unused);
            }
        }.scan(cu, null);
        return exact.get() != null ? exact.get() : spanning.get();
    }

    /**
     * Walks a TreePath up to the nearest enclosing ClassTree, returning null if none.
     */
    static TreePath enclosingClass(TreePath tp) {
        while (tp != null && !(tp.getLeaf() instanceof com.sun.source.tree.ClassTree)) {
            tp = tp.getParentPath();
        }
        return tp;
    }

    private JavaSourceUtils() {
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.sun.source.util.TreePath;
import javax.lang.model.element.Element;
import org.netbeans.api.java.source.CompilationController;

class JavaSourceUtils {

    /**
     * Returns the source offset of the first character of the given 1-based
     * line, or -1 if the line is out of range.
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
     * Returns the source offset for a 1-based line and column. When column is
     * <= 1 (unspecified), advances past leading whitespace so pathFor() lands
     * on a real token rather than block-level indentation. Returns -1 if the
     * line is out of range.
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
     * Returns the Java element at the given source offset, or null if none.
     */
    static Element elementAt(CompilationController cc, int offset) {
        TreePath tp = cc.getTreeUtilities().pathFor(offset);
        return tp != null ? cc.getTrees().getElement(tp) : null;
    }

    /**
     * Walks a TreePath up to the nearest enclosing ClassTree, returning null if
     * none.
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

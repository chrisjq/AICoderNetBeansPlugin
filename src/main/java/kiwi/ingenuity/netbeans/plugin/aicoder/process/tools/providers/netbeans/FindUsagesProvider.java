package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.sun.source.util.TreePath;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.TreePathHandle;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.api.RefactoringElement;
import org.netbeans.modules.refactoring.api.RefactoringSession;
import org.netbeans.modules.refactoring.api.WhereUsedQuery;
import org.netbeans.modules.refactoring.java.api.WhereUsedQueryConstants;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.text.PositionBounds;
import org.openide.util.lookup.Lookups;

public class FindUsagesProvider {

    private static final Logger LOG = Logger.getLogger(FindUsagesProvider.class.getName());

    public static String findUsages(String className, String memberName,
            boolean findSubclasses, boolean directSubclassesOnly, boolean searchInComments) {
        if (className == null || className.isBlank()) {
            return "className is required";
        }
        // Normalise inner class notation: com.example.Outer$Inner → com.example.Outer.Inner
        String normalizedName = className.replace('$', '.');

        FileObject targetFile = FileUtils.locateSourceFile(normalizedName);
        if (targetFile == null) {
            return "Source file not found for " + normalizedName
                    + " — only classes in the open project can be searched.";
        }
        JavaSource js = JavaSource.forFileObject(targetFile);
        if (js == null) {
            return "Cannot create JavaSource for " + normalizedName;
        }

        // Resolve a TreePathHandle for the class declaration (or a specific member)
        AtomicReference<TreePathHandle> handleRef = new AtomicReference<>();
        try {
            js.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.RESOLVED);
                TypeElement te = cc.getElements().getTypeElement(normalizedName);
                if (te == null) {
                    return;
                }
                if (memberName != null && !memberName.isBlank()) {
                    for (Element enc : te.getEnclosedElements()) {
                        if (enc.getSimpleName().toString().equals(memberName)) {
                            TreePath path = cc.getTrees().getPath(enc);
                            if (path != null) {
                                handleRef.set(TreePathHandle.create(path, cc));
                                break;
                            }
                        }
                    }
                }
                else {
                    TreePath path = cc.getTrees().getPath(te);
                    if (path != null) {
                        handleRef.set(TreePathHandle.create(path, cc));
                    }
                }
            }, true);
        }
        catch (IOException e) {
            return "Error: " + e.getMessage();
        }

        TreePathHandle handle = handleRef.get();
        if (handle == null) {
            String target = memberName != null && !memberName.isBlank()
                    ? normalizedName + "." + memberName : normalizedName;
            return "Element not found: " + target;
        }

        // WhereUsedQuery is the proper NB Find Usages API — same pattern as RenameRefactoring.
        WhereUsedQuery query = new WhereUsedQuery(Lookups.fixed(handle, targetFile));
        query.putValue(WhereUsedQuery.FIND_REFERENCES, true);
        query.putValue(WhereUsedQuery.SEARCH_IN_COMMENTS, searchInComments);
        if (findSubclasses) {
            query.putValue(WhereUsedQueryConstants.FIND_SUBCLASSES, true);
            if (directSubclassesOnly) {
                query.putValue(WhereUsedQueryConstants.FIND_DIRECT_SUBCLASSES, true);
            }
        }

        try {
            Problem p = query.preCheck();
            if (p != null && p.isFatal()) {
                return "Query blocked: " + p.getMessage();
            }
            RefactoringSession session = RefactoringSession.create("Find Usages");
            try {
                p = query.prepare(session);
                if (p != null && p.isFatal()) {
                    return "Query blocked: " + p.getMessage();
                }

                Collection<RefactoringElement> elements = session.getRefactoringElements();
                String target = memberName != null && !memberName.isBlank()
                        ? normalizedName + "." + memberName : normalizedName;
                if (elements == null || elements.isEmpty()) {
                    return "No usages found for " + target;
                }

                StringBuilder sb = new StringBuilder();
                sb.append("Found ").append(elements.size()).append(" usage(s) of ")
                        .append(target).append(":\n\n");
                for (RefactoringElement elem : elements) {
                    FileObject fo = elem.getParentFile();
                    File f = fo != null ? FileUtil.toFile(fo) : null;
                    String path = f != null ? f.getPath() : (fo != null ? fo.getPath() : "?");
                    PositionBounds pos = elem.getPosition();
                    int line = (pos != null && fo != null)
                            ? offsetToLine(fo, pos.getBegin().getOffset()) : 1;
                    String text = elem.getText();
                    if (text == null || text.isBlank()) {
                        text = elem.getDisplayText();
                    }
                    sb.append(path).append(":").append(line)
                            .append("  →  ").append(text != null ? text.strip() : "").append("\n");
                }
                return sb.toString();
            }
            finally {
                session.finished();
            }
        }
        catch (Exception e) {
            String msg = e.getMessage();
            return "FindUsages error: " + (msg != null ? msg : e.getClass().getName());
        }
    }

    private static int offsetToLine(FileObject fo, int offset) {
        try {
            File f = FileUtil.toFile(fo);
            if (f == null) {
                return 1;
            }
            String content = Files.readString(f.toPath());
            int line = 1;
            int end = Math.min(offset, content.length());
            for (int i = 0; i < end; i++) {
                if (content.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "offsetToLine error", e);
            return 1;
        }
    }

    private FindUsagesProvider() {
    }

}

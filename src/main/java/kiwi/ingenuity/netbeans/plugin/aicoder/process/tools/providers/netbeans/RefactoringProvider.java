package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.Action;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyledDocument;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.TreePathHandle;
import org.netbeans.modules.editor.indent.api.Reformat;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.MoveRefactoring;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.api.RefactoringSession;
import org.netbeans.modules.refactoring.api.RenameRefactoring;
import org.netbeans.modules.refactoring.java.api.ChangeParametersRefactoring;
import org.netbeans.modules.refactoring.java.api.ChangeParametersRefactoring.ParameterInfo;
import org.netbeans.modules.refactoring.java.api.InlineRefactoring;
import org.openide.cookies.EditorCookie;
import org.openide.cookies.SaveCookie;
import org.openide.filesystems.FileAlreadyLockedException;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectNotFoundException;
import org.openide.util.lookup.Lookups;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

public class RefactoringProvider {

    private static final String RUN_INSPECT_ACTION
            = "Actions/Source/org-netbeans-modules-analysis-RunAnalysisAction.instance";
    private static final String FIX_IMPORTS_ACTION
            = "Editors/text/x-java/Actions/fix-imports.instance";
    private static final String ORGANISE_IMPORTS_ACTION
            = "Editors/text/x-java/Actions/organize-imports.instance";
    private static final String ORGANISE_MEMBERS_ACTION
            = "Editors/text/x-java/Actions/organize-members.instance";

    public static String renameSymbol(String filePath, int line, String newName) {
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath : "No editor focused";
        }
        TreePathHandle handle = resolveHandle(fo, line > 0 ? line : 1);
        if (handle == null) {
            return "Cannot resolve Java element at " + pos(filePath, line);
        }
        RenameRefactoring r = new RenameRefactoring(Lookups.fixed(handle, fo));
        r.setNewName(newName);
        String err = runRefactoring(r);
        return err != null ? "Refactoring blocked: " + err : "Renamed to '" + newName + "'";
    }

    public static String moveClass(String filePath, int line, String targetPackage) {
        if (targetPackage == null || targetPackage.isBlank()) {
            return "Error: targetPackage is required";
        }
        if (!isValidJavaPackageName(targetPackage)) {
            return "Error: invalid target package name '" + targetPackage + "'";
        }
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath : "No editor focused";
        }
        FileObject targetFolder = findOrCreatePackage(fo, targetPackage);
        if (targetFolder == null) {
            return "Cannot resolve source root for: " + filePath;
        }
        // Use fo directly (not DataObject) so the Java plugin uses the fresh
        // FileObject rather than a potentially stale cached DataObject primary file.
        MoveRefactoring r = new MoveRefactoring(Lookups.singleton(fo));
        r.setTarget(Lookups.singleton(targetFolder.toURL()));
        String err = runRefactoring(r);
        return err != null ? "Refactoring blocked: " + err : "Moved to '" + targetPackage + "'";
    }

    public static String inlineVariable(String filePath, int line) {
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath : "No editor focused";
        }
        TreePathHandle handle = resolveHandle(fo, line > 0 ? line : 1);
        if (handle == null) {
            return "Cannot resolve Java element at " + pos(filePath, line);
        }
        // InlineRefactoring requires (TreePathHandle, Type); use TEMP for local variable inline
        InlineRefactoring r = new InlineRefactoring(handle, InlineRefactoring.Type.TEMP);
        String err = runRefactoring(r);
        return err != null ? "Refactoring blocked: " + err : "Inlined variable";
    }

    public static String changeMethodSignature(String filePath, int line, ParameterInfo[] parameters,
            String methodName, String returnType, Boolean overloadMethod) {
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath : "No editor focused";
        }
        TreePathHandle handle = resolveHandle(fo, line > 0 ? line : 1);
        if (handle == null) {
            return "Cannot resolve Java element at " + pos(filePath, line);
        }
        ChangeParametersRefactoring r = new ChangeParametersRefactoring(handle);
        // setParameterInfo must always be called — NB crashes with NPE if paramInfos is null.
        // When the caller omits parameters, preserve all existing params unchanged via ParameterInfo(i).
        r.setParameterInfo(parameters != null ? parameters : existingParamInfos(fo, handle));
        if (methodName != null && !methodName.isBlank()) {
            r.setMethodName(methodName);
        }
        if (returnType != null && !returnType.isBlank()) {
            r.setReturnType(returnType);
        }
        if (overloadMethod != null) {
            r.setOverloadMethod(overloadMethod);
        }
        String err = runRefactoring(r);
        return err != null ? "Refactoring blocked: " + err : "Method signature updated";
    }

    public static String fixImports(String filePath) {
        return runSourceAction(filePath, FIX_IMPORTS_ACTION, "FixImports");
    }

    public static String organiseImports(String filePath) {
        return runSourceAction(filePath, ORGANISE_IMPORTS_ACTION, "OrganiseImports");
    }

    public static String organiseMembers(String filePath) {
        return runSourceAction(filePath, ORGANISE_MEMBERS_ACTION, "OrganiseMembers");
    }

    public static String reformatFile(String filePath) {
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath : "No editor focused";
        }
        File diskFile = FileUtil.toFile(fo);
        if (diskFile == null) {
            return "Cannot reformat non-disk file: " + fo.getPath();
        }
        String navResult = EditorContextProvider.navigateToLine(diskFile.getPath(), 1, false);
        if (navResult.startsWith("File not found") || navResult.startsWith("Error")) {
            return navResult;
        }
        AtomicReference<String> result = new AtomicReference<>("File reformatted");
        try {
            SwingUtilities.invokeAndWait(() -> {
                JTextComponent editor = getEditorFor(fo);
                if (editor == null) {
                    result.set("No editor opened for file");
                    return;
                }
                Document doc = editor.getDocument();
                Reformat reformat = Reformat.get(doc);
                reformat.lock();
                try {
                    reformat.reformat(0, doc.getLength());
                }
                catch (BadLocationException e) {
                    result.set("Reformat error: " + e.getMessage());
                }
                finally {
                    reformat.unlock();
                }
                saveFo(fo);
            });
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
        catch (Exception e) {
            return "Error: " + e.getMessage();
        }
        return result.get();
    }

    public static String writeFileContent(String filePath, String content) {
        if (filePath == null || filePath.isBlank()) {
            return "filePath is required";
        }
        if (content == null) {
            return "content is required";
        }
        File f = new File(filePath);
        if (!f.exists()) {
            try {
                if (f.getParentFile() != null) {
                    f.getParentFile().mkdirs();
                }
                FileObject newFo = FileUtil.createData(f);
                try (OutputStream out = newFo.getOutputStream()) {
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
                GitProvider.refreshVcsStatus(filePath);
                return "File created and saved";
            }
            catch (IOException e) {
                return "Could not create file: " + e.getMessage();
            }
        }
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return "File not found: " + filePath;
        }
        // Apply the accepted change as exact bytes. Saving through the editor would
        // run NetBeans "On Save" tasks (reformat / trailing-whitespace removal) that
        // mutate the bytes and desync external tools tracking the file on disk. Only
        // fall back to the editor document when the file is locked (open + unsaved).
        try (OutputStream out = fo.getOutputStream()) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        catch (FileAlreadyLockedException lockEx) {
            String viaDoc = writeViaDocument(fo, content);
            GitProvider.refreshVcsStatus(filePath);
            return viaDoc;
        }
        catch (IOException e) {
            return "Write error: " + e.getMessage();
        }
        fo.refresh();
        GitProvider.refreshVcsStatus(filePath);
        return "File updated and saved";
    }

    public static String applyEdit(String filePath, String oldString, String newString) {
        if (filePath == null || filePath.isBlank()) {
            return "filePath is required";
        }
        if (oldString == null) {
            return "old_string is required";
        }
        final String replacement = newString != null ? newString : "";
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return "File not found: " + filePath;
        }
        String content;
        try {
            content = new String(fo.asBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            return "Read error: " + e.getMessage();
        }
        int idx = content.indexOf(oldString);
        if (idx < 0) {
            return "old_string not found in file";
        }
        String updated = content.substring(0, idx) + replacement + content.substring(idx + oldString.length());
        // Exact-byte write so the result is precisely the accepted diff (no On-Save
        // reformatting). Fall back to the editor document only when the file is locked.
        try (OutputStream out = fo.getOutputStream()) {
            out.write(updated.getBytes(StandardCharsets.UTF_8));
        }
        catch (FileAlreadyLockedException lockEx) {
            String viaDoc = writeViaDocument(fo, updated);
            GitProvider.refreshVcsStatus(filePath);
            return viaDoc;
        }
        catch (IOException e) {
            return "Edit error: " + e.getMessage();
        }
        fo.refresh();
        GitProvider.refreshVcsStatus(filePath);
        return "File updated and saved";
    }

    /**
     * Fallback writer for a file that is open in the editor with unsaved
     * changes (a held write lock prevents a direct FileObject write). Replaces
     * the whole document and saves; this path can trigger On-Save reformatting,
     * but it only runs when a direct byte write is impossible.
     */
    private static String writeViaDocument(FileObject fo, String content) {
        AtomicReference<String> result = new AtomicReference<>("File updated and saved");
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    DataObject dob = DataObject.find(fo);
                    EditorCookie ec = dob.getLookup().lookup(EditorCookie.class);
                    if (ec == null) {
                        result.set("Write error: file is locked and not editable");
                        return;
                    }
                    StyledDocument doc = ec.openDocument();
                    doc.remove(0, doc.getLength());
                    doc.insertString(0, content, null);
                    SaveCookie save = dob.getLookup().lookup(SaveCookie.class);
                    if (save != null) {
                        save.save();
                    }
                }
                catch (Exception e) {
                    result.set("Write error: " + e.getMessage());
                }
            });
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
        catch (Exception e) {
            return "Error: " + e.getMessage();
        }
        return result.get();
    }

    public static String deleteFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "filePath is required";
        }
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return "File not found: " + filePath;
        }
        File parent = FileUtil.toFile(fo.getParent());
        try {
            try {
                DataObject.find(fo).delete();
            }
            catch (DataObjectNotFoundException e) {
                fo.delete();
            }
        }
        catch (IOException e) {
            return "Delete error: " + e.getMessage();
        }
        if (parent != null) {
            FileUtil.refreshFor(parent);
            GitProvider.refreshVcsStatus(parent.getAbsolutePath());
        }
        return "File deleted";
    }

    public static String copyFile(String sourcePath, String targetDirectory, String newName) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return "sourcePath is required";
        }
        if (targetDirectory == null || targetDirectory.isBlank()) {
            return "targetDirectory is required";
        }
        FileObject fo = resolveFileObject(sourcePath);
        if (fo == null) {
            return "File not found: " + sourcePath;
        }
        FileObject targetFo = FileUtils.resolveByPath(targetDirectory);
        if (targetFo == null || !targetFo.isFolder()) {
            return "Target directory not found: " + targetDirectory;
        }
        String destName = (newName != null && !newName.isBlank()) ? newName : fo.getName();
        try {
            FileUtil.copyFile(fo, targetFo, destName);
        }
        catch (IOException e) {
            return "Copy error: " + e.getMessage();
        }
        GitProvider.refreshVcsStatus(targetDirectory);
        return "Copied to " + targetDirectory + "/" + destName + "." + fo.getExt();
    }

    public static String moveFile(String sourcePath, String targetDirectory) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return "sourcePath is required";
        }
        if (targetDirectory == null || targetDirectory.isBlank()) {
            return "targetDirectory is required";
        }
        FileObject fo = resolveFileObject(sourcePath);
        if (fo == null) {
            return "File not found: " + sourcePath;
        }
        FileObject targetFo = FileUtils.resolveByPath(targetDirectory);
        if (targetFo == null || !targetFo.isFolder()) {
            return "Target directory not found: " + targetDirectory;
        }
        File sourceParent = FileUtil.toFile(fo.getParent());
        if ("java".equals(fo.getExt())) {
            MoveRefactoring r = new MoveRefactoring(Lookups.singleton(fo));
            r.setTarget(Lookups.singleton(targetFo.toURL()));
            String err = runRefactoring(r);
            if (err != null) {
                return "Refactoring blocked: " + err;
            }
        }
        else {
            try {
                FileUtil.moveFile(fo, targetFo, fo.getName());
            }
            catch (IOException e) {
                return "Move error: " + e.getMessage();
            }
        }
        GitProvider.refreshVcsStatus(targetDirectory);
        if (sourceParent != null) {
            GitProvider.refreshVcsStatus(sourceParent.getAbsolutePath());
        }
        return "File moved";
    }

    public static String saveFile(String filePath) {
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath : "No editor focused";
        }
        try {
            DataObject dob = DataObject.find(fo);
            SaveCookie save = dob.getLookup().lookup(SaveCookie.class);
            if (save == null) {
                return "File has no unsaved changes";
            }
            save.save();
        }
        catch (DataObjectNotFoundException e) {
            return "File not open in NetBeans: " + filePath;
        }
        catch (IOException e) {
            return "Save error: " + e.getMessage();
        }
        return "File saved";
    }

    public static String closeFile(String filePath) {
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath : "No editor focused";
        }
        AtomicReference<String> result = new AtomicReference<>("File not open in any tab");
        try {
            SwingUtilities.invokeAndWait(() -> {
                for (TopComponent tc : WindowManager.getDefault().getRegistry().getOpened()) {
                    DataObject dob = tc.getLookup().lookup(DataObject.class);
                    if (dob != null && fo.equals(dob.getPrimaryFile())) {
                        tc.close();
                        result.set("Tab closed");
                        return;
                    }
                }
            });
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
        catch (Exception e) {
            return "Error: " + e.getMessage();
        }
        return result.get();
    }

    public static String runInspect() {
        try {
            Action action = FileUtil.getConfigObject(RUN_INSPECT_ACTION, Action.class);
            if (action == null) {
                return "Inspect not available in this NetBeans installation";
            }
            SwingUtilities.invokeAndWait(()
                    -> action.actionPerformed(new ActionEvent(action, 0, "")));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
        catch (Exception e) {
            return "Error: " + e.getMessage();
        }
        return "Inspect dialog opened — select 'All Analysers' configuration "
                + "and 'All Open Projects' scope, then click Inspect";
    }

    private static String runSourceAction(String filePath, String actionPath, String label) {
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath : "No editor focused";
        }
        // Open without stealing focus — the editor is found via EditorCookie, not lastFocusedComponent
        File diskFile2 = FileUtil.toFile(fo);
        if (diskFile2 == null) {
            return "Cannot run " + label + " on non-disk file: " + fo.getPath();
        }
        String navResult = EditorContextProvider.navigateToLine(diskFile2.getPath(), 1, false);
        if (navResult.startsWith("File not found") || navResult.startsWith("Error")) {
            return navResult;
        }
        try {
            Action action = FileUtil.getConfigObject(actionPath, Action.class);
            if (action == null) {
                return label + " not available in this NetBeans installation";
            }
            SwingUtilities.invokeAndWait(() -> {
                JTextComponent editor = getEditorFor(fo);
                // Pass editor as source so NB BaseAction.getTextComponent() uses it directly
                ActionEvent evt = editor != null
                        ? new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, "")
                        : new ActionEvent(action, 0, "");
                action.actionPerformed(evt);
                saveFo(fo);
            });
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
        catch (Exception e) {
            return "Error: " + e.getMessage();
        }
        return label + " applied";
    }

    /**
     * ---- Private helpers ----
     */
    private static ParameterInfo[] existingParamInfos(FileObject fo, TreePathHandle handle) {
        JavaSource js = JavaSource.forFileObject(fo);
        if (js == null) {
            return new ParameterInfo[0];
        }
        AtomicReference<ParameterInfo[]> ref = new AtomicReference<>(new ParameterInfo[0]);
        try {
            js.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.RESOLVED);
                TreePath path = handle.resolve(cc);
                if (path == null || !(path.getLeaf() instanceof MethodTree)) {
                    return;
                }
                List<? extends VariableTree> params
                        = ((MethodTree) path.getLeaf()).getParameters();
                ParameterInfo[] infos = new ParameterInfo[params.size()];
                for (int i = 0; i < params.size(); i++) {
                    VariableTree vt = params.get(i);
                    // ParameterInfo(index) leaves type=null, crashing NB's transformer at call sites.
                    infos[i] = new ParameterInfo(i, vt.getName().toString(), vt.getType().toString(), null);
                }
                ref.set(infos);
            }, true);
        }
        catch (IOException e) {
            return new ParameterInfo[0];
        }
        return ref.get();
    }

    private static JTextComponent getEditorFor(FileObject fo) {
        try {
            EditorCookie ec = DataObject.find(fo).getLookup().lookup(EditorCookie.class);
            if (ec != null) {
                JEditorPane[] panes = ec.getOpenedPanes();
                if (panes != null && panes.length > 0) {
                    return panes[0];
                }
            }
        }
        catch (DataObjectNotFoundException ignored) {
        }
        return null;
    }

    private static void saveFo(FileObject fo) {
        try {
            DataObject dob = DataObject.find(fo);
            SaveCookie save = dob.getLookup().lookup(SaveCookie.class);
            if (save != null) {
                save.save();
            }
        }
        catch (Exception ignored) {
        }
    }

    private static String runRefactoring(AbstractRefactoring refactoring) {
        try {
            Problem p = refactoring.preCheck();
            if (p != null && p.isFatal()) {
                return p.getMessage();
            }
            RefactoringSession session = RefactoringSession.create("CC Plugin Refactoring");
            p = refactoring.prepare(session);
            if (p != null && p.isFatal()) {
                return p.getMessage();
            }
            p = session.doRefactoring(true);
            if (p != null && p.isFatal()) {
                return p.getMessage();
            }
            return null;
        }
        catch (Exception e) {
            String msg = e.getMessage();
            return msg != null ? msg : e.getClass().getName();
        }
    }

    private static TreePathHandle resolveHandle(FileObject fo, int line) {
        JavaSource js = JavaSource.forFileObject(fo);
        if (js == null) {
            return null;
        }
        AtomicReference<TreePathHandle> ref = new AtomicReference<>();
        try {
            js.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.RESOLVED);
                int lineStart = JavaSourceUtils.lineStart(cc, line);
                CharSequence src = cc.getSnapshot().getText();
                if (lineStart < 0 || lineStart >= src.length()) {
                    return;
                }
                com.sun.source.tree.CompilationUnitTree cu = cc.getCompilationUnit();
                com.sun.source.tree.LineMap lineMap = cu.getLineMap();
                com.sun.source.util.SourcePositions sp = cc.getTrees().getSourcePositions();
                int lineEnd = lineStart;
                while (lineEnd < src.length() && src.charAt(lineEnd) != '\n') {
                    lineEnd++;
                }
                // Scan word-by-word. For each word, pathFor() may return a declaration tree
                // (MethodTree/ClassTree/VariableTree) or something else (ModifiersTree,
                // IdentifierTree for a return type, etc.). We only accept a declaration tree
                // whose own start position falls on the target line — this rejects the enclosing
                // ClassTree (which starts on line 36) when scanning a method body line.
                // Priority: MethodTree > VariableTree > ClassTree, so a method declaration is
                // preferred over a same-line parameter VariableTree.
                TreePath best = null;
                int bestPriority = -1;
                int off = lineStart;
                while (off < lineEnd) {
                    char c = src.charAt(off);
                    if (Character.isJavaIdentifierStart(c)) {
                        TreePath tp = cc.getTreeUtilities().pathFor(off);
                        if (tp != null) {
                            com.sun.source.tree.Tree leaf = tp.getLeaf();
                            int priority = -1;
                            if (leaf instanceof MethodTree) {
                                priority = 2;
                            }
                            else if (leaf instanceof VariableTree) {
                                priority = 1;
                            }
                            else if (leaf instanceof com.sun.source.tree.ClassTree) {
                                priority = 0;
                            }
                            if (priority > bestPriority) {
                                long treeStart = sp.getStartPosition(cu, leaf);
                                if (lineMap.getLineNumber(treeStart) == line) {
                                    best = tp;
                                    bestPriority = priority;
                                }
                            }
                        }
                        while (off < lineEnd && Character.isJavaIdentifierPart(src.charAt(off))) {
                            off++;
                        }
                    }
                    else {
                        off++;
                    }
                }
                if (best != null) {
                    ref.set(TreePathHandle.create(best, cc));
                }
            }, true);
        }
        catch (IOException e) {
            return null;
        }
        return ref.get();
    }

    private static FileObject resolveFileObject(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            filePath = EditorContextProvider.getCurrentFilePath();
            if (filePath == null) {
                return null;
            }
        }
        File f = new File(filePath);
        if (!f.exists()) {
            return null;
        }
        // Refresh so NB re-validates any FileObjects marked [invalid] after refactoring.
        FileUtil.refreshFor(f.getParentFile(), f);
        return FileUtils.resolveByFile(f);
    }

    private static boolean isValidJavaPackageName(String name) {
        if (name.startsWith("java.") || name.startsWith("javax.")
                || name.equals("java") || name.equals("javax")) {
            return false;
        }
        return name.matches("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$");
    }

    private static FileObject findOrCreatePackage(FileObject sourceFile, String packageName) {
        ClassPath cp = ClassPath.getClassPath(sourceFile, ClassPath.SOURCE);
        if (cp == null) {
            return null;
        }
        String packagePath = packageName.replace('.', '/');
        for (FileObject root : cp.getRoots()) {
            FileObject pkg = root.getFileObject(packagePath);
            if (pkg != null) {
                return pkg;
            }
        }
        for (FileObject root : cp.getRoots()) {
            if (FileUtil.isParentOf(root, sourceFile)) {
                try {
                    return FileUtil.createFolder(root, packagePath);
                }
                catch (IOException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String pos(String filePath, int line) {
        return filePath + (line > 0 ? ":" + line : " (cursor)");
    }

    private RefactoringProvider() {
    }
}

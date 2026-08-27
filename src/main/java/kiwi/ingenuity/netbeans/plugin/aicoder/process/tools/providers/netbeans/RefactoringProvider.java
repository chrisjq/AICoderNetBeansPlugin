package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Action;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyledDocument;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.PermissionDiffPolicy;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.TreePathHandle;
import org.netbeans.api.queries.FileEncodingQuery;
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
import org.openide.text.NbDocument;
import org.openide.util.lookup.Lookups;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

public class RefactoringProvider {

    private static final Logger LOG = Logger.getLogger(RefactoringProvider.class.getName());

    private static final String RUN_INSPECT_ACTION
            = "Actions/Source/org-netbeans-modules-analysis-RunAnalysisAction.instance";
    private static final String FIX_IMPORTS_ACTION
            = "Editors/text/x-java/Actions/fix-imports.instance";
    private static final String ORGANISE_IMPORTS_ACTION
            = "Editors/text/x-java/Actions/organize-imports.instance";
    private static final String ORGANISE_MEMBERS_ACTION
            = "Editors/text/x-java/Actions/organize-members.instance";

    /**
     * The single source of truth for "what charset does this file's byte I/O use" across the whole plugin —
     * {@link EditorContextProvider}, {@code AiTopComponent}, and {@code CodexAppServerHandler} all resolve through this
     * method rather than deciding independently, so a read and its matching write can never disagree. Resolves via
     * NetBeans' {@link FileEncodingQuery} against the best-available {@link FileObject}
     * ({@link FileUtils#resolveByFile}), which is how the editor itself decides a file's encoding (per-project
     * settings, detection). Falls back to UTF-8 — a documented default, never {@link Charset#defaultCharset()} — when
     * there is no resolvable FileObject (a path outside every open project and every registered source root) or the
     * query itself is unavailable (e.g. outside a fully started IDE).
     */
    public static Charset resolveCharset(FileObject fo) {
        if (fo != null) {
            try {
                Charset cs = FileEncodingQuery.getEncoding(fo);
                if (cs != null) {
                    return cs;
                }
            }
            catch (Throwable t) {
                LOG.log(Level.FINE, "encoding query failed for " + fo, t);
            }
        }
        return StandardCharsets.UTF_8;
    }

    public static Charset resolveCharset(File f) {
        return f == null ? StandardCharsets.UTF_8 : resolveCharset(FileUtils.resolveByFile(f));
    }

    public static Charset resolveCharset(String filePath) {
        return filePath == null || filePath.isBlank() ? StandardCharsets.UTF_8 : resolveCharset(new File(filePath));
    }

    public static String renameSymbol(String filePath, int line, String newName) {
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath
                    : McpToolPropertyEnum.FILE_PATH.key() + " is required — this tool does not fall back to the focused editor. "
                    + "Call " + McpToolEnum.GET_CURRENT_FILE.toolName()
                    + " if you want the file the user is looking at.";
        }
        if (line <= 0) {
            return McpToolPropertyEnum.LINE.key() + " is required and must be 1-based — this tool does not follow the user's cursor. "
                    + "Call " + McpToolEnum.GET_CURRENT_FILE.toolName()
                    + " if you want the line the user is on.";
        }
        TreePathHandle handle = resolveHandle(fo, line);
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
                    ? "File not found: " + filePath
                    : McpToolPropertyEnum.FILE_PATH.key() + " is required — this tool does not fall back to the focused editor. "
                    + "Call " + McpToolEnum.GET_CURRENT_FILE.toolName()
                    + " if you want the file the user is looking at.";
        }
        FileObject targetFolder = findOrCreatePackage(fo, targetPackage);
        if (targetFolder == null) {
            return "Cannot resolve source root for: " + filePath;
        }
        // Read the type names BEFORE the move: afterwards fo points at the old
        // location and the names are no longer resolvable from it.
        List<String> topLevelTypes = topLevelTypeNames(fo);

        // Two different refactorings, chosen by what the caller can have meant.
        //
        // Built from a TreePathHandle, MoveRefactoring moves ONE class and leaves
        // its file behind with the rest; built from the FileObject it moves the
        // whole file. Picking the file form for a multi-class file silently took
        // classes the caller never named, which is what this tool used to do.
        if (line > 0) {
            TreePathHandle handle = resolveTopLevelClassHandle(fo, line);
            if (handle == null) {
                return "No top-level class declaration found at " + pos(filePath, line)
                        + (topLevelTypes.isEmpty() ? "" : ". This file declares: " + String.join(", ", topLevelTypes))
                        + ". Give the line of the class declaration, or omit " + McpToolPropertyEnum.LINE.key()
                        + " to move the whole file.";
            }
            MoveRefactoring byClass = new MoveRefactoring(Lookups.singleton(handle));
            byClass.setTarget(Lookups.singleton(targetFolder.toURL()));
            String classErr = runRefactoring(byClass);
            return classErr != null ? "Refactoring blocked: " + classErr
                    : "Moved class to '" + targetPackage + "'";
        }

        // No line given. With one type in the file that is unambiguous; with
        // several it is not, and moving all of them silently is the bug this
        // guard exists to prevent - so name them and ask which one.
        if (topLevelTypes.size() > 1) {
            return "This file declares " + topLevelTypes.size() + " top-level types ("
                    + String.join(", ", topLevelTypes) + "), so moving it without "
                    + McpToolPropertyEnum.LINE.key() + " would move all of them. Pass "
                    + McpToolPropertyEnum.LINE.key() + " with the declaration line of the class to move.";
        }
        // Use fo directly (not DataObject) so the Java plugin uses the fresh
        // FileObject rather than a potentially stale cached DataObject primary file.
        MoveRefactoring r = new MoveRefactoring(Lookups.singleton(fo));
        r.setTarget(Lookups.singleton(targetFolder.toURL()));
        String err = runRefactoring(r);
        return err != null ? "Refactoring blocked: " + err : "Moved to '" + targetPackage + "'";
    }

    /**
     * The top-level class declared at {@code line}, as a handle the refactoring can move on its own.
     * <p>
     * An exact match on the declaration line wins; otherwise a class whose body spans the line is accepted, so a caller
     * pointing anywhere inside the class still gets it. Only top-level types are considered - a nested class cannot be
     * moved to another package on its own, and silently moving its outer class instead would be worse than refusing.
     */
    private static TreePathHandle resolveTopLevelClassHandle(FileObject fo, int line) {
        JavaSource js = JavaSource.forFileObject(fo);
        if (js == null) {
            return null;
        }
        AtomicReference<TreePathHandle> ref = new AtomicReference<>();
        try {
            js.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.RESOLVED);
                CompilationUnitTree cu = cc.getCompilationUnit();
                SourcePositions sp = cc.getTrees().getSourcePositions();
                LineMap lineMap = cu.getLineMap();
                ClassTree match = null;
                for (Tree decl : cu.getTypeDecls()) {
                    if (!(decl instanceof ClassTree ct)) {
                        continue;
                    }
                    long start = sp.getStartPosition(cu, ct);
                    long end = sp.getEndPosition(cu, ct);
                    if (start < 0 || end < 0) {
                        continue;
                    }
                    long startLine = lineMap.getLineNumber(start);
                    if (startLine == line) {
                        match = ct;
                        break;
                    }
                    if (line > startLine && line <= lineMap.getLineNumber(end)) {
                        match = ct;
                    }
                }
                if (match != null) {
                    TreePath path = TreePath.getPath(cu, match);
                    if (path != null) {
                        ref.set(TreePathHandle.create(path, cc));
                    }
                }
            }, true);
        }
        catch (IOException | RuntimeException ex) {
            return null;
        }
        return ref.get();
    }

    /**
     * Names of the top-level types declared in a Java file, in declaration order.
     * <p>
     * Used only to describe what a move actually affected. Returns an empty list when the file cannot be parsed, which
     * makes the caller silently skip the note rather than fail a refactoring that otherwise succeeded.
     */
    private static List<String> topLevelTypeNames(FileObject fo) {
        JavaSource js = JavaSource.forFileObject(fo);
        if (js == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try {
            js.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
                for (Tree decl : cc.getCompilationUnit().getTypeDecls()) {
                    if (decl instanceof ClassTree ct) {
                        names.add(ct.getSimpleName().toString());
                    }
                }
            }, true);
        }
        catch (IOException | RuntimeException ex) {
            // Swallowed deliberately: this only decorates a success message. A
            // file that will not parse still moves correctly, and failing the
            // refactoring - or reporting a parse error as its outcome - would be
            // far worse than omitting the note.
            return List.of();
        }
        return names;
    }

    public static String inlineVariable(String filePath, int line) {
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath
                    : McpToolPropertyEnum.FILE_PATH.key() + " is required — this tool does not fall back to the focused editor. "
                    + "Call " + McpToolEnum.GET_CURRENT_FILE.toolName()
                    + " if you want the file the user is looking at.";
        }
        if (line <= 0) {
            return McpToolPropertyEnum.LINE.key() + " is required and must be 1-based — this tool does not follow the user's cursor. "
                    + "Call " + McpToolEnum.GET_CURRENT_FILE.toolName()
                    + " if you want the line the user is on.";
        }
        TreePathHandle handle = resolveHandle(fo, line);
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
                    ? "File not found: " + filePath
                    : McpToolPropertyEnum.FILE_PATH.key() + " is required — this tool does not fall back to the focused editor. "
                    + "Call " + McpToolEnum.GET_CURRENT_FILE.toolName()
                    + " if you want the file the user is looking at.";
        }
        if (line <= 0) {
            return McpToolPropertyEnum.LINE.key() + " is required and must be 1-based — this tool does not follow the user's cursor. "
                    + "Call " + McpToolEnum.GET_CURRENT_FILE.toolName()
                    + " if you want the line the user is on.";
        }
        TreePathHandle handle = resolveHandle(fo, line);
        if (handle == null) {
            return "Cannot resolve Java element at " + pos(filePath, line);
        }
        ChangeParametersRefactoring r = new ChangeParametersRefactoring(handle);
        // setParameterInfo must always be called — NB crashes with NPE if paramInfos is null.
        // When the caller omits parameters, preserve all existing params unchanged via ParameterInfo(i).
        // Partial entries (name-only / type-only updates) are resolved against the current
        // signature here, so they actually rename/retype instead of silently doing nothing.
        r.setParameterInfo(mergeParameterInfos(parameters, existingParamInfos(fo, handle)));
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
        return runSourceAction(filePath, FIX_IMPORTS_ACTION, McpToolEnum.FIX_IMPORTS.toolName());
    }

    public static String organiseImports(String filePath) {
        return runSourceAction(filePath, ORGANISE_IMPORTS_ACTION, McpToolEnum.ORGANISE_IMPORTS.toolName());
    }

    public static String organiseMembers(String filePath) {
        return runSourceAction(filePath, ORGANISE_MEMBERS_ACTION, McpToolEnum.ORGANISE_MEMBERS.toolName());
    }

    public static String reformatFile(String filePath) {
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                    ? "File not found: " + filePath
                    : McpToolPropertyEnum.FILE_PATH.key() + " is required — this tool rewrites a file, so it does not fall back to "
                    + "the focused editor. Call " + McpToolEnum.GET_CURRENT_FILE.toolName()
                    + " if you want the file the user is looking at.";
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
                    // Wrapped in the document's atomic lock, which is the shape
                    // Reformat's own javadoc prescribes. Without it a whole-file
                    // reformat is recorded as many separate edits, so the user
                    // needs a long run of Ctrl+Z to undo one tool call.
                    if (doc instanceof StyledDocument styled) {
                        NbDocument.runAtomic(styled, () -> {
                            try {
                                reformat.reformat(0, doc.getLength());
                            }
                            catch (BadLocationException e) {
                                result.set("Reformat error: " + e.getMessage());
                            }
                        });
                    }
                    else {
                        reformat.reformat(0, doc.getLength());
                    }
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
            return McpToolPropertyEnum.FILE_PATH.key() + " is required";
        }
        if (content == null) {
            return McpToolPropertyEnum.CONTENT.key() + " is required";
        }
        File f = new File(filePath);
        if (!f.exists()) {
            try {
                File parentDir = f.getParentFile();
                if (parentDir != null) {
                    parentDir.mkdirs();
                }
                // Resolve the parent directory through NetBeans to handle symlinked paths.
                // If the parent exists and is under a project root, create the file relative
                // to the canonicalized parent FileObject so it inherits the correct root ancestry.
                FileObject parentFO = null;
                if (parentDir != null && parentDir.exists()) {
                    parentFO = FileUtils.resolveByFile(parentDir);
                }
                FileObject newFo;
                if (parentFO != null) {
                    newFo = FileUtil.createData(parentFO, f.getName());
                }
                else {
                    newFo = FileUtil.createData(f);
                }
                try (OutputStream out = newFo.getOutputStream()) {
                    out.write(content.getBytes(resolveCharset(newFo)));
                }
                // For consistency with the other two write paths below. NetBeans performed this write itself, so its
                // cached size should already be right — this is not a known defect, just one less special case.
                FileUtils.refreshAfterWrite(newFo);
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
        // Flush first so the editor and disk agree before we overwrite. The caller
        // composed this content from an earlier read, which came from disk, so any
        // unsaved edits were invisible to it; saving them at least records them in
        // the editor's undo history and VCS instead of dropping them silently.
        FlushResult flush = flushUnsavedEditorChanges(fo);
        if (flush.error() != null) {
            return flush.error();
        }
        // Apply the accepted change as exact bytes. Saving through the editor would
        // run NetBeans "On Save" tasks (reformat / trailing-whitespace removal) that
        // mutate the bytes and desync external tools tracking the file on disk. Only
        // fall back to the editor document when the file is locked (open + unsaved).
        try (OutputStream out = fo.getOutputStream()) {
            out.write(content.getBytes(resolveCharset(fo)));
        }
        catch (FileAlreadyLockedException lockEx) {
            String viaDoc = writeViaDocument(fo, content);
            GitProvider.refreshVcsStatus(filePath);
            return viaDoc + flushNote(flush);
        }
        catch (IOException e) {
            return "Write error: " + e.getMessage();
        }
        FileUtils.refreshAfterWrite(fo);
        GitProvider.refreshVcsStatus(filePath);
        return "File updated and saved" + flushNote(flush);
    }

    public static String applyEdit(String filePath, String oldString, String newString) {
        return applyEdit(filePath, oldString, newString, false);
    }

    /**
     * @param replaceAll replace every occurrence rather than the first. Shares
     * {@link PermissionDiffPolicy#replaceEvery} with the preview so the approved diff and the written bytes cannot
     * diverge — see that method for why neither side uses String.replace/replaceAll.
     */
    public static String applyEdit(String filePath, String oldString, String newString, boolean replaceAll) {
        if (filePath == null || filePath.isBlank()) {
            return McpToolPropertyEnum.FILE_PATH.key() + " is required";
        }
        if (oldString == null) {
            return McpToolPropertyEnum.OLD_STRING.key() + " is required";
        }
        if (newString == null) {
            return McpToolPropertyEnum.NEW_STRING.key() + " is required";
        }
        final String replacement = newString;
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return "File not found: " + filePath;
        }
        // Flush BEFORE reading: the match below and the write further down both
        // work on disk bytes, so a dirty buffer would mean editing text the user
        // cannot see and discarding the text they can.
        FlushResult flush = flushUnsavedEditorChanges(fo);
        if (flush.error() != null) {
            return flush.error();
        }
        // Observed 2026-08-27: a 282-line/10828-byte file written by a peer session was seen by NetBeans as
        // 120 lines/4334 bytes. Anchors past line 120 were rejected as "not found"; an anchor before it applied,
        // and the write below persisted the truncated read — cutting the file to 119 lines.
        //
        // NOT COVERED BY A TEST, deliberately. A unit test against a temp file passes either way: the headless
        // harness has no live MasterFileSystem to hold a stale cache, so the defect cannot be reproduced there. A
        // test was written, observed to pass under revert, and deleted rather than kept as false assurance. Verify
        // manually: edit near the end of a file the IDE never observed (e.g. one a peer session created).
        FileUtils.refreshBeforeRead(fo);
        // Resolved once and reused for every decode/encode below — the initial read,
        // the staleness re-check, and the final write must all agree, or oldString's
        // byte-for-byte match against what was read either fails or matches the wrong
        // text and clobbers it.
        Charset charset = resolveCharset(fo);
        String content;
        try {
            content = new String(fo.asBytes(), charset);
        }
        catch (IOException e) {
            return "Read error: " + e.getMessage();
        }
        int idx = content.indexOf(oldString);
        if (idx < 0) {
            return McpToolPropertyEnum.OLD_STRING.key() + " not found in file";
        }
        String updated = replaceAll
                ? PermissionDiffPolicy.replaceEvery(content, oldString, replacement)
                : PermissionDiffPolicy.replaceFirst(content, oldString, replacement);
        try {
            // Refresh here too: this guard exists to catch the file moving under us between match and write, and it
            // cannot do that if it re-reads the same cache the match already used.
            FileUtils.refreshBeforeRead(fo);
            String current = new String(fo.asBytes(), charset);
            if (!current.equals(content)) {
                return "Edit refused: file changed after approval; please retry";
            }
        }
        catch (IOException e) {
            return "Read error before edit: " + e.getMessage();
        }
        // Exact-byte write so the result is precisely the accepted diff (no On-Save
        // reformatting). Fall back to the editor document only when the file is locked.
        try (OutputStream out = fo.getOutputStream()) {
            out.write(updated.getBytes(charset));
        }
        catch (FileAlreadyLockedException lockEx) {
            String viaDoc = writeViaDocument(fo, updated);
            GitProvider.refreshVcsStatus(filePath);
            return viaDoc + flushNote(flush);
        }
        catch (IOException e) {
            return "Edit error: " + e.getMessage();
        }
        FileUtils.refreshAfterWrite(fo);
        GitProvider.refreshVcsStatus(filePath);
        return "File updated and saved" + flushNote(flush);
    }

    /**
     * Fallback writer for a file that is open in the editor with unsaved changes (a held write lock prevents a direct
     * FileObject write). Replaces the whole document and saves; this path can trigger On-Save reformatting, but it only
     * runs when a direct byte write is impossible.
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
                    if (save == null) {
                        result.set("Write error: file is locked and cannot be saved");
                        return;
                    }
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
            return McpToolPropertyEnum.FILE_PATH.key() + " is required";
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

    /**
     * Saves a file's unsaved editor changes before a tool reads or writes that file on disk.
     * <p>
     * Disk-based read/write tools here (including ApplyEdit and writeFileContent) flush before accessing bytes, while
     * delete/copy/move operate on filesystem objects without a read/write byte path and do not flush. The editor holds
     * its own copy. Touching the file while the buffer is dirty loses whatever the user had typed: the tool computes
     * its result from disk, which never contained those edits, and the write then either replaces the document or makes
     * the editor reload. Verified by experiment - a one-line ApplyEdit against a file with an unsaved line silently
     * discarded that line and reported success, and the diff panel could not show it because the diff was computed from
     * disk in the first place.
     * <p>
     * Flushing first makes disk and buffer agree, so the AI edits what the user actually has on screen and the diff
     * they approve is the real one. A save that FAILS returns an error instead: continuing would destroy the very
     * changes this exists to protect.
     */
    public static FlushResult flushUnsavedEditorChanges(FileObject fo) {
        if (fo == null) {
            return new FlushResult(false, null);
        }
        try {
            DataObject dob = DataObject.find(fo);
            if (!dob.isModified()) {
                return new FlushResult(false, null);
            }
            SaveCookie save = dob.getLookup().lookup(SaveCookie.class);
            if (save == null) {
                // Modified with nothing able to save it. Returning "nothing to do"
                // here would let the caller write bytes over changes it cannot
                // see - the exact failure this guard exists to prevent - so fail
                // closed instead.
                return new FlushResult(false, "Refusing to continue: " + fo.getPath()
                        + " has unsaved editor changes and offers no way to save them, so proceeding"
                        + " would discard them. Ask the user to save or revert the file, then retry.");
            }
            save.save();
            return new FlushResult(true, null);
        }
        catch (DataObjectNotFoundException e) {
            // Not known to the IDE, so there is no buffer to lose.
            return new FlushResult(false, null);
        }
        catch (IOException e) {
            return new FlushResult(false, "Refusing to continue: " + fo.getPath()
                    + " has unsaved editor changes that could not be saved first (" + e.getMessage()
                    + "). Proceeding would discard them. Ask the user to save or revert the file, then retry.");
        }
    }

    /**
     * Suffix describing a flush, for appending to a tool's success message.
     */
    public static String flushNote(FlushResult flush) {
        return flush.flushed() ? " (unsaved editor changes were saved first)" : "";
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
                    ? "File not found: " + filePath : McpToolPropertyEnum.FILE_PATH.key() + " is required";
        }
        AtomicReference<String> result = new AtomicReference<>("File not open in any tab");
        try {
            SwingUtilities.invokeAndWait(() -> {
                for (TopComponent tc : WindowManager.getDefault().getRegistry().getOpened()) {
                    DataObject dob = tc.getLookup().lookup(DataObject.class);
                    if (dob != null && fo.equals(dob.getPrimaryFile())) {
                        result.set(tc.close() ? "Tab closed" : "Unable to close tab");
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
                    ? "File not found: " + filePath : McpToolPropertyEnum.FILE_PATH.key() + " is required";
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
            AtomicReference<String> saveError = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                JTextComponent editor = getEditorFor(fo);
                // Pass editor as source so NB BaseAction.getTextComponent() uses it directly
                ActionEvent evt = editor != null
                        ? new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, "")
                        : new ActionEvent(action, 0, "");
                action.actionPerformed(evt);
                saveError.set(saveFo(fo));
            });
            if (saveError.get() != null) {
                return "Error: " + saveError.get();
            }
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

    /**
     * Resolves caller-supplied parameter entries against the method's current signature so a PARTIAL update
     * ({@code name}-only or {@code type}-only — exactly the shape the ChangeMethodSignature tool schema's own example
     * shows) is no longer a silent no-op: anything an entry omits is filled in from the existing parameter at
     * {@code originalIndex}. Entries naming both fields pass through untouched, as do brand-new parameters
     * ({@code originalIndex == -1}), which the tool layer has already validated for required fields. A null
     * {@code defaultValue} stays null on existing-index entries — NetBeans reads that as "keep call-site arguments as
     * they are", whereas substituting an empty string would rewrite every call site to pass nothing.
     *
     * @param requested entries built by the tool from the JSON {@code parameters} array, or null when omitted
     * @param existing the method's current parameters (see {@link #existingParamInfos})
     */
    static ParameterInfo[] mergeParameterInfos(ParameterInfo[] requested, ParameterInfo[] existing) {
        if (requested == null || requested.length == 0) {
            return existing;
        }
        if (existing.length == 0) {
            // Nothing to resolve against (e.g. unresolvable signature) — hand through untouched.
            return requested;
        }
        ParameterInfo[] merged = new ParameterInfo[requested.length];
        for (int i = 0; i < requested.length; i++) {
            ParameterInfo req = requested[i];
            int idx = req.getOriginalIndex();
            if (idx >= 0 && idx < existing.length && (req.getName() == null || req.getType() == null)) {
                ParameterInfo ex = existing[idx];
                merged[i] = new ParameterInfo(idx,
                        req.getName() != null ? req.getName() : ex.getName(),
                        req.getType() != null ? req.getType() : ex.getType(),
                        req.getDefaultValue());
            }
            else {
                merged[i] = req;
            }
        }
        return merged;
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

    private static String saveFo(FileObject fo) {
        try {
            DataObject dob = DataObject.find(fo);
            SaveCookie save = dob.getLookup().lookup(SaveCookie.class);
            if (save != null) {
                save.save();
            }
            return null;
        }
        catch (Exception e) {
            String message = e.getMessage();
            return message != null ? message : e.getClass().getName();
        }
    }

    private static String runRefactoring(AbstractRefactoring refactoring) {
        try {
            Problem p = refactoring.preCheck();
            if (p != null) {
                return p.getMessage();
            }
            RefactoringSession session = RefactoringSession.create("CC Plugin Refactoring");
            try {
                p = refactoring.prepare(session);
                if (p != null) {
                    return p.getMessage();
                }
                p = session.doRefactoring(true);
                return p != null ? p.getMessage() : null;
            }
            finally {
                session.finished();
            }
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

    /**
     * Resolves the file a refactoring will act on.
     *
     * <p>
     * There is deliberately no fallback. This used to take whatever file the editor had focused, which meant a caller
     * that omitted {@code filePath} refactored a file it had never named — chosen by wherever the user last clicked.
     * Unlike a search, a refactoring writes, so guessing the target is not recoverable by trying again. Callers that
     * want the file the user is looking at should call GetCurrentFile and pass the path it returns.
     */
    private static FileObject resolveFileObject(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        File f = new File(filePath);
        if (!f.exists()) {
            return null;
        }
        // Refresh so NB re-validates any FileObjects marked [invalid] after refactoring.
        //
        // Note this does NOT give a fresh view of file CONTENT: filePath arrives via the /share symlink while
        // resolveByFile looks the object up under the canonical root, so this refreshes a different filesystem
        // location than the FileObject returned. Content reads must call FileUtils.refreshBeforeRead on the resolved
        // object — see applyEdit.
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
            // getRelativePath rather than the deprecated FileUtil.isParentOf: it
            // answers the same question (is sourceFile under root) by returning
            // null when it is not, and is the supported form as of NetBeans 22.
            if (FileUtil.getRelativePath(root, sourceFile) != null) {
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

    /**
     * Outcome of flushing a file's editor buffer before a tool touches the file on disk.
     */
    public static final class FlushResult {

        private final boolean flushed;
        private final String error;

        FlushResult(boolean flushed, String error) {
            this.flushed = flushed;
            this.error = error;
        }

        /**
         * Whether unsaved editor changes were written to disk.
         */
        public boolean flushed() {
            return flushed;
        }

        /**
         * Message to return to the caller instead of proceeding, or null when it is safe to continue.
         */
        public String error() {
            return error;
        }
    }
}

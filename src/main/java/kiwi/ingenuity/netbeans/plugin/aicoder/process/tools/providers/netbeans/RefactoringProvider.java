package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Action;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyledDocument;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.PermissionDiffPolicy;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.TreePathHandle;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.ui.OpenProjects;
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

    public static String renameSymbol(String filePath, int line, String newName, boolean commitWithWarning) {
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
        RenameRefactoring r = isTopLevelTypeMatchingFilename(handle, fo)
                              ? new RenameRefactoring(Lookups.fixed(handle, fo))
                              : new RenameRefactoring(Lookups.singleton(handle));
        r.setNewName(newName);
        return runRefactoring(r, commitWithWarning, "Renamed to '" + newName + "'");
    }

    public static String moveClass(String filePath, int line, String targetPackage, String targetProjectPath,
                                   boolean commitWithWarning) {
        if (targetPackage == null || targetPackage.isBlank()) {
            return "Error: " + McpToolPropertyEnum.TARGET_PACKAGE.key() + " is required";
        }
        if (!isValidJavaPackageName(targetPackage)) {
            return "Error: invalid target package name '" + targetPackage + "'";
        }
        // A negative line is malformed, not omitted. Only absent-or-zero means "whole file" (MoveClassTool defaults the
        // parameter to 0), so falling through with -1 would silently reinterpret a bad value as a BROADER operation
        // than the caller asked for, and for a single-type file it would move it with no complaint at all. Refuse
        // instead — the same reasoning as the multi-type guard further down, applied to the input rather than the file.
        // Validated here with the other arguments, before any file or source-root resolution, so a malformed line is
        // rejected on its own terms and does not depend on the IDE being able to resolve the file first.
        if (line < 0) {
            return "Error: " + McpToolPropertyEnum.LINE.key()
                    + " must be 1-based, or omitted to move the whole file. Received: " + line;
        }
        boolean targetProjectGiven = targetProjectPath != null && !targetProjectPath.isBlank();
        // Validated alongside the other arguments, before any file resolution — the same principle commit d056587
        // already established for `line` above: a malformed argument must not be masked by a DIFFERENT failure
        // (here, "File not found" for a filePath that was never even reached) just because it happened to be
        // checked first.
        if (targetProjectGiven && resolveOpenProjectByPath(targetProjectPath) == null) {
            return "Error: no open project matches " + McpToolPropertyEnum.TARGET_PROJECT_PATH.key() + ": " + targetProjectPath;
        }
        FileObject fo = resolveFileObject(filePath);
        if (fo == null) {
            return filePath != null && !filePath.isBlank()
                   ? "File not found: " + filePath
                   : McpToolPropertyEnum.FILE_PATH.key() + " is required — this tool does not fall back to the focused editor. "
                    + "Call " + McpToolEnum.GET_CURRENT_FILE.toolName()
                    + " if you want the file the user is looking at.";
        }
        if (!isUsableRefactoringFile(fo)) {
            return "Source file is no longer valid: " + filePath + ". Re-resolve the path and retry.";
        }
        // #17: targetProjectPath omitted used to mean "search/create targetPackage under the source file's own
        // project, no matter what" — which silently created the package inside the WRONG module whenever the caller
        // actually meant a package that already exists in a different open project. Refuse instead of guessing.
        if (!targetProjectGiven) {
            String misplacement = packageBelongsToOtherOpenProjectMessage(fo, targetPackage);
            if (misplacement != null) {
                return misplacement;
            }
        }

        FileObject targetFolder = findOrCreatePackage(fo, targetPackage, targetProjectPath);
        if (targetFolder == null) {
            return targetProjectGiven
                   ? "Error: no open project matches " + McpToolPropertyEnum.TARGET_PROJECT_PATH.key() + ": " + targetProjectPath
                   : "Cannot resolve source root for: " + filePath;
        }

        // Directory creation can advance MasterFS and JavaSource independently. Re-resolve the source and its class
        // handle only after the target exists, immediately before MoveRefactoring is constructed.
        File sourceDisk = FileUtil.toFile(fo);
        if (sourceDisk != null) {
            FileUtil.refreshFor(sourceDisk.getParentFile(), sourceDisk);
        }
        fo.refresh();
        fo = resolveFileObject(filePath);
        if (!isUsableRefactoringFile(fo)) {
            return "Source file became unavailable before refactoring: " + filePath;
        }
        List<String> topLevelTypes = topLevelTypeNames(fo);
        TopLevelClassMatch match = line > 0 ? resolveTopLevelClassMatch(fo, line) : null;
        if (line > 0 && match == null) {
            return "No top-level class declaration found at " + pos(filePath, line)
                    + (topLevelTypes.isEmpty() ? "" : ". This file declares: " + String.join(", ", topLevelTypes))
                    + ". Give the line of the class declaration, or omit " + McpToolPropertyEnum.LINE.key()
                    + " to move the whole file.";
        }
        if (match != null && topLevelTypes.size() > 1) {
            FileObject handleFile = match.handle().getFileObject();
            if (handleFile != fo || !isUsableRefactoringFile(handleFile)) {
                return "Source file changed during class-handle resolution. Re-resolve the path and retry; nothing was changed.";
            }
            logMoveClassModel(filePath, fo, match);
        }

        // No line given. With one type in the file that is unambiguous; with
        // several it is not, and moving all of them silently is the bug this
        // guard exists to prevent - so name them and ask which one.
        if (line == 0 && topLevelTypes.size() > 1) {
            return "This file declares " + topLevelTypes.size() + " top-level types ("
                    + String.join(", ", topLevelTypes) + "), so moving it without "
                    + McpToolPropertyEnum.LINE.key() + " would move all of them. Pass "
                    + McpToolPropertyEnum.LINE.key() + " with the declaration line of the class to move.";
        }

        // A TreePathHandle is required only to extract one type from a multi-type file. Single-type files use the
        // whole-file route above even when a line was supplied, because both requests have identical meaning.
        if (match != null && usesWholeFileMoveForResolvedClass(topLevelTypes.size())) {
            // NetBeans' TreePathHandle move generates a target from a MemoryFileSystem template. That template path can
            // emit invalid diffs and delete the source before target creation fails; the FileObject move is live-proven.
            return runWholeFileMove(fo, targetFolder, targetPackage, commitWithWarning);
        }
        if (match != null) {
            MoveRefactoring byClass = new MoveRefactoring(Lookups.singleton(match.handle()));
            byClass.setTarget(Lookups.singleton(targetFolder.toURL()));
            // A class-only move always creates a NEW file named after the class itself in the target folder — never
            // the source file's own name — so the resulting path is reported from the class's name, not fo's.
            String resultingPath = targetFolder.getPath() + "/" + match.simpleName() + ".java";
            return runMoveRefactoringWithRecovery(byClass, commitWithWarning,
                                                  "Moved class to '" + targetPackage + "': " + resultingPath, fo, targetFolder, match.simpleName() + ".java");
        }

        // Use fo directly (not DataObject) so the Java plugin uses the fresh
        // FileObject rather than a potentially stale cached DataObject primary file.
        MoveRefactoring r = new MoveRefactoring(Lookups.singleton(fo));
        r.setTarget(Lookups.singleton(targetFolder.toURL()));
        String resultingPath = targetFolder.getPath() + "/" + fo.getNameExt();
        return runMoveRefactoringWithRecovery(r, commitWithWarning,
                                              "Moved to '" + targetPackage + "': " + resultingPath, fo, targetFolder, fo.getNameExt());
    }

    /**
     * Moves a complete Java file, avoiding the broken class-handle template path when the file has only one top-level
     * type. Package-private route seam for the single-type-with-line regression test.
     */
    static boolean usesWholeFileMoveForResolvedClass(int topLevelTypeCount) {
        return topLevelTypeCount == 1;
    }

    private static String runWholeFileMove(FileObject source, FileObject targetFolder, String targetPackage,
                                           boolean commitWithWarning) {
        MoveRefactoring refactoring = new MoveRefactoring(Lookups.singleton(source));
        refactoring.setTarget(Lookups.singleton(targetFolder.toURL()));
        String resultingPath = targetFolder.getPath() + "/" + source.getNameExt();
        return runMoveRefactoringWithRecovery(refactoring, commitWithWarning,
                                              "Moved to '" + targetPackage + "': " + resultingPath, source, targetFolder, source.getNameExt());
    }

    /**
     * Moves several Java classes to the same target package in ONE refactoring. NetBeans' {@link MoveRefactoring} takes
     * a {@link org.openide.util.Lookup}, and a lookup can hold many {@link FileObject}s (the same pattern
     * {@code renameSymbol} already uses via {@code Lookups.fixed} for a single file's handle+FileObject pair), so the
     * whole batch is one preCheck/prepare/doRefactoring transaction rather than N of them. Every source is validated
     * immediately before constructing the refactoring because an invalid FileObject must never be passed to the engine.
     * <p>
     * Every path is validated — resolvable, exactly one top-level type, and a shared target folder — before anything
     * moves, so a bad file among several is caught while nothing has changed. {@code line} has no meaning here: a line
     * number cannot identify a class across several files, so every file in a batch moves as a whole, and a file
     * declaring more than one top-level type is refused for the same reason the single-file path refuses it — moving it
     * would silently take classes nobody named.
     */
    public static String moveClasses(List<String> filePaths, String targetPackage, String targetProjectPath,
                                     boolean commitWithWarning) {
        if (targetPackage == null || targetPackage.isBlank()) {
            return "Error: " + McpToolPropertyEnum.TARGET_PACKAGE.key() + " is required";
        }
        if (!isValidJavaPackageName(targetPackage)) {
            return "Error: invalid target package name '" + targetPackage + "'";
        }
        if (filePaths == null || filePaths.isEmpty()) {
            return "Error: " + McpToolPropertyEnum.FILE_PATHS.key() + " must contain at least one path";
        }
        boolean targetProjectGiven = targetProjectPath != null && !targetProjectPath.isBlank();
        // Validated alongside the other arguments, before any file resolution — see moveClass's identical guard for
        // why (commit d056587's principle: a malformed argument must not be masked by "File not found" for a path
        // that was never even reached).
        if (targetProjectGiven && resolveOpenProjectByPath(targetProjectPath) == null) {
            return "Error: no open project matches " + McpToolPropertyEnum.TARGET_PROJECT_PATH.key() + ": " + targetProjectPath;
        }

        List<FileObject> resolved = new ArrayList<>(filePaths.size());
        FileObject targetFolder = null;
        for (String filePath : filePaths) {
            FileObject fo = resolveFileObject(filePath);
            if (fo == null) {
                return filePath != null && !filePath.isBlank()
                       ? "File not found: " + filePath
                       : McpToolPropertyEnum.FILE_PATHS.key() + " contains a blank path";
            }
            if (!isUsableRefactoringFile(fo)) {
                return "Source file is no longer valid: " + filePath + ". Re-resolve the path and retry.";
            }
            List<String> topLevelTypes = topLevelTypeNames(fo);
            if (topLevelTypes.size() > 1) {
                return filePath + " declares " + topLevelTypes.size() + " top-level types ("
                        + String.join(", ", topLevelTypes) + "). A batch move has no " + McpToolPropertyEnum.LINE.key()
                        + " to pick one, so every file in the batch must declare exactly one top-level type — move "
                        + "this file on its own with " + McpToolPropertyEnum.LINE.key() + " instead.";
            }
            // #17: same silent-misplacement guard as the single-file path, applied per file — a batch can draw its
            // files from more than one source project even though they all share one target.
            if (!targetProjectGiven) {
                String misplacement = packageBelongsToOtherOpenProjectMessage(fo, targetPackage);
                if (misplacement != null) {
                    return misplacement;
                }
            }
            FileObject folder = findOrCreatePackage(fo, targetPackage, targetProjectPath);
            if (folder == null) {
                return targetProjectGiven
                       ? "Error: no open project matches " + McpToolPropertyEnum.TARGET_PROJECT_PATH.key() + ": " + targetProjectPath
                       : "Cannot resolve source root for: " + filePath;
            }
            if (targetFolder == null) {
                targetFolder = folder;
            }
            else if (!targetFolder.equals(folder)) {
                return filePath + " resolves to a different source root than the rest of the batch (target would be "
                        + folder.getPath() + " instead of " + targetFolder.getPath() + "). Move it in its own call.";
            }
            resolved.add(fo);
        }

        for (int i = 0; i < resolved.size(); i++) {
            if (!isUsableRefactoringFile(resolved.get(i))) {
                return "Source file is no longer valid: " + filePaths.get(i) + ". Re-resolve the path and retry.";
            }
        }
        MoveRefactoring r = new MoveRefactoring(Lookups.fixed(resolved.toArray()));
        r.setTarget(Lookups.singleton(targetFolder.toURL()));
        List<String> resultingPaths = new ArrayList<>(resolved.size());
        for (FileObject fo : resolved) {
            resultingPaths.add(targetFolder.getPath() + "/" + fo.getNameExt());
        }
        String success = "Moved " + resolved.size() + " file(s) to '" + targetPackage + "': " + String.join(", ", resultingPaths);
        return runRefactoring(r, commitWithWarning, success);
    }

    /**
     * A top-level class resolved at a given line, paired with its simple name — the name is captured in the same
     * {@code JavaSource} pass that resolves the handle so a caller needing both (a class-only move reports the moved
     * file's new name, which is always the class's own name, never the source file's) does not need a second pass.
     */
    private record TopLevelClassMatch(TreePathHandle handle, String simpleName, long endPosition) {

    }

    /**
     * The top-level class declared at {@code line}, as a handle the refactoring can move on its own, plus its simple
     * name.
     * <p>
     * An exact match on the declaration line wins; otherwise a class whose body spans the line is accepted, so a caller
     * pointing anywhere inside the class still gets it. Only top-level types are considered - a nested class cannot be
     * moved to another package on its own, and silently moving its outer class instead would be worse than refusing.
     */
    private static TopLevelClassMatch resolveTopLevelClassMatch(FileObject fo, int line) {
        JavaSource js = JavaSource.forFileObject(fo);
        if (js == null) {
            return null;
        }
        AtomicReference<TopLevelClassMatch> ref = new AtomicReference<>();
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
                        ref.set(new TopLevelClassMatch(TreePathHandle.create(path, cc), match.getSimpleName().toString(),
                                                       sp.getEndPosition(cu, match)));
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
    private static void logMoveClassModel(String filePath, FileObject source, TopLevelClassMatch match) {
        if (!PluginSettings.isDebugJson()) {
            return;
        }
        long byteLength = -1;
        File sourceFile = FileUtil.toFile(source);
        if (sourceFile != null) {
            try {
                byteLength = Files.size(sourceFile.toPath());
            }
            catch (IOException ignored) {
                // Debug diagnostics must not affect refactoring behavior.
            }
        }
        FileObject handleFile = match.handle().getFileObject();
        LOG.log(Level.FINE, "MoveClass model file={0}, bytes={1}, source={2}@{3}, handle={4}@{5}, treeEnd={6}",
                new Object[]{filePath, byteLength, source != null ? source.getPath() : "<null>",
                    System.identityHashCode(source), handleFile != null ? handleFile.getPath() : "<null>",
                    System.identityHashCode(handleFile), match.endPosition()});
    }

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

    public static String inlineVariable(String filePath, int line, boolean commitWithWarning) {
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
        return runRefactoring(r, commitWithWarning, "Inlined variable");
    }

    public static String changeMethodSignature(String filePath, int line, ParameterInfo[] parameters,
                                               String methodName, String returnType, Boolean overloadMethod, boolean commitWithWarning) {
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
        ParameterInfo[] existing = existingParamInfos(fo, handle);
        ParameterInfo[] merged = mergeParameterInfos(parameters, existing);
        String parameterError = validateParameterInfos(merged, existing.length);
        if (parameterError != null) {
            return parameterError;
        }
        r.setParameterInfo(merged);
        if (methodName != null && !methodName.isBlank()) {
            r.setMethodName(methodName);
        }
        if (returnType != null && !returnType.isBlank()) {
            r.setReturnType(returnType);
        }
        if (overloadMethod != null) {
            r.setOverloadMethod(overloadMethod);
        }
        return runRefactoring(r, commitWithWarning, "Method signature updated");
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
        String navResult = EditorContextProvider.openFile(diskFile.getPath(), false);
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
        Charset charset = resolveCharset(fo);
        // The baseline for the locked-document guard below, read immediately after the flush.
        //
        // The caller supplies complete NEW content, so there is no "what it was before" to inherit from it — but this
        // method can establish one itself. flushUnsavedEditorChanges has just left the document and disk in agreement
        // on every path that reaches here: either the document was not modified (NetBeans' own statement that the
        // buffer matches disk), or it was saved successfully, or the file has no document at all. So the bytes on disk
        // right now ARE what the editor is showing, which is exactly the baseline the guard needs.
        //
        // Read with the same size check applyEdit uses: a baseline we cannot trust is worse than none, because the
        // guard would then compare the document against a truncated view and refuse or allow on noise.
        File onDisk = FileUtil.toFile(fo);
        if (onDisk == null) {
            return "Write refused: no disk path for " + filePath;
        }
        Path diskPath = onDisk.toPath();
        String baseline;
        try {
            long sizeOnDisk = Files.size(diskPath);
            byte[] bytes = Files.readAllBytes(diskPath);
            String shortRead = describeShortRead(filePath, bytes.length, sizeOnDisk);
            if (shortRead != null) {
                return shortRead;
            }
            baseline = new String(bytes, charset);
        }
        catch (IOException e) {
            return "Read error before write: " + e.getMessage();
        }
        // Apply the accepted change as exact bytes. Saving through the editor would
        // run NetBeans "On Save" tasks (reformat / trailing-whitespace removal) that
        // mutate the bytes and desync external tools tracking the file on disk. Only
        // fall back to the editor document when the file is locked (open + unsaved).
        try (OutputStream out = fo.getOutputStream()) {
            out.write(content.getBytes(charset));
        }
        catch (FileAlreadyLockedException lockEx) {
            // Reaching here is itself evidence someone typed: the lock is held by a DIRTY document, and the flush above
            // left it clean, so it was dirtied after the flush. Require the document to still match the baseline before
            // replacing it wholesale — otherwise this overwrites the user's own typing and saves it, silently.
            String viaDoc = writeViaDocument(fo, content, baseline);
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
     * Fail-closed check on a content read that is about to drive a write: null when the bytes read account for the
     * whole file, otherwise the error to return instead of writing.
     *
     * <p>
     * A read-modify-write is only as safe as its read. If fewer bytes came back than the file actually holds, the
     * replacement built from them is missing the tail, and writing it destroys that tail silently. Refusing is always
     * recoverable — the caller retries and nothing is lost — so this errs towards refusing.</p>
     *
     * <p>
     * Pure, and separate from the read, so a test can drive a disagreement between "bytes read" and "size on disk"
     * without needing a live filesystem to produce a genuinely stale cache. The truncation itself cannot be reproduced
     * headlessly; this decision can.</p>
     *
     * @param filePath the path to name in the error, as the caller supplied it
     * @param bytesRead how many bytes the read actually returned
     * @param sizeOnDisk how many bytes the file holds, stat'd from disk
     */
    static String describeShortRead(String filePath, long bytesRead, long sizeOnDisk) {
        if (bytesRead == sizeOnDisk) {
            return null;
        }
        return "Edit refused: read " + bytesRead + " bytes of " + filePath + " but it is " + sizeOnDisk
                + " bytes on disk. The IDE's view of this file is stale, so the edit was NOT applied and nothing "
                + "was changed. Retry — the read is re-taken each attempt.";
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
        // WHY THIS READS FROM DISK RATHER THAN THROUGH THE FileObject.
        //
        // NetBeans caches a FileObject's length, and asBytes() returns only that many bytes. When the cache is stale
        // the read comes back TRUNCATED, and a read-modify-write then persists the truncation — silently destroying
        // everything past the boundary. This has now happened twice, measured both times:
        //
        //   2026-08-27: a 282-line/10828-byte file written by a peer session was seen as 120 lines/4334 bytes.
        //               Anchors past line 120 were "not found"; one before it applied, and the write cut the file
        //               to 119 lines.
        //   2026-08-29: this file's sibling AiDiffTopComponent.java, 969 lines/36240 bytes on disk and untouched
        //               since 2026-07-11, was seen as 114 lines/4097 bytes. Every anchor past line 114 was rejected
        //               across a whole session while java.nio reads of the same path returned all 969 lines. A
        //               top-of-file edit then reported SUCCESS and left 114 lines/4097 bytes on disk.
        //
        // fo.refresh() did not save us either time: it re-stats from last-modified, and a file whose mtime has not
        // changed keeps its stale cached length indefinitely. That is why the read below no longer goes through the
        // FileObject at all. flushUnsavedEditorChanges above has already made disk authoritative, so nothing is lost
        // by reading it directly; the FileObject is still used for the WRITE, with refreshAfterWrite to re-sync.
        //
        // The size guard is the part that does not depend on that diagnosis being right: a read whose byte count
        // disagrees with the file's real size on disk is refused rather than written. A read we cannot trust must
        // become a loud, retryable refusal — never a silent write.
        //
        // The TRUNCATION ITSELF IS STILL NOT COVERED BY A TEST, deliberately: the headless harness has no live
        // MasterFileSystem to hold a stale cache, so a temp-file test passes either way. One was written in August,
        // observed to pass under revert, and deleted rather than kept as false assurance. The guard's decision IS
        // tested — see describeShortRead and RefactoringProviderShortReadGuardTest.
        File onDisk = FileUtil.toFile(fo);
        if (onDisk == null) {
            return "Edit refused: no disk path for " + filePath;
        }
        Path diskPath = onDisk.toPath();
        // Resolved once and reused for every decode/encode below — the initial read,
        // the staleness re-check, and the final write must all agree, or oldString's
        // byte-for-byte match against what was read either fails or matches the wrong
        // text and clobbers it.
        Charset charset = resolveCharset(fo);
        String content;
        try {
            long sizeOnDisk = Files.size(diskPath);
            byte[] bytes = Files.readAllBytes(diskPath);
            String shortRead = describeShortRead(filePath, bytes.length, sizeOnDisk);
            if (shortRead != null) {
                return shortRead;
            }
            content = new String(bytes, charset);
        }
        catch (IOException e) {
            return "Read error: " + e.getMessage();
        }
        int idx = content.indexOf(oldString);
        if (idx < 0) {
            if (PluginSettings.isDebugJson()) {
                int hash = oldString.hashCode();
                String contentNormalized = content.replaceAll("\\s+", " ").trim();
                String oldStringNormalized = oldString.replaceAll("\\s+", " ").trim();
                boolean wsInsensitiveMatch = contentNormalized.contains(oldStringNormalized);
                int oldStringLen = oldString.length();
                int contentLen = content.length();
                LOG.info("ApplyEdit: oldString not found in " + filePath
                        + " | content: " + contentLen + " bytes"
                        + " | oldString: " + oldStringLen + " bytes, hash=" + hash
                        + " | wsInsensitiveMatch: " + wsInsensitiveMatch
                        + " | oldString: " + oldString);
            }
            String hint = PermissionDiffPolicy.diagnoseWhitespaceMismatch(content, oldString);
            if (hint != null) {
                return hint;
            }
            return McpToolPropertyEnum.OLD_STRING.key()
                    + " not found in file. A common cause is copying from GetFileContent and leaving its line-number gutter; "
                    + McpToolPropertyEnum.OLD_STRING.key()
                    + " must match the file byte-for-byte, including leading whitespace.";
        }
        String updated = replaceAll
                         ? PermissionDiffPolicy.replaceEvery(content, oldString, replacement)
                         : PermissionDiffPolicy.replaceFirst(content, oldString, replacement);
        try {
            // This guard exists to catch the file moving under us between match and write. It reads from disk for the
            // same reason the match above does — re-reading the same cache the match used would compare one possibly
            // truncated read against another and call them equal, which is how a stale view got all the way to a write.
            long sizeOnDisk = Files.size(diskPath);
            byte[] bytes = Files.readAllBytes(diskPath);
            String shortRead = describeShortRead(filePath, bytes.length, sizeOnDisk);
            if (shortRead != null) {
                return shortRead;
            }
            String current = new String(bytes, charset);
            if (!current.equals(content)) {
                return "Edit refused: file changed after approval; please retry";
            }
        }
        catch (IOException e) {
            return "Read error before edit: " + e.getMessage();
        }
        // Exact-byte write so the result is precisely the accepted diff (no On-Save
        // reformatting). Fall back to the editor document only when the file is locked.
        //
        // KNOWN, ACCEPTED RACE: another process can write this file between the verification just above and the
        // stream opening below, and we would overwrite it. The window is bounded by that verification — it is the gap
        // between two adjacent statements, not an unchecked write — and closing it properly needs OS-level locking or
        // a write-to-temp-and-atomic-move, and the latter would give up the exact-byte FileObject semantics this write
        // exists to preserve. Documented rather than half-closed: a race that reads as closed is worse than an open one
        // someone can see.
        try (OutputStream out = fo.getOutputStream()) {
            out.write(updated.getBytes(charset));
        }
        catch (FileAlreadyLockedException lockEx) {
            // Locked means the file is open in the editor with unsaved changes — which may be changes the user made
            // AFTER our verification. The fallback is handed the verified pre-edit content so it can refuse rather
            // than overwrite them.
            String viaDoc = writeViaDocument(fo, updated, content);
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
     * Normalises line endings so document text and disk bytes can be compared for equality.
     *
     * <p>
     * NetBeans documents hold {@code \n} internally whatever the file uses on disk, so a CRLF file read as bytes and
     * the same file read from its document differ on every single line. Comparing them raw is not a stricter check, it
     * is a broken one — it would report "changed" for every CRLF file and turn the locked-file path into a path that
     * never works.</p>
     */
    private static String normaliseLineEndings(String text) {
        return text == null ? null : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * True when the editor document still holds the text that passed disk verification, so replacing it would discard
     * nothing the caller has not accounted for.
     *
     * <p>
     * NOT a plain {@code equals}: see {@link #normaliseLineEndings} for why line endings are normalised on both sides
     * first. Do not "simplify" this back to {@code documentText.equals(verifiedContent)} — that breaks every file with
     * CRLF endings.</p>
     *
     * <p>
     * Pure, and separate from the write, so a test can drive identical text, changed text and the CRLF-versus-LF case
     * directly. That last one is the whole reason this function exists rather than an inline comparison.</p>
     */
    static boolean documentMatchesVerified(String documentText, String verifiedContent) {
        if (documentText == null || verifiedContent == null) {
            return false;
        }
        return normaliseLineEndings(documentText).equals(normaliseLineEndings(verifiedContent));
    }

    /**
     * Fallback writer for a file that is open in the editor with unsaved changes (a held write lock prevents a direct
     * FileObject write). Replaces the whole document and saves; this path can trigger On-Save reformatting, but it only
     * runs when a direct byte write is impossible.
     *
     * <p>
     * Refuses if the document no longer holds {@code verifiedContent}. The lock we are here because of is taken by a
     * dirty document, and the likeliest reason it is dirty is that the USER typed after
     * {@code flushUnsavedEditorChanges} made disk authoritative and after both disk verifications passed. Replacing the
     * document wholesale at that point would overwrite their typing and then save it — silent destruction of the user's
     * own work, the same class of defect as the truncating write, through a different door. Refuse first; never write
     * and then report.</p>
     *
     * <p>
     * There is no unguarded form and no null-baseline escape. Both callers establish a baseline from disk — a
     * read-modify-write inherits the content it verified, and a whole-file write reads it straight after the flush — so
     * a null here would mean a caller lost track of its own state, and the only safe answer to that is to refuse. A
     * guard with an opt-out is the one a future caller opts out of.</p>
     *
     * @param content the text to write
     * @param verifiedContent the content the document must still hold; required, and a null refuses the write
     */
    private static String writeViaDocument(FileObject fo, String content, String verifiedContent) {
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
                    String documentText = doc.getText(0, doc.getLength());
                    if (!documentMatchesVerified(documentText, verifiedContent)) {
                        result.set("Edit refused: this file changed in the editor after it was verified, so the edit "
                                + "was NOT applied and nothing was changed. Retry — the read is re-taken each attempt.");
                        return;
                    }
                    doc.remove(0, doc.getLength());
                    doc.insertString(0, content, null);
                    SaveCookie save = dob.getLookup().lookup(SaveCookie.class);
                    if (save == null) {
                        result.set("Write error: file is locked and cannot be saved");
                        return;
                    }
                    save.save();
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
            return McpToolPropertyEnum.SOURCE_PATH.key() + " is required";
        }
        if (targetDirectory == null || targetDirectory.isBlank()) {
            return McpToolPropertyEnum.TARGET_DIRECTORY.key() + " is required";
        }
        FileObject fo = resolveFileObject(sourcePath);
        if (fo == null) {
            return "File not found: " + sourcePath;
        }
        FileObject targetFo = FileUtils.resolveByPath(targetDirectory);
        if (targetFo == null || !targetFo.isFolder()) {
            return "Target directory not found: " + FileUtils.toIdePath(targetDirectory);
        }
        String destName = (newName != null && !newName.isBlank()) ? newName : fo.getName();
        try {
            FileUtil.copyFile(fo, targetFo, destName);
        }
        catch (IOException e) {
            return "Copy error at " + FileUtils.toIdePath(targetDirectory) + ": " + e.getMessage();
        }
        GitProvider.refreshVcsStatus(targetDirectory);
        return "Copied to " + FileUtils.toIdePath(new File(targetDirectory, destName + "." + fo.getExt()));
    }

    /**
     * @param targetDirectory the FINAL, already-resolved absolute directory — callers combine an optional
     * {@code targetProjectPath} with a possibly-relative {@code targetDirectory} via
     * {@link #resolveMoveTargetDirectory} BEFORE calling this, so the access check the tool runs beforehand and the
     * move performed here agree on the exact same path (see {@code MoveFileTool}).
     * @param commitWithWarning #18: now honoured instead of hardcoded {@code false} — MoveFile is exactly the tool a
     * cross-module move (#17b) hits the "non-fatal warning" refusal on, and until now it had no way to proceed past it
     * short of hand-editing.
     */
    public static String moveFile(String sourcePath, String targetDirectory, boolean commitWithWarning) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return McpToolPropertyEnum.SOURCE_PATH.key() + " is required";
        }
        if (targetDirectory == null || targetDirectory.isBlank()) {
            return McpToolPropertyEnum.TARGET_DIRECTORY.key() + " is required";
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
        String problemSuffix = null;
        if ("java".equals(fo.getExt())) {
            MoveRefactoring r = new MoveRefactoring(Lookups.singleton(fo));
            r.setTarget(Lookups.singleton(targetFo.toURL()));
            RefactoringRunResult result = runRefactoringInternal(r, commitWithWarning);
            if (!result.committed) {
                return result.blockedMessage;
            }
            problemSuffix = result.problemSuffix;
        }
        else {
            try {
                FileUtil.moveFile(fo, targetFo, fo.getName());
            }
            catch (IOException e) {
                return "Move error at " + FileUtils.toIdePath(targetDirectory) + ": " + e.getMessage();
            }
        }
        GitProvider.refreshVcsStatus(targetDirectory);
        if (sourceParent != null) {
            GitProvider.refreshVcsStatus(sourceParent.getAbsolutePath());
        }
        return problemSuffix != null ? "File moved" + problemSuffix : "File moved";
    }

    /**
     * Outcome of {@link #resolveMoveTargetDirectory}: exactly one of {@code path}/{@code error} is non-null.
     */
    public record TargetDirectoryResolution(String path, String error) {

        public static TargetDirectoryResolution ok(String path) {
            return new TargetDirectoryResolution(path, null);
        }

        public static TargetDirectoryResolution error(String error) {
            return new TargetDirectoryResolution(null, error);
        }
    }

    /**
     * Combines {@code targetDirectory} with an optional {@code targetProjectPath} (#17b) into the single absolute
     * directory a move should use. Pure path arithmetic — no filesystem or NetBeans API calls — so both sides of the
     * access-check boundary can agree on the identical resolved path without duplicating the combination rules:
     * {@code MoveFileTool} calls this BEFORE its {@code isFileWritable} scope check, and passes the resulting path, not
     * the raw arguments, on to {@link #moveFile}.
     * <ul>
     * <li>{@code targetProjectPath} omitted (null/blank): {@code targetDirectory} is used exactly as given — today's
     * behaviour, now explicit.</li>
     * <li>{@code targetProjectPath} given, {@code targetDirectory} relative: resolved AGAINST it, so a caller can write
     * {@code targetProjectPath=.../app-platform-rest, targetDirectory=src/main/java/.../oauth}.</li>
     * <li>{@code targetProjectPath} given, {@code targetDirectory} already absolute: must already be under
     * {@code targetProjectPath}; refused, naming both, if it is not.</li>
     * </ul>
     */
    public static TargetDirectoryResolution resolveMoveTargetDirectory(String targetDirectory, String targetProjectPath) {
        if (targetProjectPath == null || targetProjectPath.isBlank()) {
            return TargetDirectoryResolution.ok(targetDirectory);
        }
        Path projectPath;
        Path dirPath;
        try {
            projectPath = Path.of(targetProjectPath).toAbsolutePath().normalize();
            dirPath = Path.of(targetDirectory);
        }
        catch (InvalidPathException e) {
            return TargetDirectoryResolution.error("Malformed path in " + McpToolPropertyEnum.TARGET_DIRECTORY.key()
                    + " or " + McpToolPropertyEnum.TARGET_PROJECT_PATH.key() + ": " + e.getMessage());
        }
        Path resolved = (dirPath.isAbsolute() ? dirPath : projectPath.resolve(dirPath)).normalize();
        if (!resolved.startsWith(projectPath)) {
            return TargetDirectoryResolution.error(McpToolPropertyEnum.TARGET_DIRECTORY.key() + " '" + targetDirectory
                    + "' is not under " + McpToolPropertyEnum.TARGET_PROJECT_PATH.key() + " '" + targetProjectPath + "'.");
        }
        return TargetDirectoryResolution.ok(resolved.toString());
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
        String navResult = EditorContextProvider.openFile(diskFile2.getPath(), false);
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
                if (editor != null) {
                    editor.getCaret().setDot(editor.getCaret().getDot());
                }
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
     * Last check before the array is handed to {@code ChangeParametersRefactoring.setParameterInfo}, which is the point
     * of no return: NetBeans' transformer dereferences each entry's type while rewriting call sites, so an entry whose
     * type is still null takes the IDE down inside the refactoring rather than failing our call. See the note in
     * {@link #existingParamInfos} — {@code ParameterInfo(index)} alone leaves the type null.
     * <p>
     * {@link #mergeParameterInfos} resolves an omitted name/type from the parameter at {@code originalIndex}, but it
     * can only do that when the index actually points at one. An out-of-range index — {@code {"originalIndex": 99}}
     * against a three-parameter method — falls through its merge branch untouched and arrives here still null-typed.
     * That is a caller mistake, so it must come back as a readable error naming the offending entry, not as a crash and
     * not as an internal error the caller cannot act on.
     *
     * @param merged the resolved entries about to be set
     * @param existingCount how many parameters the method currently declares
     *
     * @return an error message to return to the caller, or null when the array is safe to hand over
     */
    static String validateParameterInfos(ParameterInfo[] merged, int existingCount) {
        for (int i = 0; i < merged.length; i++) {
            ParameterInfo p = merged[i];
            int idx = p.getOriginalIndex();
            if (idx < -1 || idx >= existingCount) {
                String valid = existingCount == 0
                               ? "the method has no parameters, so only -1 (a new parameter) is valid"
                               : "valid values are 0.." + (existingCount - 1) + " to keep an existing parameter, or -1 for a new one";
                return "Error: parameters[" + i + "]: originalIndex " + idx + " does not match this method, which declares "
                        + existingCount + " parameter(s) — " + valid;
            }
            if (p.getType() == null || p.getType().isBlank()) {
                return "Error: parameters[" + i + "]: type is required and could not be resolved from the existing "
                        + "signature — supply type explicitly for this entry";
            }
        }
        return null;
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
     * An EMPTY {@code requested} array is not the same as a null one and must not be folded into it. The tool schema
     * calls {@code parameters} "the complete desired parameter list — omit to keep existing params", so {@code []} is
     * an explicit request for a method with no parameters, while omission is the no-change signal. Treating both as
     * "keep existing" made removing every parameter impossible AND silent: the refactoring ran, changed nothing, and
     * still reported "Method signature updated". Only null returns {@code existing} now; an empty array falls through
     * and yields an empty result.
     *
     * @param requested entries built by the tool from the JSON {@code parameters} array, or null when omitted
     * @param existing the method's current parameters (see {@link #existingParamInfos})
     */
    static ParameterInfo[] mergeParameterInfos(ParameterInfo[] requested, ParameterInfo[] existing) {
        if (requested == null) {
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

    /**
     * Runs a refactoring's preCheck/prepare/doRefactoring pipeline and formats the outcome around {@code
     * successMessage}. Convenience wrapper around {@link #runRefactoringInternal} for the common case: a caller with
     * one success string and nothing to do after the refactoring itself. {@link #moveFile} calls
     * {@link #runRefactoringInternal} directly instead, because it still has its own file-move bookkeeping to run after
     * a successful Java move and before it knows its own final message.
     */
    private static String runRefactoring(AbstractRefactoring refactoring, boolean commitWithWarning, String successMessage) {
        RefactoringRunResult result = runRefactoringInternal(refactoring, commitWithWarning);
        if (!result.committed) {
            return result.blockedMessage;
        }
        return result.problemSuffix != null ? successMessage + result.problemSuffix : successMessage;
    }

    // flattenProblems/blockedMessageOrNull/buildProblemSuffix/RefactoringRunResult below are package-private rather
    // than private, purely as a test seam: that logic is pure (it classifies and formats Problem objects, never
    // touching the live refactoring engine), so it is exactly what a unit test should exercise directly — driving it
    // end to end instead would need a live NetBeans project with a real Java source, the same gap the single-file
    // success path already has.
    /**
     * Runs a refactoring's preCheck/prepare/doRefactoring pipeline, collecting EVERY problem reported at each stage —
     * {@link Problem} is a linked list via {@link Problem#getNext()}, so reporting only the head (the original
     * behaviour here) silently hid every problem after the first — and classifying each with {@link Problem#isFatal()}
     * so the caller can tell advice from a veto.
     * <p>
     * {@code commitWithWarning} is OUR policy, not something the refactoring engine enforces for us. Nothing in the
     * {@code Problem} API documents what {@code doRefactoring} itself would do if invoked past a fatal problem —
     * {@code isFatal()} is known to disable the Refactor button in NetBeans' own interactive dialogs, but whether the
     * engine would refuse outright or would happily commit broken code is not established by the API surface, and this
     * method never finds out, because a fatal problem stops it before {@code doRefactoring} is ever called. The actual
     * reason to refuse by default on ANY problem, fatal or not, is that these tools apply their change immediately with
     * no diff panel and no confirm step — a non-fatal problem is still the engine's own considered guess that something
     * behind the scenes will break, and committing that guess unreviewed is not a decision a tool should make silently
     * on an AI's behalf. {@code commitWithWarning = true} is permission to accept that risk for non-fatal problems
     * specifically. FATAL problems always block regardless: the flag means "proceed despite advice", never "ignore
     * errors".
     * <p>
     * A problem {@code doRefactoring} itself returns is reported separately from the pre-commit checks and never blocks
     * anything, fatal or not — by the time {@code doRefactoring} runs, the files are already being written, so unlike a
     * preCheck/prepare refusal this can never read as "nothing happened".
     */
    /**
     * Snapshots a move source before the engine runs and restores it if the engine returns after leaving neither the
     * source nor its expected destination on disk. RefactoringSession can throw after deleting its source; reporting
     * that as merely "blocked" without recovery turns an engine failure into data loss.
     */
    private static String runMoveRefactoringWithRecovery(AbstractRefactoring refactoring, boolean commitWithWarning,
                                                         String successMessage, FileObject source, FileObject targetFolder,
                                                         String targetName) {
        File sourceFile = FileUtil.toFile(source);
        File targetDirectory = FileUtil.toFile(targetFolder);
        if (sourceFile == null || !sourceFile.isFile()) {
            return "Cannot safely move an unavailable source file: " + (source != null ? source.getPath() : "<unknown>");
        }
        if (targetDirectory == null || !targetDirectory.isDirectory()) {
            return "Cannot safely move because the target directory is unavailable: "
                    + (targetFolder != null ? targetFolder.getPath() : "<unknown>");
        }
        byte[] sourceBytes;
        try {
            sourceBytes = Files.readAllBytes(sourceFile.toPath());
        }
        catch (IOException e) {
            return "Cannot safely move because the source could not be backed up: " + sourceFile.getPath()
                    + " (" + e.getMessage() + ")";
        }

        File expectedTarget = new File(targetDirectory, targetName);
        Map<Path, byte[]> targetSnapshot = snapshotJavaFiles(targetDirectory.toPath());
        byte[] expectedTargetBytes = readBytesOrNull(expectedTarget.toPath());
        String result = runRefactoring(refactoring, commitWithWarning, successMessage);
        return restoreSourceIfMoveLost(sourceFile, expectedTarget, sourceBytes, result,
                                       expectedTargetBytes, targetSnapshot, targetDirectory);
    }

    static String runMoveRefactoringWithRecoveryForTest(Supplier<String> engine, File sourceFile,
                                                        File targetDirectory, String targetName,
                                                        byte[] sourceBytes, String successMessage) {
        File expectedTarget = new File(targetDirectory, targetName);
        Map<Path, byte[]> targetSnapshot = snapshotJavaFiles(targetDirectory.toPath());
        byte[] expectedTargetBytes = readBytesOrNull(expectedTarget.toPath());
        String result;
        try {
            result = engine.get();
        }
        catch (RuntimeException e) {
            result = "Refactoring failed during commit: " + e.getMessage();
        }
        return restoreSourceIfMoveLost(sourceFile, expectedTarget, sourceBytes, result,
                                       expectedTargetBytes, targetSnapshot, targetDirectory);
    }

    /**
     * Restores a move source only when the engine left neither it nor the expected destination on disk. Package-private
     * so the no-data-loss recovery can be proven without starting the NetBeans refactoring engine.
     */
    static String restoreSourceIfMoveLost(File sourceFile, File expectedTarget, byte[] sourceBytes, String engineResult) {
        return restoreSourceIfMoveLost(sourceFile, expectedTarget, sourceBytes, engineResult, null, Map.of(),
                                       expectedTarget.getParentFile());
    }

    static String restoreSourceIfMoveLost(File sourceFile, File expectedTarget, byte[] sourceBytes,
                                          String engineResult, byte[] expectedTargetBytes,
                                          Map<Path, byte[]> targetSnapshot, File targetDirectory) {
        // A source still on disk is never written to, so there is nothing to report. It is also routinely CHANGED by
        // a successful move: extracting one class from a multi-type file rewrites the source without that class.
        if (sourceFile.isFile()) {
            return engineResult;
        }
        byte[] currentTargetBytes = readBytesOrNull(expectedTarget.toPath());
        boolean targetChanged = currentTargetBytes != null
                && (expectedTargetBytes == null || !Arrays.equals(expectedTargetBytes, currentTargetBytes));
        if (targetChanged) {
            return engineResult;
        }
        if (hasNewOrChangedJavaFile(targetDirectory.toPath(), targetSnapshot,
                                    expectedTarget.toPath().toAbsolutePath().normalize())) {
            return engineResult + " The source is missing, but the move may have landed under a collision-renamed "
                    + "path in target folder " + targetDirectory.getPath() + "; inspect that folder before retrying.";
        }
        try {
            Files.createDirectories(sourceFile.toPath().getParent());
            if (sourceFile.isFile()) {
                return engineResult + " The source changed concurrently; it was not overwritten.";
            }
            Files.write(sourceFile.toPath(), sourceBytes);
            FileUtil.refreshFor(sourceFile.getParentFile(), sourceFile);
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.WARNING, "Restored source after MoveRefactoring lost both source and target: {0}",
                        sourceFile.getPath());
            }
            return "Move failed after the refactoring engine removed the source; the original source was restored: "
                    + sourceFile.getPath() + ". Engine result: " + engineResult;
        }
        catch (IOException restoreFailure) {
            LOG.log(Level.SEVERE, "MoveRefactoring lost source and target; source restoration also failed: "
                    + sourceFile.getPath(), restoreFailure);
            return "CRITICAL: move removed both source and target and restoration failed for " + sourceFile.getPath()
                    + ": " + restoreFailure.getMessage() + ". Engine result: " + engineResult;
        }
    }

    private static byte[] readBytesOrNull(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
        }
        catch (IOException e) {
            return null;
        }
    }

    private static Map<Path, byte[]> snapshotJavaFiles(Path directory) {
        Map<Path, byte[]> snapshot = new HashMap<>();
        File[] files = directory.toFile().listFiles(file -> file.isFile() && file.getName().endsWith(".java"));
        if (files != null) {
            for (File file : files) {
                byte[] bytes = readBytesOrNull(file.toPath());
                if (bytes != null) {
                    snapshot.put(file.toPath().toAbsolutePath().normalize(), bytes);
                }
            }
        }
        return snapshot;
    }

    private static boolean hasNewOrChangedJavaFile(Path directory, Map<Path, byte[]> snapshot, Path expectedTarget) {
        File[] files = directory.toFile().listFiles(file -> file.isFile() && file.getName().endsWith(".java"));
        if (files == null) {
            return false;
        }
        for (File file : files) {
            Path path = file.toPath().toAbsolutePath().normalize();
            if (path.equals(expectedTarget)) {
                continue;
            }
            byte[] before = snapshot.get(path);
            byte[] now = readBytesOrNull(path);
            if (before == null || now == null || !Arrays.equals(before, now)) {
                return true;
            }
        }
        return false;
    }

    static final String NOTHING_TO_CHANGE = "Nothing was changed: the refactoring found nothing to apply at this location.";

    static RefactoringRunResult runRefactoringInternal(AbstractRefactoring refactoring, boolean commitWithWarning) {
        try {
            List<Problem> preProblems = flattenProblems(refactoring.preCheck());
            String blocked = blockedMessageOrNull(preProblems, commitWithWarning);
            if (blocked != null) {
                return RefactoringRunResult.blocked(blocked);
            }

            RefactoringSession session = RefactoringSession.create("CC Plugin Refactoring");
            try {
                List<Problem> prepareProblems = flattenProblems(refactoring.prepare(session));
                List<Problem> tolerated = new ArrayList<>(preProblems);
                tolerated.addAll(prepareProblems);
                blocked = blockedMessageOrNull(prepareProblems, commitWithWarning);
                if (blocked != null) {
                    return RefactoringRunResult.blocked(blocked);
                }
                // The engine accepted the request but prepared no change, e.g. InlineVariable on a reassigned variable
                // (live v1.4.15, AiCoderCodex_2). Committing an empty session and returning the success message claimed a
                // change that never happened.
                if (session.getRefactoringElements().isEmpty()) {
                    return RefactoringRunResult.blocked(NOTHING_TO_CHANGE);
                }

                try {
                    List<Problem> postProblems = flattenProblems(session.doRefactoring(true));
                    return RefactoringRunResult.committed(buildProblemSuffix(tolerated, postProblems));
                }
                catch (Exception e) {
                    String msg = e.getMessage();
                    LOG.log(Level.WARNING, "Refactoring failed during commit; files may be partially applied", e);
                    return RefactoringRunResult.blocked("Refactoring failed during commit; files may be partially applied: "
                            + (msg != null ? msg : e.getClass().getName()));
                }
            }
            finally {
                session.finished();
            }
        }
        catch (Exception e) {
            String msg = e.getMessage();
            LOG.log(Level.WARNING, "Refactoring failed before commit", e);
            return RefactoringRunResult.blocked("Refactoring blocked before commit: "
                    + (msg != null ? msg : e.getClass().getName()));
        }
    }

    /**
     * Walks a {@link Problem} chain via {@link Problem#getNext()} into a plain list, head first. Empty (never null)
     * when {@code head} is null. Package-private: see the test-seam note above.
     */
    static List<Problem> flattenProblems(Problem head) {
        List<Problem> problems = new ArrayList<>();
        for (Problem p = head; p != null; p = p.getNext()) {
            problems.add(p);
        }
        return problems;
    }

    /**
     * Null when this stage's problems do not block proceeding: none at all, or all non-fatal with {@code
     * commitWithWarning} true. Otherwise the full "Refactoring blocked" text. Package-private: see the test-seam note
     * above.
     * <p>
     * A fatal problem always blocks and the message never mentions {@code commitWithWarning} — suggesting it there
     * would invite a retry that cannot work, since the flag has no effect on a fatal problem. A warnings-only refusal
     * (no fatal problems, {@code commitWithWarning} false or absent) DOES name the flag and say it will let the
     * refactoring proceed: an AI reading this refusal cannot see the schema, and a refusal that does not say how to get
     * past it leaves only worse options — giving up, retrying the identical call, or hand-editing the files.
     */
    static String blockedMessageOrNull(List<Problem> problems, boolean commitWithWarning) {
        if (problems.isEmpty()) {
            return null;
        }
        boolean anyFatal = problems.stream().anyMatch(Problem::isFatal);
        if (!anyFatal && commitWithWarning) {
            return null;
        }
        StringBuilder sb = new StringBuilder("Refactoring blocked");
        sb.append(anyFatal ? " — fatal problem(s) reported:\n" : " — warning(s) reported, no fatal problems:\n");
        appendProblemLines(sb, problems);
        if (!anyFatal) {
            sb.append("\nSet ").append(McpToolPropertyEnum.COMMIT_WITH_WARNING.key())
                    .append(" to true to apply this refactoring despite these warnings.");
        }
        return sb.toString();
    }

    /**
     * The trailing text to append after a success message when a refactoring committed with problems still worth
     * reporting: non-fatal problems tolerated pre-commit via {@code commitWithWarning}, and/or problems
     * {@code doRefactoring} itself returned after already writing the change to disk. Null when both are empty — a
     * clean commit gets no suffix at all. Package-private: see the test-seam note above.
     */
    static String buildProblemSuffix(List<Problem> tolerated, List<Problem> postCommit) {
        StringBuilder suffix = new StringBuilder();
        if (!tolerated.isEmpty()) {
            suffix.append("\n\nCommitted with warning(s) tolerated by ")
                    .append(McpToolPropertyEnum.COMMIT_WITH_WARNING.key()).append(":\n");
            appendProblemLines(suffix, tolerated);
        }
        if (!postCommit.isEmpty()) {
            suffix.append("\n\nThe refactoring engine reported problem(s) AFTER applying this change "
                    + "(already written to disk):\n");
            appendProblemLines(suffix, postCommit);
        }
        return suffix.length() > 0 ? suffix.toString() : null;
    }

    private static void appendProblemLines(StringBuilder sb, List<Problem> problems) {
        for (Problem p : problems) {
            sb.append(p.isFatal() ? "[FATAL] " : "[WARNING] ").append(p.getMessage()).append('\n');
        }
    }

    /**
     * Outcome of {@link #runRefactoringInternal}: either blocked with the full refusal text, or committed with an
     * optional trailing problem report to append after a caller's own success message. Package-private: see the
     * test-seam note above.
     */
    static final class RefactoringRunResult {

        final boolean committed;
        final String blockedMessage;
        final String problemSuffix;

        private RefactoringRunResult(boolean committed, String blockedMessage, String problemSuffix) {
            this.committed = committed;
            this.blockedMessage = blockedMessage;
            this.problemSuffix = problemSuffix;
        }

        static RefactoringRunResult blocked(String message) {
            return new RefactoringRunResult(false, message, null);
        }

        static RefactoringRunResult committed(String problemSuffix) {
            return new RefactoringRunResult(true, null, problemSuffix);
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
                CompilationUnitTree cu = cc.getCompilationUnit();
                LineMap lineMap = cu.getLineMap();
                SourcePositions sp = cc.getTrees().getSourcePositions();
                TreePath best = bestDeclarationAtLine(cu, lineMap, sp, line);
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
     * The declaration (class/interface/enum/record, method, or field/local variable) at or enclosing {@code line} —
     * preferring the narrowest kind (Method &gt; Variable &gt; Class) and an EXACT start-line match over a SPANNING
     * one. Mirrors {@link JavaSourceUtils#classAtLine}, generalised from classes alone to all three declaration kinds
     * {@code RenameSymbol} can target.
     * <p>
     * An exact-start-line match used to be the WHOLE algorithm, and it failed on any declaration preceded by javadoc or
     * an annotation: javac reports the tree's start position at the comment/annotation, not at the
     * {@code class}/{@code record}/method/field keyword, so pointing at the declaration line itself — exactly what
     * every one of these tools documents as the contract — found no tree whose start position equalled that line
     * (confirmed on {@code ManagedFileSummary.java}, a record with a preceding javadoc block and {@code @Dto}). The
     * span fallback below is {@code classAtLine}'s fix, applied to all three kinds: when nothing starts exactly on
     * {@code line}, the innermost declaration whose full span (javadoc/annotations through closing brace) CONTAINS
     * {@code line} is used instead.
     */
    private static TreePath bestDeclarationAtLine(CompilationUnitTree cu, LineMap lineMap, SourcePositions sp, int line) {
        class Finder extends TreePathScanner<Void, Void> {

            TreePath bestExact;
            int bestExactPriority = -1;
            TreePath bestSpanning;
            int bestSpanningPriority = -1;

            void consider(Tree node, int priority) {
                long start = sp.getStartPosition(cu, node);
                long end = sp.getEndPosition(cu, node);
                if (start < 0 || end < 0) {
                    return;
                }
                long startLine = lineMap.getLineNumber(start);
                long endLine = lineMap.getLineNumber(end);
                if (startLine == line && priority > bestExactPriority) {
                    bestExact = getCurrentPath();
                    bestExactPriority = priority;
                }
                // >= rather than >: scanning descends, so a more deeply NESTED declaration of the
                // SAME priority (an inner class inside an outer one, both ClassTree) is visited
                // after the one enclosing it and must overwrite it — see classAtLine's identical rule.
                if (line >= startLine && line <= endLine && priority >= bestSpanningPriority) {
                    bestSpanning = getCurrentPath();
                    bestSpanningPriority = priority;
                }
            }

            @Override
            public Void visitClass(ClassTree node, Void unused) {
                consider(node, 0);
                return super.visitClass(node, unused);
            }

            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                consider(node, 2);
                return super.visitMethod(node, unused);
            }

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                consider(node, 1);
                return super.visitVariable(node, unused);
            }
        }
        Finder finder = new Finder();
        finder.scan(cu, null);
        return finder.bestExact != null ? finder.bestExact : finder.bestSpanning;
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
        // location than the FileObject returned. Nor would refreshing the resolved object be enough — refresh() re-stats
        // from last-modified, so an unchanged mtime keeps a stale cached length and asBytes() keeps returning a short
        // read. Content reads must go to disk and check the byte count against the real size — see applyEdit.
        FileUtil.refreshFor(f.getParentFile(), f);
        return FileUtils.resolveByFile(f);
    }

    /**
     * A refactoring must never be constructed from an invalid or disappeared source FileObject. This is deliberately
     * checked immediately before creating the NetBeans refactoring as directory creation and external filesystem events
     * can invalidate a previously resolved object.
     */
    static boolean isUsableRefactoringFile(FileObject fileObject) {
        if (fileObject == null || !fileObject.isValid() || !fileObject.isData()) {
            return false;
        }
        File diskFile = FileUtil.toFile(fileObject);
        return diskFile != null && diskFile.isFile();
    }

    private static boolean isValidJavaPackageName(String name) {
        if (name.startsWith("java.") || name.startsWith("javax.")
                || name.equals("java") || name.equals("javax")) {
            return false;
        }
        return name.matches("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$");
    }

    private static boolean isTopLevelTypeMatchingFilename(TreePathHandle handle, FileObject fo) {
        String filename = fo.getName();
        JavaSource js = JavaSource.forFileObject(fo);
        if (js == null) {
            return false;
        }
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        try {
            js.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.RESOLVED);
                TreePath path = handle.resolve(cc);
                if (path == null) {
                    return;
                }
                Tree leaf = path.getLeaf();
                if (!(leaf instanceof ClassTree ct)) {
                    return;
                }
                TreePath parent = path.getParentPath();
                if (parent == null || !(parent.getLeaf() instanceof CompilationUnitTree)) {
                    return;
                }
                String className = ct.getSimpleName().toString();
                if (className.equals(filename)) {
                    result.set(true);
                }
            }, true);
        }
        catch (IOException | RuntimeException ex) {
            return false;
        }
        return result.get();
    }

    /**
     * Resolves (creating if necessary) the folder for {@code packageName}.
     *
     * @param sourceFile the file being moved. Its own project supplies the default root set, and anchors the "already
     * under one of these roots" check below, exactly as before {@code targetProjectPath} existed.
     * @param packageName dot-separated target package.
     * @param targetProjectPath optional (#17). Omitted: behaviour is unchanged — search, then create if needed, under
     * {@code sourceFile}'s own project roots only. Given: search/create under THAT project's own Java source roots
     * instead, regardless of which project {@code sourceFile} belongs to — this is what makes a cross-module move land
     * in the right place instead of silently inside {@code sourceFile}'s own module.
     *
     * @return the resolved/created folder, or null when the relevant roots (source file's own, or the named project's)
     * cannot be determined — the caller distinguishes the two cases by whether {@code targetProjectPath} was given.
     */
    private static FileObject findOrCreatePackage(FileObject sourceFile, String packageName, String targetProjectPath) {
        ClassPath cp;
        if (targetProjectPath != null && !targetProjectPath.isBlank()) {
            Project targetProject = resolveOpenProjectByPath(targetProjectPath);
            if (targetProject == null) {
                return null;
            }
            SourceGroup[] groups = ProjectUtils.getSources(targetProject).getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA);
            if (groups.length == 0) {
                return null;
            }
            // Anchoring ClassPath.getClassPath on the source group's own root folder reuses the exact same
            // roots-lookup mechanism as the default (sourceFile-anchored) case below, so the search/create logic
            // that follows does not need two implementations.
            cp = ClassPath.getClassPath(groups[0].getRootFolder(), ClassPath.SOURCE);
        }
        else {
            cp = ClassPath.getClassPath(sourceFile, ClassPath.SOURCE);
        }
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
            // targetProjectPath given: the caller has already picked which module owns this package, so any of ITS
            // roots is a legitimate place to create it — the "is sourceFile under this root" anchor only makes sense
            // for the default (same-module) case, where cp's roots are sourceFile's own.
            if (targetProjectPath != null && !targetProjectPath.isBlank()
                    || FileUtil.getRelativePath(root, sourceFile) != null) {
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

    /**
     * Resolves {@code projectPath} to one of the currently open projects by comparing real directories — same pattern
     * as {@code ProjectActionProvider}'s build-tool project resolution. Null when no open project's directory matches.
     */
    private static Project resolveOpenProjectByPath(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return null;
        }
        File requested = FileUtils.toRealPath(new File(projectPath));
        for (Project candidate : OpenProjects.getDefault().getOpenProjects()) {
            File root = FileUtil.toFile(candidate.getProjectDirectory());
            if (root != null && FileUtils.toRealPath(root).equals(requested)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The #17 silent-misplacement guard for the {@code targetProjectPath}-omitted case: when {@code packageName} does
     * not already exist under {@code sourceFile}'s own project but DOES exist under a different open project's Java
     * source roots, creating it in the source project would almost certainly be wrong — the caller very likely meant
     * the package that already exists elsewhere. Refuses by naming both projects rather than guessing.
     *
     * @return the ready-to-return refusal message, or null when the omitted-{@code targetProjectPath} default
     * (search/create under {@code sourceFile}'s own project) may proceed normally — either because the package already
     * exists there, or because no OTHER open project claims it either
     */
    private static String packageBelongsToOtherOpenProjectMessage(FileObject sourceFile, String packageName) {
        String packagePath = packageName.replace('.', '/');
        ClassPath ownCp = ClassPath.getClassPath(sourceFile, ClassPath.SOURCE);
        if (ownCp != null) {
            for (FileObject root : ownCp.getRoots()) {
                if (root.getFileObject(packagePath) != null) {
                    return null; // already exists in the source file's own project — no misplacement risk
                }
            }
        }
        Project ownProject = FileOwnerQuery.getOwner(sourceFile);
        for (Project candidate : OpenProjects.getDefault().getOpenProjects()) {
            if (candidate.equals(ownProject)) {
                continue;
            }
            for (SourceGroup group : ProjectUtils.getSources(candidate).getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA)) {
                ClassPath cp = ClassPath.getClassPath(group.getRootFolder(), ClassPath.SOURCE);
                if (cp == null) {
                    continue;
                }
                for (FileObject root : cp.getRoots()) {
                    if (root.getFileObject(packagePath) != null) {
                        String otherName = ProjectUtils.getInformation(candidate).getDisplayName();
                        String ownName = ownProject != null
                                         ? ProjectUtils.getInformation(ownProject).getDisplayName() : "the source file's project";
                        return "Error: " + McpToolPropertyEnum.TARGET_PACKAGE.key() + " '" + packageName
                                + "' belongs to project " + otherName + ", not " + ownName + ". Pass "
                                + McpToolPropertyEnum.TARGET_PROJECT_PATH.key() + " to move it there.";
                    }
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

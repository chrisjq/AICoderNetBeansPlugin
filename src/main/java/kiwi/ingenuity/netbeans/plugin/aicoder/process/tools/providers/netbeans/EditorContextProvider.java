package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import org.netbeans.api.editor.EditorRegistry;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.Sources;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.api.queries.FileEncodingQuery;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.windows.TopComponent;

public class EditorContextProvider {

    private static final Logger LOG = Logger.getLogger(EditorContextProvider.class.getName());
    private static final int MAX_FILE_CONTENT_CHARS = 200_000;

    public static String getSelectedText() {
        AtomicReference<String> ref = new AtomicReference<>("No editor focused");
        try {
            SwingUtilities.invokeAndWait(() -> {
                JTextComponent editor = EditorRegistry.lastFocusedComponent();
                if (editor == null) {
                    return;
                }
                String s = editor.getSelectedText();
                ref.set(s == null || s.isBlank() ? "No text selected" : s);
            });
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "getSelectedText error", e);
            return "Error: " + e.getMessage();
        }
        return ref.get();
    }

    public static String getCurrentFile() {
        AtomicReference<String> ref = new AtomicReference<>("No editor focused");
        try {
            SwingUtilities.invokeAndWait(() -> {
                JTextComponent editor = EditorRegistry.lastFocusedComponent();
                if (editor == null) {
                    return;
                }
                Document doc = editor.getDocument();
                FileObject fo = fileObjectFromDoc(doc);
                if (fo == null) {
                    ref.set("Cannot determine current file");
                    return;
                }
                File f = FileUtil.toFile(fo);
                String path = f != null ? f.getPath() : fo.getPath();
                int caretPos = editor.getCaretPosition();
                javax.swing.text.Element root = doc.getDefaultRootElement();
                int line = root.getElementIndex(caretPos) + 1;
                int col = caretPos - root.getElement(line - 1).getStartOffset() + 1;
                ref.set(path + ":" + line + ":" + col);
            });
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "getCurrentFile error", e);
            return "Error: " + e.getMessage();
        }
        return ref.get();
    }

    /**
     * Caret position in the focused editor as {@code line:column}, or null when
     * there is no editor or the caret cannot be read.
     *
     * <p>
     * Unlike {@link #getCurrentFile()} this is safe to call from the EDT: it
     * reads directly when already on the dispatch thread instead of calling
     * {@code invokeAndWait}, which throws when invoked from the EDT itself. The
     * context preamble is built on the EDT, which is why the plain accessor
     * cannot be reused there.
     *
     * <p>
     * This is informational only. Tools deliberately do not act on the caret —
     * the caller cannot see it, so a tool that used it would behave differently
     * depending on where the user last clicked. Supplying the position lets the
     * caller decide whether the user's location is relevant and pass it
     * explicitly.
     */
    public static String getCaretLineColumn() {
        AtomicReference<String> ref = new AtomicReference<>(null);
        Runnable read = () -> {
            JTextComponent editor = EditorRegistry.lastFocusedComponent();
            if (editor == null) {
                return;
            }
            try {
                Document doc = editor.getDocument();
                int caretPos = editor.getCaretPosition();
                javax.swing.text.Element root = doc.getDefaultRootElement();
                int line = root.getElementIndex(caretPos) + 1;
                int col = caretPos - root.getElement(line - 1).getStartOffset() + 1;
                ref.set(line + ":" + col);
            }
            catch (RuntimeException e) {
                LOG.log(Level.FINE, "getCaretLineColumn error", e);
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                read.run();
            }
            else {
                SwingUtilities.invokeAndWait(read);
            }
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "getCaretLineColumn dispatch error", e);
        }
        return ref.get();
    }

    public static String getCurrentFileContent() {
        AtomicReference<String> ref = new AtomicReference<>("No editor focused");
        try {
            SwingUtilities.invokeAndWait(() -> {
                JTextComponent editor = EditorRegistry.lastFocusedComponent();
                if (editor == null) {
                    return;
                }
                Document doc = editor.getDocument();
                FileObject fo = fileObjectFromDoc(doc);
                File f = fo != null ? FileUtil.toFile(fo) : null;
                String path = f != null ? f.getPath() : (fo != null ? fo.getPath() : "unknown");
                try {
                    String text = doc.getText(0, doc.getLength());
                    if (text.length() > MAX_FILE_CONTENT_CHARS) {
                        text = text.substring(0, MAX_FILE_CONTENT_CHARS) + "\n[truncated at " + MAX_FILE_CONTENT_CHARS + " chars]";
                    }
                    ref.set("File: " + path + "\n\n" + text);
                }
                catch (javax.swing.text.BadLocationException ex) {
                    ref.set("Error reading content: " + ex.getMessage());
                }
            });
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "getCurrentFileContent error", e);
            return "Error: " + e.getMessage();
        }
        return ref.get();
    }

    public static String getCurrentFilePath() {
        AtomicReference<String> ref = new AtomicReference<>(null);
        try {
            SwingUtilities.invokeAndWait(() -> {
                JTextComponent editor = EditorRegistry.lastFocusedComponent();
                if (editor == null) {
                    return;
                }
                Document doc = editor.getDocument();
                FileObject fo = fileObjectFromDoc(doc);
                if (fo == null) {
                    return;
                }
                File f = FileUtil.toFile(fo);
                ref.set(f != null ? f.getPath() : fo.getPath());
            });
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "getCurrentFilePath error", e);
        }
        return ref.get();
    }

    public static String getFileContent(String filePath, int startLine, int endLine) {
        File f = new File(filePath);
        if (!f.exists() || !f.isFile()) {
            return buildNotFoundMessage(filePath);
        }
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            int from = startLine > 0 ? Math.max(0, startLine - 1) : 0;
            int to = endLine > 0 ? Math.min(lines.size(), endLine) : lines.size();
            StringBuilder sb = new StringBuilder();
            // Include the whole-file size so a caller whose own tool-result limit
            // truncates large results (many agent harnesses cap at ~20 KB) can
            // see up front that it needs to page through with startLine/endLine,
            // rather than discovering it from a clipped first read.
            sb.append("File: ").append(filePath).append(" (lines ")
                    .append(from + 1).append("–").append(to).append(" of ").append(lines.size())
                    .append(", ").append(f.length()).append(" bytes)\n\n");
            for (int i = from; i < to; i++) {
                sb.append(String.format("%4d  %s%n", i + 1, lines.get(i)));
                if (sb.length() > MAX_FILE_CONTENT_CHARS) {
                    sb.append("\n[Truncated: output exceeded ").append(MAX_FILE_CONTENT_CHARS)
                            .append(" characters. Use startLine/endLine to read a specific range.]");
                    break;
                }
            }
            return sb.toString();
        }
        catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    /**
     * Reports a file's metadata without returning its contents: exact byte
     * size, line count, text encoding, last-modified time and age, whether it
     * is writable, and whether the editor holds unsaved changes for it. Lets a
     * caller decide whether a read needs paging (a caller's result limit may
     * clip a large GetFileContent) before spending the tokens, which charset
     * the bytes decode with, how stale the on-disk copy is, and whether an edit
     * will be permitted. The encoding is resolved through NetBeans' own
     * {@link FileEncodingQuery} so it matches how the editor reads the file
     * (per-project charset settings, detection); the size/line count are the
     * on-disk copy — an "unsaved editor changes" flag warns when the editor's
     * in-memory copy has diverged from it.
     */
    public static String getFileSizeAndMeta(String filePath) {
        File f = new File(filePath);
        if (!f.exists() || !f.isFile()) {
            return "File not found: " + filePath;
        }
        long bytes = f.length();

        // Resolve the FileObject once — both the encoding query and the
        // unsaved-changes check need it. Best-effort: a null or throwing resolve
        // just omits the IDE-derived fields rather than failing the whole call.
        FileObject fo = null;
        try {
            fo = FileUtil.toFileObject(FileUtil.normalizeFile(f));
        }
        catch (Throwable t) {
            LOG.log(Level.FINE, "toFileObject failed for " + filePath, t);
        }

        String encoding = "unknown";
        if (fo != null) {
            try {
                Charset cs = FileEncodingQuery.getEncoding(fo);
                if (cs != null) {
                    encoding = cs.name();
                }
            }
            catch (Throwable t) {
                // FileEncodingQuery pulls in ProjectManager/global Lookup, which
                // may be absent outside a fully started IDE (e.g. tests) and
                // fails with an Error, not an Exception. Encoding is best-effort
                // — degrade to "unknown" rather than failing.
                LOG.log(Level.FINE, "encoding query failed for " + filePath, t);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(filePath).append(": ").append(bytes).append(" bytes");
        try {
            long lineCount = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8).size();
            sb.append(", ").append(lineCount).append(" lines");
        }
        catch (IOException e) {
            // Size and encoding are still useful even if the file is not
            // decodable as UTF-8 text (e.g. a binary or non-UTF-8 file).
            sb.append(", line count unavailable (").append(e.getMessage()).append(")");
        }
        sb.append(", encoding ").append(encoding);

        // Last-modified timestamp (UTC, second precision) plus age in seconds so
        // a caller can judge staleness without a second clock call of its own.
        long modMillis = f.lastModified();
        if (modMillis > 0) {
            Instant modified = Instant.ofEpochMilli(modMillis).truncatedTo(ChronoUnit.SECONDS);
            long ageSeconds = Math.max(0, (System.currentTimeMillis() - modMillis) / 1000);
            sb.append(", modified ").append(modified).append(" (").append(ageSeconds).append("s ago)");
        }

        // Writable flag — lets a caller know an edit will be permitted before it
        // reaches the diff panel (generated/read-only files fail there).
        sb.append(f.canWrite() ? ", writable" : ", read-only");

        // Unsaved editor changes: the on-disk size/line count above may lag the
        // editor's in-memory copy when this is set.
        if (fo != null) {
            try {
                DataObject dob = DataObject.find(fo);
                sb.append(dob.isModified() ? ", unsaved editor changes" : ", no unsaved changes");
            }
            catch (Throwable t) {
                LOG.log(Level.FINE, "unsaved-changes check failed for " + filePath, t);
            }
        }
        return sb.toString();
    }

    public static String navigateToLine(String filePath, int lineNumber) {
        return navigateToLine(filePath, lineNumber, true);
    }

    /**
     * Opens a file in the editor and optionally scrolls to a line. Pass
     * focus=true when showing the user something; false for internal tool use.
     */
    public static String navigateToLine(String filePath, int lineNumber, boolean focus) {
        File f = new File(filePath);
        if (!f.exists()) {
            return "File not found: " + filePath;
        }
        FileObject fo = FileUtils.resolveByFile(f);
        if (fo == null) {
            return "Cannot resolve file: " + filePath;
        }
        final int effectiveLine = Math.max(1, lineNumber);
        org.openide.text.Line.ShowVisibilityType visibility = focus
                ? org.openide.text.Line.ShowVisibilityType.FOCUS
                : org.openide.text.Line.ShowVisibilityType.NONE;
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    org.openide.loaders.DataObject dob = org.openide.loaders.DataObject.find(fo);
                    org.openide.cookies.LineCookie lc = dob.getLookup().lookup(org.openide.cookies.LineCookie.class);
                    if (lc != null) {
                        org.openide.text.Line line = lc.getLineSet().getCurrent(effectiveLine - 1);
                        line.show(org.openide.text.Line.ShowOpenType.OPEN, visibility);
                    }
                    else {
                        org.openide.cookies.OpenCookie oc = dob.getLookup().lookup(org.openide.cookies.OpenCookie.class);
                        if (oc != null) {
                            oc.open();
                        }
                    }
                }
                catch (org.openide.loaders.DataObjectNotFoundException ex) {
                    LOG.log(Level.FINE, "navigateToLine: file not found in DataObject system", ex);
                }
            });
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "navigateToLine error", e);
            return "Error navigating: " + e.getMessage();
        }
        return "Navigated to " + filePath + ":" + effectiveLine;
    }

    public static String getOpenFiles() {
        AtomicReference<String> ref = new AtomicReference<>("");
        try {
            SwingUtilities.invokeAndWait(() -> {
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                for (TopComponent tc : TopComponent.getRegistry().getOpened()) {
                    DataObject dob = tc.getLookup().lookup(DataObject.class);
                    if (dob == null) {
                        continue;
                    }
                    FileObject fo = dob.getPrimaryFile();
                    if (fo.isFolder()) {
                        continue;
                    }
                    File f = FileUtil.toFile(fo);
                    seen.add(f != null ? f.getPath() : fo.getPath());
                }
                ref.set(String.join("\n", seen));
            });
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "getOpenFiles error", e);
            return "Error: " + e.getMessage();
        }
        String result = ref.get();
        return result.isEmpty() ? "No files open" : result;
    }

    public static String getClipboard() {
        try {
            var t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return (String) t.getTransferData(DataFlavor.stringFlavor);
            }
            return "Clipboard does not contain text";
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "getClipboard error", e);
            return "Error reading clipboard: " + e.getMessage();
        }
    }

    private static FileObject fileObjectFromDoc(Document doc) {
        Object sd = doc.getProperty(Document.StreamDescriptionProperty);
        if (sd instanceof DataObject dob) {
            return dob.getPrimaryFile();
        }
        if (sd instanceof FileObject fo) {
            return fo;
        }
        return null;
    }

    static String buildNotFoundMessage(String filePath) {
        return buildNotFoundMessage(filePath, collectSourceRoots());
    }

    static String buildNotFoundMessage(String filePath, List<File> sourceRoots) {
        String simpleName = new File(filePath).getName();
        List<String> candidates = new ArrayList<>();
        int scanned = 0;
        scan:
        for (File root : sourceRoots) {
            ArrayDeque<File> queue = new ArrayDeque<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                File dir = queue.poll();
                File[] children = dir.listFiles();
                if (children == null) {
                    continue;
                }
                for (File child : children) {
                    if (child.isDirectory()) {
                        queue.add(child);
                    }
                    else {
                        scanned++;
                        if (scanned > 20_000) {
                            break scan;
                        }
                        if (child.getName().equals(simpleName)) {
                            candidates.add(child.getAbsolutePath());
                            if (candidates.size() >= 5) {
                                break scan;
                            }
                        }
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder("File not found: ").append(filePath);
        if (candidates.isEmpty()) {
            sb.append("\n\nNo file with that name in the open projects. Use GetProjectStructure for the package layout, or SearchSymbols/SearchInFiles to locate it.");
        }
        else {
            sb.append("\n\nDid you mean:");
            for (String c : candidates) {
                sb.append("\n  ").append(c);
            }
        }
        return sb.toString();
    }

    private static List<File> collectSourceRoots() {
        List<File> roots = new ArrayList<>();
        try {
            Project[] projects = OpenProjects.getDefault().getOpenProjects();
            for (Project project : projects) {
                Sources sources = ProjectUtils.getSources(project);
                SourceGroup[] groups = sources.getSourceGroups("java");
                for (SourceGroup group : groups) {
                    File rootFile = FileUtil.toFile(group.getRootFolder());
                    if (rootFile != null) {
                        roots.add(rootFile);
                    }
                }
            }
        }
        catch (Throwable t) {
            LOG.log(Level.FINE, "collectSourceRoots error", t);
        }
        return roots;
    }

    private EditorContextProvider() {
    }
}

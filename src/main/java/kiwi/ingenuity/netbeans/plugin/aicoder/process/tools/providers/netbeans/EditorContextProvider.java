package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import org.netbeans.api.editor.EditorRegistry;
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
            return "File not found: " + filePath;
        }
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            int from = startLine > 0 ? Math.max(0, startLine - 1) : 0;
            int to = endLine > 0 ? Math.min(lines.size(), endLine) : lines.size();
            StringBuilder sb = new StringBuilder();
            sb.append("File: ").append(filePath).append(" (lines ")
                    .append(from + 1).append("–").append(to).append(" of ").append(lines.size()).append(")\n\n");
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

    /**
     * Resolves the Java class name at the current cursor position. Uses the
     * selected text if present; otherwise extracts the identifier word under
     * the caret and resolves it to a fully-qualified name via the file's import
     * declarations, then falls back to the file's own package. Returns null if
     * no editor is focused or no identifier is found.
     */
    public static String resolveClassAtCursor() {
        AtomicReference<String> ref = new AtomicReference<>(null);
        try {
            SwingUtilities.invokeAndWait(() -> {
                JTextComponent editor = EditorRegistry.lastFocusedComponent();
                if (editor == null) {
                    return;
                }
                try {
                    String text = editor.getDocument().getText(0, editor.getDocument().getLength());

                    // Prefer selection, otherwise extract word at caret
                    String selected = editor.getSelectedText();
                    String word;
                    if (selected != null && !selected.isBlank()) {
                        word = selected.trim();
                    }
                    else {
                        int caret = editor.getCaretPosition();
                        int start = caret;
                        while (start > 0 && isClassNameChar(text.charAt(start - 1))) {
                            start--;
                        }
                        int end = caret;
                        while (end < text.length() && isClassNameChar(text.charAt(end))) {
                            end++;
                        }
                        word = text.substring(start, end).replaceAll("^\\.+|\\.+$", "");
                    }
                    if (word.isBlank()) {
                        return;
                    }

                    // Already a FQN (starts with lowercase package segment)
                    if (word.contains(".") && Character.isLowerCase(word.charAt(0))) {
                        ref.set(word);
                        return;
                    }

                    String simpleName = word.contains(".")
                            ? word.substring(word.lastIndexOf('.') + 1) : word;

                    // Scan imports for matching simple name
                    for (String line : text.split("\r?\n")) {
                        String t = line.trim();
                        if (t.startsWith("import ") && t.endsWith(";") && !t.contains("static")) {
                            String imp = t.substring(7, t.length() - 1).trim();
                            if (imp.endsWith("." + simpleName)) {
                                ref.set(imp);
                                return;
                            }
                        }
                    }

                    // Fall back to the file's own package
                    for (String line : text.split("\n")) {
                        String t = line.trim();
                        if (t.startsWith("package ") && t.endsWith(";")) {
                            ref.set(t.substring(8, t.length() - 1).trim() + "." + simpleName);
                            return;
                        }
                    }

                    ref.set(simpleName);
                }
                catch (javax.swing.text.BadLocationException ignored) {
                }
            });
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "resolveClassAtCursor error", e);
        }
        return ref.get();
    }

    private static boolean isClassNameChar(char c) {
        return Character.isJavaIdentifierPart(c) || c == '.';
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

    private EditorContextProvider() {
    }
}

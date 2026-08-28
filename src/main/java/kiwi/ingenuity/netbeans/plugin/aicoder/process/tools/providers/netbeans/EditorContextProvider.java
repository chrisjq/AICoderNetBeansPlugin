package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.GetFileContentParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.DateUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.OperatingSystemEnum;
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
    private static final int MAX_FILTER_MATCHES = 200;
    private static final int MAX_FILTER_CONTEXT_LINES = 50;

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
     * Caret position in the focused editor as {@code line:column}, or null when there is no editor or the caret cannot
     * be read.
     *
     * <p>
     * Unlike {@link #getCurrentFile()} this is safe to call from the EDT: it reads directly when already on the
     * dispatch thread instead of calling {@code invokeAndWait}, which throws when invoked from the EDT itself. The
     * context preamble is built on the EDT, which is why the plain accessor cannot be reused there.
     *
     * <p>
     * This is informational only. Tools deliberately do not act on the caret — the caller cannot see it, so a tool that
     * used it would behave differently depending on where the user last clicked. Supplying the position lets the caller
     * decide whether the user's location is relevant and pass it explicitly.
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
        // This reads from disk, so unsaved editor changes would be invisible - and
        // the write tools work on disk too, so a caller would then edit text the
        // user cannot see. Flushing first makes the read match both what is on
        // screen and what a later ApplyEdit will match against.
        RefactoringProvider.FlushResult flush
                = RefactoringProvider.flushUnsavedEditorChanges(FileUtils.resolveByFile(f));
        if (flush.error() != null) {
            return flush.error();
        }
        try {
            List<String> lines = Files.readAllLines(f.toPath(), RefactoringProvider.resolveCharset(f));
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
                            .append(" characters. Use ").append(GetFileContentParamEnum.START_LINE.key()).append("/")
                            .append(GetFileContentParamEnum.END_LINE.key()).append(" to read a specific range.]");
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
     * Pattern-matches lines within a single file, without ever holding the whole file in memory — the motivating case
     * is grepping a 115k+ line build/test log, not a handful of source lines. Runs two sequential streaming passes
     * instead: the first counts every match and records which line numbers must be shown (matches plus their
     * {@code contextLines} neighbours) in a bounded {@link TreeSet}; the second re-reads the file and emits only those
     * lines. Two passes of cheap sequential I/O is the trade against holding either the full content or a sliding
     * window in memory to support look-ahead context — the file is read fully, twice, rather than partially, once, held
     * in memory.
     *
     * <p>
     * {@code maxMatches <= 0} means "use the default cap" ({@link #MAX_FILTER_MATCHES}), matching the
     * {@code startLine}/{@code endLine} 0-means-omitted convention used by {@link #getFileContent}.
     * {@code contextLines} is clamped to {@code [0, MAX_FILTER_CONTEXT_LINES]} defensively even though the tool layer
     * should already have done so, so a direct caller cannot request an unbounded context window.
     */
    public static String filterFileContent(String filePath, String pattern, boolean isRegex,
            boolean caseSensitive, int contextLines, int maxMatches) {
        File f = new File(filePath);
        if (!f.exists() || !f.isFile()) {
            return buildNotFoundMessage(filePath);
        }
        // Same reasoning as getFileContent: a pattern typed into the editor but not
        // yet saved would otherwise be invisible to a filter over the on-disk copy,
        // which is a false negative from the one operation this tool exists to do.
        RefactoringProvider.FlushResult flush
                = RefactoringProvider.flushUnsavedEditorChanges(FileUtils.resolveByFile(f));
        if (flush.error() != null) {
            return flush.error();
        }

        Pattern compiled;
        try {
            compiled = LineMatcher.compile(pattern, isRegex, caseSensitive);
        }
        catch (PatternSyntaxException e) {
            return "Invalid regex: " + e.getMessage();
        }

        int cap = maxMatches > 0 ? maxMatches : MAX_FILTER_MATCHES;
        int context = Math.max(0, Math.min(contextLines, MAX_FILTER_CONTEXT_LINES));
        Charset charset = RefactoringProvider.resolveCharset(filePath);

        TreeSet<Integer> linesToShow = new TreeSet<>();
        TreeSet<Integer> matchedLines = new TreeSet<>();
        int totalMatches = 0;
        try (BufferedReader reader = Files.newBufferedReader(f.toPath(), charset)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (LineMatcher.findWithTimeout(compiled, line)) {
                    // Counted for every match so the header can report the true total
                    // even past the cap — silent truncation is the failure mode to avoid.
                    totalMatches++;
                    if (totalMatches <= cap) {
                        matchedLines.add(lineNo);
                        for (int l = Math.max(1, lineNo - context); l <= lineNo + context; l++) {
                            linesToShow.add(l);
                        }
                    }
                }
            }
        }
        catch (LineMatcher.RegexTimeoutException e) {
            // A pathological pattern must not hang the handler thread; report it like an
            // invalid pattern — the query, not the file, is the problem.
            return "Regex timed out after " + e.timeoutMillis()
                    + " ms — the pattern backtracks catastrophically; simplify it.";
        }
        catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }

        if (totalMatches == 0) {
            return "No matches found for: " + pattern;
        }

        StringBuilder sb = new StringBuilder("Found ").append(totalMatches)
                .append(" match(es) in ").append(filePath);
        if (totalMatches > cap) {
            sb.append(" (showing first ").append(cap).append(")");
        }
        sb.append(":\n\n");
        try (BufferedReader reader = Files.newBufferedReader(f.toPath(), charset)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (!linesToShow.contains(lineNo)) {
                    continue;
                }
                // grep -C convention: ':' marks an actual match, '-' marks context so
                // the two are never confused when they appear interleaved.
                sb.append(lineNo).append(matchedLines.contains(lineNo) ? ": " : "- ").append(line).append("\n");
            }
        }
        catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
        return sb.toString().strip();
    }

    /**
     * Reports metadata for any path — regular file, directory or symbolic link — without returning its contents. A
     * regular file gets the exact byte size, line count, text encoding, created and last-modified times, writability,
     * link status and unsaved-editor-change flag. A directory gets immediate (non-recursive) entry counts split into
     * files and directories, each split hidden vs non-hidden, plus created and modified times. A symbolic link is
     * resolved to its target and the target's info reported with both paths shown; a broken link is stated as such.
     * Nothing here throws out: an unreadable field degrades on its own so the caller still gets the facts that were
     * readable. The encoding is resolved through NetBeans' own {@link FileEncodingQuery} so it matches how the editor
     * reads the file (per-project charset settings, detection); the size/line count are the on-disk copy — an "unsaved
     * editor changes" flag warns when the editor's in-memory copy has diverged from it.
     */
    public static String getFileInfo(String filePath) {
        StringBuilder sb = new StringBuilder();
        // Every filesystem call below is guarded, but turning the caller's string into a Path was not — and it is the
        // one statement that runs before any guard can. A null path threw NullPointerException and an embedded NUL byte
        // threw InvalidPathException, both escaping as "Internal error", which tells the caller the tool broke rather
        // than that its argument was unusable. Refused explicitly instead, and kept distinct from "File not found",
        // which is a well-formed path that simply is not there.
        if (filePath == null || filePath.isBlank()) {
            return McpToolPropertyEnum.FILE_PATH.key() + " is required — supply an absolute path to a file, directory "
                    + "or symbolic link.";
        }
        Path link;
        try {
            link = Path.of(filePath);
        }
        catch (InvalidPathException e) {
            return "Not a usable path: " + filePath + " — " + e.getReason();
        }

        boolean isLink;
        try {
            isLink = Files.isSymbolicLink(link);
        }
        catch (Throwable t) {
            isLink = false;
        }

        Path target = link;
        if (isLink) {
            sb.append(filePath).append(" (symbolic link) -> ");
            try {
                // toRealPath resolves symlinks fully and throws on a broken or cyclic
                // chain (e.g. "Too many levels of symbolic links") — the resolver is
                // the loop guard, so no recursion is needed here.
                target = link.toRealPath();
                sb.append(target);
            }
            catch (Throwable t) {
                return sb.append("cannot resolve target (").append(t.getMessage())
                        .append(") - broken or cyclic link").toString();
            }
        }
        else {
            try {
                if (!Files.exists(target)) {
                    return "File not found: " + filePath;
                }
            }
            catch (Throwable t) {
                return "Error reading " + filePath + ": " + t.getMessage();
            }
            sb.append(filePath);
        }

        boolean isDir;
        try {
            isDir = Files.isDirectory(target);
        }
        catch (Throwable t) {
            isDir = false;
        }
        if (isDir) {
            appendDirectoryInfo(sb, target);
        }
        else {
            boolean isFile;
            try {
                isFile = Files.isRegularFile(target);
            }
            catch (Throwable t) {
                isFile = false;
            }
            if (isFile) {
                appendFileInfo(sb, target, isLink);
            }
            else {
                sb.append(": neither a regular file nor a directory");
            }
        }
        return sb.toString();
    }

    private static void appendFileInfo(StringBuilder sb, Path path, boolean isLink) {
        File f = path.toFile();
        sb.append(": ").append(f.length()).append(" bytes");

        // Resolve the FileObject once — both the encoding query and the
        // unsaved-changes check need it. Best-effort: a null or throwing resolve
        // just omits the IDE-derived fields rather than failing the whole call.
        FileObject fo = null;
        try {
            fo = FileUtil.toFileObject(FileUtil.normalizeFile(f));
        }
        catch (Throwable t) {
            LOG.log(Level.FINE, "toFileObject failed for " + path, t);
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
                LOG.log(Level.FINE, "encoding query failed for " + path, t);
            }
        }

        // Streamed a line at a time rather than read whole. This tool exists to be called BEFORE GetFileContent on a
        // large file, so materialising that file here would defeat its purpose. BufferedReader is used in preference to
        // Files.lines because Files.lines defers decoding to the terminal operation and wraps a decode failure in
        // UncheckedIOException, which the catch below would not see — a binary file would then escape this method
        // instead of degrading to the note.
        try (BufferedReader reader = Files.newBufferedReader(path, RefactoringProvider.resolveCharset(fo))) {
            long lineCount = 0;
            while (reader.readLine() != null) {
                lineCount++;
            }
            sb.append(", ").append(lineCount).append(" lines");
        }
        catch (IOException e) {
            // Size and encoding are still useful even if the file is not
            // decodable as its resolved charset (e.g. a binary file, or the
            // charset guess above did not match the actual bytes).
            sb.append(", line count unavailable (").append(e.getMessage()).append(")");
        }
        sb.append(", encoding ").append(encoding);
        sb.append(", type ").append(mimeType(fo, path));

        appendTimes(sb, path);

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
                LOG.log(Level.FINE, "unsaved-changes check failed for " + path, t);
            }
        }
        sb.append(isLink ? ", symbolic link" : ", not a symbolic link");
    }

    /**
     * Appends created and last-modified times, rendered in the machine's local zone by DateUtil like every other date
     * an AI sees, plus age in seconds so a caller can judge staleness without a second clock call of its own. Each time
     * degrades on its own: a filesystem that offers no creation time gets a plain statement, not an invented value.
     */
    /**
     * The file's MIME type, preferring NetBeans' own resolution over the JDK's.
     * <p>
     * {@code FileObject.getMIMEType()} goes through the IDE's MIMEResolver chain, which is content- and extension-aware
     * and knows the editor types that matter here — {@code text/x-java}, {@code text/x-maven-pom+xml} and so on. It is
     * also the type the editor itself uses, so what this reports matches how the IDE actually treats the file rather
     * than being a second opinion about it.
     * <p>
     * {@code Files.probeContentType} is the fallback rather than the primary for a specific reason: on Linux it depends
     * on installed {@code FileTypeDetector}s and very often returns null for ordinary source files, so leading with it
     * would answer "unknown" for exactly the files a caller most wants identified. NetBeans returns the sentinel
     * {@code content/unknown} when it cannot decide, which is treated as no answer so the fallback still gets its turn.
     * <p>
     * Both calls are guarded: MIME resolution reaches into global Lookup and can fail with an Error outside a fully
     * started IDE, and a type is metadata rather than the point of the call — losing it must not fail the whole result.
     */
    /**
     * Whether a MIME type reported by NetBeans is an actual answer.
     * <p>
     * {@code FileObject.getMIMEType()} does not return null when it cannot decide — it returns the sentinel
     * {@code content/unknown}. Treating that as a real type is the trap: it reads like a result, so it would be
     * reported to the caller as the file's type AND would suppress the {@code probeContentType} fallback that might
     * genuinely have identified it. Both failures at once, and neither visible in the output.
     */
    static boolean isUsableMimeType(String type) {
        return type != null && !type.isBlank() && !"content/unknown".equals(type);
    }

    static String mimeType(FileObject fo, Path path) {
        if (fo != null) {
            try {
                String type = fo.getMIMEType();
                if (isUsableMimeType(type)) {
                    return type;
                }
            }
            catch (Throwable t) {
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.FINE, "MIME resolution failed for " + path, t);
                }
            }
        }
        try {
            String probed = Files.probeContentType(path);
            if (probed != null && !probed.isBlank()) {
                return probed;
            }
        }
        catch (Throwable t) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.FINE, "probeContentType failed for " + path, t);
            }
        }
        return "unknown";
    }

    /**
     * The created-time fragment, or empty when there is nothing trustworthy to say.
     * <p>
     * Answers ONE question — did the filesystem supply a usable value — and is pure so both of its branches can be
     * tested on any machine. Whether the PLATFORM records birth times at all is a separate decision, made by
     * {@link #appendTimes(StringBuilder, Path, boolean)}, which takes that capability as an argument so its branch is
     * reachable in a test off Windows and macOS too. Keeping the two questions in separate methods is what lets both be
     * pinned here; folding either into the other puts one of them out of reach again.
     *
     * @param createdMillis the reported creation time; {@code <= 0} means the filesystem did not supply one.
     * Deliberate: {@link BasicFileAttributes#creationTime()} on a platform/filesystem combination that cannot supply a
     * real value returns the epoch ({@code FileTime.fromMillis(0)}), not an absent/optional result — there is no
     * separate "unsupported" signal to check instead. Reading {@code <= 0} as absent therefore treats "no value
     * supplied" and "the epoch itself" the same way, which is correct in practice: no file on a real project's
     * filesystem was genuinely created at or before 1970-01-01T00:00:00Z, so this can never misclassify a real project
     * file's timestamp. This was raised and re-examined once already — do not "fix" it into accepting epoch-era values;
     * there is no way to tell a genuine epoch timestamp apart from the platform's absent-value sentinel, and treating
     * them differently would let unsupported platforms leak a misleading {@code 1970} date into results instead of
     * correctly reporting the field as absent.
     */
    static String createdSuffix(long createdMillis) {
        if (createdMillis <= 0) {
            return "";
        }
        return ", created " + DateUtil.format(Instant.ofEpochMilli(createdMillis));
    }

    private static void appendTimes(StringBuilder sb, Path path) {
        appendTimes(sb, path, OperatingSystemEnum.current().providesFileCreationTime());
    }

    /**
     * Package-visible overload taking the platform capability as an argument, so the WIRING — that this method actually
     * consults {@link #createdSuffix} and appends its result — is testable off Windows and macOS.
     * <p>
     * Extracting the pure {@code createdSuffix} closed the DECISION gap: its body is now pinned on every platform. It
     * did not close the wiring gap. With the capability read from a static inside this method, the emitting branch was
     * unreachable on Linux, so deleting the whole created-time block still passed here — the absence assertions stayed
     * true and the pure-function tests kept calling {@code createdSuffix} directly. A reviewer caught that distinction
     * precisely: the decision was pinned, the call was not. Passing the flag in makes both observable anywhere.
     */
    static void appendTimes(StringBuilder sb, Path path, boolean platformProvidesBirthTime) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            long modMillis = attrs.lastModifiedTime().toMillis();
            if (modMillis > 0) {
                Instant modified = Instant.ofEpochMilli(modMillis);
                long ageSeconds = Math.max(0, (System.currentTimeMillis() - modMillis) / 1000);
                sb.append(", modified ").append(DateUtil.format(modified)).append(" (").append(ageSeconds).append("s ago)");
            }
        }
        catch (Throwable t) {
            LOG.log(Level.FINE, "modified-time query failed for " + path, t);
        }
        // Created time is reported only where a real BIRTH time exists — Windows and macOS. Elsewhere the runtime
        // substitutes the inode CHANGE time, which is a real timestamp but not the creation date and cannot be told
        // apart from one through BasicFileAttributes; emitting that under a "created" label invites a caller to
        // compare it against the modified time and conclude something false.
        //
        // Unavailable means ABSENT, not a placeholder. An unsupported field is not news — it is the normal state on
        // that platform — and a per-call notice would appear on every result forever, adding noise an AI has to read
        // and discard each time. A caller that needs to know whether created time exists here can consult the tool
        // description once, rather than being told on every line.
        if (!platformProvidesBirthTime) {
            return;
        }
        try {
            FileTime created = Files.readAttributes(path, BasicFileAttributes.class).creationTime();
            sb.append(createdSuffix(created.toMillis()));
        }
        catch (Throwable t) {
            // No created time means the field is ABSENT — one rule, whatever the cause. A read that fails is not
            // reported differently from a platform that has none: the caller cares whether the value is there, not why
            // it is not, and a second wording would put a placeholder back into results that are supposed to carry
            // none. The reason is available in the log when debugging is on.
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.FINE, "creation-time query failed for " + path, t);
            }
        }
    }

    /**
     * Appends a directory's immediate — not recursive — entry counts split into files and directories, each split
     * hidden vs non-hidden via {@link FindFileProvider#isHiddenPath}, plus created/modified times. An unreadable
     * directory reports the times it could read and a note instead of an exception.
     */
    private static void appendDirectoryInfo(StringBuilder sb, Path dir) {
        sb.append(": directory");
        appendTimes(sb, dir);
        sb.append("; immediate entries only (not recursive): ");

        int files = 0, directories = 0, hiddenFiles = 0, hiddenDirectories = 0;
        // Iterated lazily rather than collected. Only one child is needed at a time — the loop keeps nothing but four
        // counters — so draining the stream into a List first would hold every entry of a wide directory in memory for
        // no gain. Stream.iterator() keeps the counters named and the loop readable, which forEach would not.
        try (Stream<Path> entries = Files.list(dir)) {
            for (Iterator<Path> it = entries.iterator(); it.hasNext();) {
                Path child = it.next();
                boolean childDir;
                try {
                    childDir = Files.isDirectory(child);
                }
                catch (Throwable t) {
                    childDir = false;
                }
                boolean hidden;
                try {
                    hidden = FindFileProvider.isHiddenPath(child);
                }
                catch (Throwable t) {
                    hidden = false;
                }
                if (childDir) {
                    directories++;
                    if (hidden) {
                        hiddenDirectories++;
                    }
                }
                else {
                    files++;
                    if (hidden) {
                        hiddenFiles++;
                    }
                }
            }
            sb.append(files).append(files == 1 ? " file" : " files")
                    .append(" (").append(hiddenFiles).append(" hidden, ").append(files - hiddenFiles).append(" visible), ")
                    .append(directories).append(directories == 1 ? " directory" : " directories")
                    .append(" (").append(hiddenDirectories).append(" hidden, ").append(directories - hiddenDirectories).append(" visible)");
        }
        catch (Throwable t) {
            sb.append("could not list all entries (").append(t.getMessage()).append(")");
        }
    }

    public static String navigateToLine(String filePath, Integer lineNumber, boolean focus) {
        return lineNumber == null ? openFile(filePath, focus)
                : navigateToLine(filePath, lineNumber.intValue(), focus);
    }

    /**
     * Opens a file in the editor and optionally scrolls to a line. Pass focus=true when showing the user something;
     * false for internal tool use.
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
        final int effectiveLine = lineNumber;
        org.openide.text.Line.ShowVisibilityType visibility = focus
                ? org.openide.text.Line.ShowVisibilityType.FOCUS
                : org.openide.text.Line.ShowVisibilityType.NONE;
        AtomicReference<String> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    org.openide.loaders.DataObject dob = org.openide.loaders.DataObject.find(fo);
                    TopComponent previouslyActive = focus ? null : TopComponent.getRegistry().getActivated();
                    org.openide.cookies.LineCookie lc = dob.getLookup().lookup(org.openide.cookies.LineCookie.class);
                    if (lc != null) {
                        org.openide.text.Line line = lc.getLineSet().getCurrent(effectiveLine - 1);
                        line.show(org.openide.text.Line.ShowOpenType.OPEN, visibility);
                        if (!focus && previouslyActive != null) {
                            previouslyActive.requestActive();
                        }
                        result.set("Navigated to " + filePath + ":" + effectiveLine);
                        return;
                    }
                    org.openide.cookies.OpenCookie oc = dob.getLookup().lookup(org.openide.cookies.OpenCookie.class);
                    if (oc != null) {
                        oc.open();
                        if (!focus && previouslyActive != null) {
                            previouslyActive.requestActive();
                        }
                        result.set("Navigated to " + filePath + ":" + effectiveLine);
                    }
                    else {
                        result.set("Cannot open file in NetBeans: " + filePath);
                    }
                }
                catch (org.openide.loaders.DataObjectNotFoundException ex) {
                    LOG.log(Level.FINE, "navigateToLine: file not found in DataObject system", ex);
                    result.set("Cannot open file in NetBeans: " + filePath);
                }
            });
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "navigateToLine error", e);
            return "Error navigating: " + e.getMessage();
        }
        return result.get();
    }

    public static String openFile(String filePath, boolean focus) {
        File f = new File(filePath);
        if (!f.exists()) {
            return "File not found: " + filePath;
        }
        FileObject fo = FileUtils.resolveByFile(f);
        if (fo == null) {
            return "Cannot resolve file: " + filePath;
        }
        AtomicReference<String> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    DataObject dob = DataObject.find(fo);
                    TopComponent previouslyActive = focus ? null : TopComponent.getRegistry().getActivated();
                    org.openide.cookies.EditorCookie ec = dob.getLookup().lookup(org.openide.cookies.EditorCookie.class);
                    boolean targetWasActive = false;
                    if (!focus && ec != null && previouslyActive != null) {
                        javax.swing.JEditorPane[] panes = ec.getOpenedPanes();
                        if (panes != null && panes.length > 0) {
                            targetWasActive = previouslyActive == SwingUtilities.getAncestorOfClass(TopComponent.class, panes[0]);
                        }
                    }
                    if (ec != null) {
                        ec.open();
                        if (focus) {
                            javax.swing.JEditorPane[] panes = ec.getOpenedPanes();
                            if (panes != null && panes.length > 0) {
                                TopComponent editor = (TopComponent) SwingUtilities.getAncestorOfClass(TopComponent.class, panes[0]);
                                if (editor != null) {
                                    editor.requestActive();
                                }
                                panes[0].requestFocusInWindow();
                            }
                        }
                        else if (previouslyActive != null && !targetWasActive) {
                            previouslyActive.requestActive();
                        }
                        result.set("Opened " + filePath);
                        return;
                    }
                    org.openide.cookies.OpenCookie oc = dob.getLookup().lookup(org.openide.cookies.OpenCookie.class);
                    if (oc != null) {
                        oc.open();
                        if (!focus && previouslyActive != null) {
                            previouslyActive.requestActive();
                        }
                        result.set("Opened " + filePath);
                    }
                    else {
                        result.set("Cannot open file in NetBeans: " + filePath);
                    }
                }
                catch (org.openide.loaders.DataObjectNotFoundException ex) {
                    LOG.log(Level.FINE, "openFile: file not found in DataObject system", ex);
                    result.set("Cannot open file in NetBeans: " + filePath);
                }
            });
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "openFileWithoutMovingCaret error", e);
            return "Error opening file: " + e.getMessage();
        }
        return result.get();
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
            sb.append("\n\nNo file with that name in the open projects. Use ").append(McpToolEnum.GET_PROJECT_STRUCTURE.toolName()).append(" for the package layout, or ").append(McpToolEnum.SEARCH_SYMBOLS.toolName()).append("/").append(McpToolEnum.SEARCH_IN_FILES.toolName()).append(" to locate it.");
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

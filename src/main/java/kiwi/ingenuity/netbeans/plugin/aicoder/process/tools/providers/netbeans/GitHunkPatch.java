package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-text parsing and building of git unified diffs, for partial staging.
 *
 * <p>
 * {@link #parse(String)} turns the text {@code GetGitDiff} returns (git's unified diff) into a list of
 * {@link FileDiff}s, and {@link #buildPatch(FileDiff, List)} renders a new unified-diff text containing a file's header
 * plus only the selected hunks (and, within a hunk, only the selected new-file line range). The result is meant to be
 * applied to the index with {@code git apply --cached}; it contains no git calls, processes or filesystem access — it
 * operates on diff text alone.
 *
 * <p>
 * Path extraction reuses the same quoting rules as {@code GitProvider#readGitDiffPath} (an unquoted path ends at the
 * next space; a {@code "-"}-quoted path is byte-oriented with {@code \ooo} octal and {@code \n}/{@code \t} escapes). A
 * hunk's line range, when given, is expressed in <em>new-file</em> line numbers: a {@code '+'} line is inside the range
 * when its own new-file line number is within it; a {@code '-'} line is inside when the new-file line number the
 * <em>next</em> new line would get is within it (a deleted line has no new-file number of its own). Context lines are
 * always retained — they anchor the hunk so {@code git apply} can locate it.
 */
public final class GitHunkPatch {

    private static final Pattern HUNK_HEADER
            = Pattern.compile("@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@\\s*(.*)");

    private GitHunkPatch() {
    }

    /**
     * One file in a unified diff: its (decoded, repo-relative) path, the verbatim header lines (the {@code diff --git}
     * line through {@code +++} / mode lines, up to but not including the first {@code @@} hunk) and its hunks in file
     * order.
     */
    public record FileDiff(String path, List<String> headerLines, List<Hunk> hunks) {

        /**
         * A single {@code @@} hunk. {@code oldCount}/{@code newCount} are the parsed counts (a count omitted in the
         * header means 1). {@code bodyLines} are the hunk's content lines, each beginning with a space (context),
         * {@code +} (added), {@code -} (removed) or {@code \} (a {@code \ No newline at end of file} marker).
         * {@code headerSuffix} is the optional text after the closing {@code @@} (e.g. a function name).
         */
        public record Hunk(int oldStart, int oldCount, int newStart, int newCount,
                           String headerSuffix, List<String> bodyLines) {

        }
    }

    /**
     * A selection of part of a file. {@code hunk} is the 1-based hunk within the file, or {@code null} to apply the
     * line range to <em>every</em> hunk of the file; {@code fromLine} and {@code toLine} are optional, inclusive, and
     * in <em>new-file</em> line numbers. A selection with a hunk but no range selects that whole hunk; a selection with
     * a range (numbered or not) keeps only the lines within the range; a selection with no hunk and no range is the
     * whole file and is rejected by {@link #buildPatch} (stage it with a normal add instead). See {@link #buildPatch}.
     */
    public record Selection(Integer hunk, Integer fromLine, Integer toLine) {

    }

    /**
     * A hunk as it will be emitted by {@link #buildPatch}: its rendered lines (the {@code @@} header line followed by
     * the body) and its recomputed old/new line counts.
     */
    private record Emitted(List<String> lines, int oldCount, int newCount) {

    }

    /**
     * Splits a unified diff into its files on {@code diff --git } lines.
     *
     * @param unifiedDiff the diff text (e.g. what {@code GetGitDiff} returns); may be null or contain no files
     *
     * @return the files in order; empty when the input is null or has no {@code diff --git } lines
     */
    public static List<FileDiff> parse(String unifiedDiff) {
        if (unifiedDiff == null) {
            return List.of();
        }
        // split("\n") (not -1) so a trailing newline, as git produces, does not yield a phantom empty line.
        String[] lines = unifiedDiff.split("\n");
        List<FileDiff> files = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            if (!lines[i].startsWith("diff --git ")) {
                i++;
                continue;
            }
            String path = extractBPath(lines[i]);
            List<String> headerLines = new ArrayList<>();
            headerLines.add(lines[i]); // the "diff --git ..." line is the first header line
            List<FileDiff.Hunk> hunks = new ArrayList<>();
            i++;
            // Header lines run from the diff --git line up to the first hunk (or the next file / EOF).
            while (i < lines.length && !lines[i].startsWith("@@") && !lines[i].startsWith("diff --git ")) {
                headerLines.add(lines[i]);
                i++;
            }
            // A hunk's body never starts with '@@' or 'diff --git', so these delimit the hunks of this file.
            while (i < lines.length && lines[i].startsWith("@@") && !lines[i].startsWith("diff --git ")) {
                ParsedHunk parsed = parseHunk(lines, i);
                hunks.add(parsed.hunk());
                i = parsed.next();
            }
            files.add(new FileDiff(path, List.copyOf(headerLines), List.copyOf(hunks)));
        }
        return files;
    }

    /**
     * Renders a new unified-diff text for one file containing its header lines and the selected hunks (and, within a
     * hunk, the selected new-file line ranges).
     *
     * <p>
     * A selection with a hunk number and no range selects that whole hunk. A selection with a range keeps only the
     * lines within it: {@code '+'} lines outside the range are dropped, {@code '-'} lines outside the range become
     * context, and a {@code \} no-newline marker is kept only if the line it follows was kept. A selection whose hunk
     * is {@code null} and that has a range applies that range to <em>every</em> hunk of the file. A selection with
     * neither a hunk nor a range means the whole file and is rejected (stage it with a normal add instead).
     *
     * <p>
     * When several selections apply to the same hunk (two numbered ranges on one hunk, or a file-wide range overlapping
     * a numbered hunk) they are combined by union: a change line is kept if <em>any</em> applicable selection covers it
     * (a whole-hunk selection covers everything in its hunk). The output hunk's counts are recomputed (old = context +
     * '-', new = context + '+'); its oldStart is kept and its newStart is {@code oldStart +} the net lines added by the
     * earlier selected hunks in this patch. A hunk whose result contains no {@code '+'}/{@code '-'} lines is omitted.
     *
     * @param file the file to render
     * @param selections which hunks (and, optionally, line ranges) to include; at least one is required
     *
     * @return the rendered diff text (header lines and selected hunks joined by newlines)
     *
     * @throws IllegalArgumentException if no selection is given; a hunk number is out of range; a range has fromLine
     * &gt; toLine; a whole-file selection (no hunk, no range) is given; or a file-wide range covers no changed line
     */
    public static String buildPatch(FileDiff file, List<Selection> selections) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        if (selections == null || selections.isEmpty()) {
            throw new IllegalArgumentException("no hunk selected");
        }
        int hunkCount = file.hunks().size();
        for (Selection sel : selections) {
            if (sel == null) {
                throw new IllegalArgumentException("selection must not be null");
            }
            if (sel.hunk() != null && (sel.hunk() < 1 || sel.hunk() > hunkCount)) {
                throw new IllegalArgumentException("file " + file.path() + " has " + hunkCount
                        + " hunks; cannot select hunk " + sel.hunk());
            }
            if (sel.hunk() == null && sel.fromLine() == null && sel.toLine() == null) {
                throw new IllegalArgumentException("whole-file selection: stage with GitAdd files instead");
            }
            if (sel.fromLine() != null && sel.toLine() != null && sel.fromLine() > sel.toLine()) {
                throw new IllegalArgumentException("fromLine " + sel.fromLine() + " > toLine "
                        + sel.toLine() + " in hunk " + sel.hunk());
            }
        }

        List<Selection> wholeFile = new ArrayList<>();
        Map<Integer, List<Selection>> byHunk = new HashMap<>();
        for (Selection sel : selections) {
            if (sel.hunk() == null) {
                wholeFile.add(sel);
            }
            else {
                byHunk.computeIfAbsent(sel.hunk(), k -> new ArrayList<>()).add(sel);
            }
        }

        // A file-wide range that touches no changed line anywhere in the file is a caller mistake (a whole-file
        // selection would throw earlier); there is nothing to stage.
        boolean wholeFileCovered = false;
        if (!wholeFile.isEmpty()) {
            for (FileDiff.Hunk h : file.hunks()) {
                if (!coveredRangeIsEmpty(h, wholeFile)) {
                    wholeFileCovered = true;
                    break;
                }
            }
            if (!wholeFileCovered) {
                throw new IllegalArgumentException("no changes in the selected range");
            }
        }

        List<String> out = new ArrayList<>(file.headerLines());
        int shift = 0;
        for (int i = 0; i < hunkCount; i++) {
            List<Selection> hunkSelections = new ArrayList<>(byHunk.getOrDefault(i + 1, List.of()));
            hunkSelections.addAll(wholeFile); // a file-wide range applies to every hunk
            if (hunkSelections.isEmpty()) {
                continue; // hunk not selected
            }
            Emitted emitted = emitHunk(file.hunks().get(i), hunkSelections, shift);
            if (emitted == null) {
                continue; // selected but no +/- lines remain — omitted, adds nothing
            }
            out.addAll(emitted.lines());
            shift += emitted.newCount() - emitted.oldCount();
        }
        // End with a newline: a patch whose last line is missing one is treated as corrupt by git apply.
        return String.join("\n", out) + "\n";
    }

    /**
     * Whether applying every selection in {@code sels} to {@code h} would keep no changed line at all — i.e. every
     * change line of the hunk falls outside every range. (Whole-hunk selections and a non-range cover everything, so
     * only a pure range can yield no changes.)
     */
    private static boolean coveredRangeIsEmpty(FileDiff.Hunk hunk, List<Selection> sels) {
        boolean hasAnyChangeLine = false;
        boolean anyKept = false;
        List<String> body = hunk.bodyLines();
        int n = body.size();
        int counter = hunk.newStart();
        int idx = 0;
        while (idx < n) {
            char tag = body.get(idx).isEmpty() ? ' ' : body.get(idx).charAt(0);
            if (tag == ' ') {
                counter++;
                idx++;
                continue;
            }
            if (tag != '-' && tag != '+') {
                idx++; // a no-newline marker consumes no number
                continue;
            }
            // A change block; test each line at the position emitHunk uses (blockStart + j) with the same keep
            // rule (keptBySelections), so this agrees exactly with what emitHunk would keep.
            int blockEnd = idx;
            while (blockEnd < n) {
                char t = body.get(blockEnd).isEmpty() ? ' ' : body.get(blockEnd).charAt(0);
                if (t == '-' || t == '+') {
                    blockEnd++;
                }
                else if (t == '\\') {
                    blockEnd++;
                }
                else {
                    break; // a context line ends the block
                }
            }
            int blockStart = counter;
            int j = 0;
            int plusCount = 0;
            for (int k = idx; k < blockEnd; k++) {
                char t = body.get(k).isEmpty() ? ' ' : body.get(k).charAt(0);
                if (t == '-' || t == '+') {
                    hasAnyChangeLine = true;
                    if (keptBySelections(blockStart + j, sels)) {
                        anyKept = true;
                    }
                    j++;
                    if (t == '+') {
                        plusCount++;
                    }
                }
                // a no-newline marker consumes no position
            }
            counter = blockStart + plusCount; // only added lines consume new numbers
            idx = blockEnd;
        }
        return hasAnyChangeLine && !anyKept;
    }

    private static ParsedHunk parseHunk(String[] lines, int idx) {
        String hunkLine = lines[idx];
        Matcher m = HUNK_HEADER.matcher(hunkLine);
        if (!m.matches()) {
            throw new IllegalArgumentException("Malformed hunk header: " + hunkLine);
        }
        int oldStart = Integer.parseInt(m.group(1));
        int oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        int newStart = Integer.parseInt(m.group(3));
        int newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
        String suffix = m.group(5);
        idx++;
        List<String> body = new ArrayList<>();
        while (idx < lines.length && !lines[idx].startsWith("@@") && !lines[idx].startsWith("diff --git ")) {
            body.add(lines[idx]);
            idx++;
        }
        return new ParsedHunk(new FileDiff.Hunk(oldStart, oldCount, newStart, newCount, suffix, List.copyOf(body)), idx);
    }

    private record ParsedHunk(FileDiff.Hunk hunk, int next) {

    }

    private static Emitted emitHunk(FileDiff.Hunk hunk, List<Selection> sels, int shift) {
        List<String> bodyLines = hunk.bodyLines();
        int n = bodyLines.size();

        List<String> body = new ArrayList<>();
        int oldLines = 0;
        int newLines = 0;
        boolean anyChange = false;
        boolean prevEmitted = false;

        int idx = 0;
        int counter = hunk.newStart(); // running new-file line number, for context/'+' lines
        while (idx < n) {
            String line = bodyLines.get(idx);
            char tag = line.isEmpty() ? ' ' : line.charAt(0);
            if (tag != '-' && tag != '+') {
                // A context line is always kept (it anchors the hunk and is in both files); a '\' marker has no
                // number of its own. A context line consumes one new number; a '\' consumes none.
                if (tag == ' ') {
                    body.add(line);
                    oldLines++;
                    newLines++;
                    counter++;
                    prevEmitted = true;
                }
                else { // '\\' no-newline marker — kept only if the line it follows was emitted.
                    if (prevEmitted) {
                        body.add(line);
                    }
                    // prevEmitted is left unchanged: a kept marker keeps it true, a dropped one false.
                }
                idx++;
                continue;
            }
            // A change block: a run of consecutive '-'/'+' lines between context lines. Collect the block's
            // deletion lines M[0..m) and addition lines P[0..p) first, then EMIT them interleaved by position
            // j = 0..max(m,p)-1 (position j has new-file number blockStart + j): at each j, the j-th deletion
            // M[j] goes first, then the j-th addition P[j]. A replaced line therefore pairs with its
            // replacement (both tested at the same position), and the emitted order is old-file lines before
            // new-file lines — a requirement of unified-diff.
            int blockEnd = idx;
            while (blockEnd < n) {
                char t = bodyLines.get(blockEnd).isEmpty() ? ' ' : bodyLines.get(blockEnd).charAt(0);
                if (t == '-' || t == '+') {
                    blockEnd++;
                }
                else if (t == '\\') {
                    blockEnd++; // a no-newline marker inside the block consumes no numbers
                }
                else {
                    break; // a context line ends the block
                }
            }
            int blockStart = counter; // the new-file number of the block's first line
            List<Integer> minusIdx = new ArrayList<>();
            List<Integer> plusIdx = new ArrayList<>();
            for (int k = idx; k < blockEnd; k++) {
                char t = bodyLines.get(k).isEmpty() ? ' ' : bodyLines.get(k).charAt(0);
                if (t == '-') {
                    minusIdx.add(k);
                }
                else if (t == '+') {
                    plusIdx.add(k);
                }
            }
            // A no-newline marker attaches to the line immediately before it; record, for each -/+ line directly
            // followed by a marker, that marker, so it is emitted right after the line it followed.
            Map<Integer, String> markerAfter = new HashMap<>();
            for (int k = idx + 1; k < blockEnd; k++) {
                String l = bodyLines.get(k);
                if (!l.isEmpty() && l.charAt(0) == '\\') {
                    char pt = bodyLines.get(k - 1).isEmpty() ? ' ' : bodyLines.get(k - 1).charAt(0);
                    if (pt == '-' || pt == '+') {
                        markerAfter.put(k - 1, l);
                    }
                }
            }
            int m = minusIdx.size();
            int p = plusIdx.size();
            for (int j = 0; j < Math.max(m, p); j++) {
                int pos = blockStart + j;
                if (j < m) {
                    int mi = minusIdx.get(j);
                    String minus = bodyLines.get(mi);
                    if (keptBySelections(pos, sels)) {
                        body.add(minus); // in range: keep the deletion
                        oldLines++;
                        anyChange = true;
                    }
                    else { // out of range: retain the old line as a context line
                        body.add(" " + (minus.length() > 1 ? minus.substring(1) : ""));
                        oldLines++;
                        newLines++;
                    }
                    prevEmitted = true; // a '-' line is always emitted (as '-' or as context)
                    String marker = markerAfter.get(mi);
                    if (marker != null) {
                        body.add(marker); // the '-' line it follows was emitted, so keep it
                    }
                }
                if (j < p) {
                    int pi = plusIdx.get(j);
                    String plus = bodyLines.get(pi);
                    if (keptBySelections(pos, sels)) {
                        body.add(plus); // in range: keep the addition
                        newLines++;
                        anyChange = true;
                        prevEmitted = true;
                        String marker = markerAfter.get(pi);
                        if (marker != null) {
                            body.add(marker); // the '+' line it follows was emitted, so keep it
                        }
                    }
                    else {
                        prevEmitted = false; // out of range: drop it (and any marker that followed it)
                    }
                }
            }
            // The block's '-'/'+' lines (and their attached markers) have all been emitted, so skip straight
            // past the block — never reprocessing its lines (that was the double-emission bug).
            idx = blockEnd;
            counter = blockStart + p; // only added lines consume new-file numbers
        }

        if (!anyChange) {
            return null;
        }
        int oldStart = hunk.oldStart();
        int newStart = oldStart + shift;
        String suffix = hunk.headerSuffix() == null || hunk.headerSuffix().isEmpty() ? "" : " " + hunk.headerSuffix();
        String header = "@@ -" + oldStart + "," + oldLines + " +" + newStart + "," + newLines + " @@" + suffix;
        List<String> lines = new ArrayList<>();
        lines.add(header);
        lines.addAll(body);
        return new Emitted(lines, oldLines, newLines);
    }

    private static boolean inRange(int lineNo, Integer fromLine, Integer toLine, boolean hasRange) {
        if (!hasRange) {
            return true;
        }
        if (fromLine != null && lineNo < fromLine) {
            return false;
        }
        if (toLine != null && lineNo > toLine) {
            return false;
        }
        return true;
    }

    /**
     * Whether a change line at new-file number {@code lineNo} is kept by <em>any</em> of the selections that apply to
     * this hunk: a selection without a range covers the whole hunk; a selection with a range covers its lines.
     */
    private static boolean keptBySelections(int lineNo, List<Selection> sels) {
        for (Selection sel : sels) {
            if (inRange(lineNo, sel.fromLine(), sel.toLine(), sel.fromLine() != null || sel.toLine() != null)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the new (b/) path from a {@code diff --git a/x b/y} line, decoded and reduced to its repo-relative form
     * (the {@code b/} prefix stripped) — mirroring {@code GitProvider#readGitDiffPath} and {@code #stripDiffPrefix}.
     */
    private static String extractBPath(String line) {
        int index = "diff --git ".length();
        PathRef old = readPath(line, index);
        if (old == null) {
            return "";
        }
        int idx = old.nextIndex();
        while (idx < line.length() && line.charAt(idx) == ' ') {
            idx++;
        }
        PathRef newPath = readPath(line, idx);
        return stripDiffPrefix(newPath == null ? "" : newPath.value());
    }

    private static String stripDiffPrefix(String path) {
        return path.length() >= 2 && path.charAt(1) == '/' ? path.substring(2) : path;
    }

    /**
     * Reads one path at {@code index}: unquoted (up to the next space) or {@code "-"}-quoted (byte-oriented,
     * {@code \ooo} octal and {@code \n}/{@code \t} escapes) — a faithful copy of {@code GitProvider#readGitDiffPath}.
     */
    private static PathRef readPath(String header, int index) {
        while (index < header.length() && header.charAt(index) == ' ') {
            index++;
        }
        if (index >= header.length()) {
            return null;
        }
        if (header.charAt(index) != '"') {
            int end = header.indexOf(' ', index);
            return new PathRef(header.substring(index, end >= 0 ? end : header.length()),
                               end >= 0 ? end + 1 : header.length());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int cursor = index + 1; cursor < header.length(); cursor++) {
            char ch = header.charAt(cursor);
            if (ch == '"') {
                return new PathRef(bytes.toString(StandardCharsets.UTF_8), cursor + 1);
            }
            if (ch != '\\' || cursor + 1 >= header.length()) {
                bytes.writeBytes(String.valueOf(ch).getBytes(StandardCharsets.UTF_8));
                continue;
            }
            char escaped = header.charAt(++cursor);
            if (escaped >= '0' && escaped <= '7' && cursor + 2 < header.length()
                    && header.charAt(cursor + 1) >= '0' && header.charAt(cursor + 1) <= '7'
                    && header.charAt(cursor + 2) >= '0' && header.charAt(cursor + 2) <= '7') {
                bytes.write((escaped - '0') * 64 + (header.charAt(++cursor) - '0') * 8
                        + (header.charAt(++cursor) - '0'));
            }
            else {
                bytes.write(switch (escaped) {
                    case 'n' ->
                        '\n';
                    case 't' ->
                        '\t';
                    default ->
                        escaped;
                });
            }
        }
        return null; // unterminated quote
    }

    private record PathRef(String value, int nextIndex) {

    }
}

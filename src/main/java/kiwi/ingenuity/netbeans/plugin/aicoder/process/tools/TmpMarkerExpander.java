package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;

/**
 * Expands the short {@code @tmp.<filename>} marker AiInputField inserts for a pasted temp file into the absolute path
 * the agent actually needs. Callers keep the ORIGINAL text for display and history, and send only {@link
 * #expand}'s result to the agent — the short marker is what the user and the conversation transcript ever see.
 * <p>
 * {@code <filename>} names a file directly inside the SUBMITTING session's own temp directory
 * ({@code ~/.ai-coder/{type}/{sessionId}/tmp/}), resolved against that session and no other. Treated as hostile input
 * throughout: a marker that fails any check below is left exactly as written in the output — never expanded, never
 * thrown for.
 */
public final class TmpMarkerExpander {

    // No path separator can appear in the capture group at all: the character
    // class excludes '/' and '\', so traversal via a separator is structurally
    // impossible before the explicit ".." check or the real-path containment
    // check below ever run.
    private static final Pattern MARKER_PATTERN = Pattern.compile("@tmp\\.([A-Za-z0-9._-]+)");

    /**
     * @param text the raw prompt text, unmodified for display/history by the caller
     * @param session the SUBMITTING session — markers resolve only against its own temp directory, never another
     * session's
     */
    public static Result expand(String text, AiSession session) {
        if (text == null || text.isEmpty() || !text.contains("@tmp.") || session == null) {
            return new Result(text, List.of());
        }
        Path realTmpDir = resolveRealSessionTmpDir(session);
        List<String> missing = new ArrayList<>();
        Matcher m = MARKER_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement = resolve(realTmpDir, m.group(1), missing);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement != null ? replacement : m.group()));
        }
        m.appendTail(out);
        return new Result(out.toString(), missing);
    }

    /**
     * Decides one marker's fate. Returns the absolute-path replacement text, or null to leave the marker exactly as
     * written — adding {@code name} to {@code missing} only when the marker was well-formed and named a file that
     * genuinely is not there right now (worth telling the user about), not when it was rejected as malformed or as
     * escaping containment (silent — that is hostile or accidental input, not a missing file).
     */
    private static String resolve(Path realTmpDir, String name, List<String> missing) {
        if (name.contains("..")) {
            return null;
        }
        if (realTmpDir == null) {
            missing.add(name);
            return null;
        }
        Path candidate = realTmpDir.resolve(name);
        if (!Files.exists(candidate)) {
            missing.add(name);
            return null;
        }
        try {
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realTmpDir)) {
                // Belt and braces: the regex and the ".." check already make traversal
                // impossible in practice, but a symlinked candidate could still resolve
                // outside the tmp dir. Stay silent rather than expand — this is not a
                // "file went missing" case, it is a "this should not happen" case.
                return null;
            }
            return "@" + realCandidate;
        }
        catch (IOException e) {
            return null;
        }
    }

    private static Path resolveRealSessionTmpDir(AiSession session) {
        if (session.aiType() == null) {
            return null;
        }
        Path tmpDir = TempFileRegistry.getSessionTempDir(session.id());
        if (tmpDir == null) {
            return null;
        }
        try {
            return tmpDir.toRealPath();
        }
        catch (IOException e) {
            return null;
        }
    }

    private TmpMarkerExpander() {
    }

    /**
     * Result of an expansion attempt: the text with every resolvable marker replaced by an absolute path, and the
     * filenames named by markers that looked like a real temp-file reference but could not be resolved (the session has
     * no temp directory, or the named file is not in it — already swept, deleted on session close, or never existed).
     * Callers surface these to the user rather than silently sending a dead marker to the agent.
     */
    public static final class Result {

        private final String expandedText;
        private final List<String> missingFiles;

        Result(String expandedText, List<String> missingFiles) {
            this.expandedText = expandedText;
            this.missingFiles = missingFiles;
        }

        public String expandedText() {
            return expandedText;
        }

        public List<String> missingFiles() {
            return missingFiles;
        }
    }
}

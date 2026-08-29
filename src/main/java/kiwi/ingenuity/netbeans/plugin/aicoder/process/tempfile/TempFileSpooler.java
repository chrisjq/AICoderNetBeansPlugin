package kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;

/**
 * Spools oversized tool output to disk so a tool can return a short, useful result without anything becoming
 * unavailable.
 * <p>
 * This is the general, reusable spooler: the directory is chosen from {@link TempFileDirEnum} and the file extension is
 * a caller-supplied parameter, so any tool can park its output under a recognisable subdirectory with a suitable
 * extension. Build/test tools and the git read tools use {@link TempFileDirEnum#TOOL_RESULTS} with a {@code .log}
 * extension; tools with other needs can add their own enum constant.
 * <p>
 * Files land in the calling session's registry-owned temp tree under the requested subdirectory:
 * {@code ~/.ai-coder/{type}/{sessionId}/tmp/{dirName}/name-{sessionId}-{random}{ext}}. The {@code tmp} parent is minted
 * and owned by {@link TempFileRegistry} — which places it under the session config directory, where the per-session
 * scope exemption ({@link McpHookServer#isOwnSessionConfigFile}) makes the files readable by the AI via
 * GetFileContent/FilterFileContent even under restrict-to-project, no Bash round-trip and no widened scope rule.
 * <p>
 * Content lands in a file named by the caller's tool label. Uniqueness is the registry's atomic createTempFile
 * guarantee, so a burst of same-instant results can never clobber each other.
 * <p>
 * Lifetime is the registry's, not this class's: an age sweep prunes old files from long-lived sessions, and session
 * close / IDE shutdown / plugin uninstall remove the whole temp directory wholesale.
 * <p>
 * Spooling is best-effort and never throws: any failure falls back to returning the full output inline. Two public
 * entry points cover the two call patterns: {@link #spoolIfLarge} for tools that keep short output inline and only
 * spool once it grows past a threshold, and {@link #spool} for callers that always want a durable copy on disk.
 */
public final class TempFileSpooler {

    private static final Logger LOG = Logger.getLogger(TempFileSpooler.class.getName());

    /**
     * Default inline-vs-spool cut-off for tools that keep short results inline: below this a tool returns the full
     * output inline; at or above it {@link #spoolIfLarge} parks the complete output and returns a truncated head plus
     * the path.
     *
     * <p>
     * A judgement call, not a derived limit — chosen so that ordinary results (a normal diff, a short log, a handful of
     * blame lines) stay inline and only genuinely large ones spool. There is no transport cap it has to match, and
     * nothing breaks at any particular value; too low only means spooling things that would have been fine inline, too
     * high means a large payload reaching the AI whole.</p>
     *
     * <p>
     * An earlier version of this javadoc claimed the number mirrored "the transport cap the plugin's own consumers
     * assume", citing {@code EditorContextProvider} and {@code WebRequestTool}. Neither supports it: the former's
     * 20_000 counts filesystem entries scanned, not characters, and the latter's is a caller-configurable
     * {@code maxChars} default clamped to 1-200000. The number is fine; the justification was invented, and a false
     * rationale is worse than none because the next person tunes against it.</p>
     */
    public static final int DEFAULT_RESULT_SPOOL_THRESHOLD_CHARS = 20_000;

    /**
     * Returns {@code text} unchanged when it is short enough to send inline (below {@code thresholdChars}). At or above
     * that it writes the COMPLETE output to a file and returns only the first {@code thresholdChars} characters,
     * followed by a count of what was dropped and the path to the rest.
     *
     * <p>
     * The point is to make the result SMALLER. An earlier version returned the whole payload with the notice appended,
     * so a 2 MB git diff reached the AI as 2 MB plus a claim it had been truncated — more bytes than returning it raw,
     * and a false label on top. Nothing is lost either way: the file always holds the full text.</p>
     *
     * <p>
     * The cut is at exactly {@code thresholdChars} characters and may land mid-line. That is deliberate: the omitted
     * count has to match what was actually dropped, and trimming back to a line boundary makes that arithmetic a second
     * thing to keep right for no real benefit to the reader.</p>
     *
     * @param sessionId the calling AI session; locates its own config directory
     * @param dir the subdirectory inside the session's {@code tmp} root (see {@link TempFileDirEnum})
     * @param toolName short label for the filename (see {@link #spool})
     * @param extension file extension, with or without the leading dot (see {@link #spool})
     * @param text the complete output to return or preserve
     * @param thresholdChars the inline cut-off in characters
     * @return {@code text} when short, or when spooling failed (best-effort — the caller still gets everything rather
     * than losing the tail); otherwise the first {@code thresholdChars} characters, then "... N chars omitted.", then
     * "Full output written to: &lt;path&gt;"
     */
    public static String spoolIfLarge(String sessionId, TempFileDirEnum dir, String toolName, String extension,
            String text, int thresholdChars) {
        String full = text == null ? "" : text;
        if (full.length() < thresholdChars) {
            return text;
        }
        Path file = spool(sessionId, dir, toolName, extension, full);
        if (file == null) {
            // Spooling failed, so the tail exists nowhere else. Returning everything inline is the only option that
            // does not destroy it; a truncated result here would be a silent loss.
            return text;
        }
        int omitted = full.length() - thresholdChars;
        return full.substring(0, thresholdChars)
                + "\n\n... " + omitted + " chars omitted.\n\nFull output written to: " + file;
    }

    /**
     * Unconditionally writes {@code text} into the given session's {@code dir} subdirectory of the registry-owned temp
     * tree and returns the resulting path. Best-effort: returns null (never throws) when the file cannot be created or
     * written. Use this when a caller always wants a durable copy on disk regardless of size (e.g. build/test tools
     * that return a summary body and append the log path). For output that is only worth spooling once it grows past a
     * threshold, use {@link #spoolIfLarge} instead.
     *
     * @param sessionId the calling AI session; locates its own config directory
     * @param dir the subdirectory inside the session's {@code tmp} root (see {@link TempFileDirEnum})
     * @param toolName short label for the filename, e.g. {@code "maven"}, {@code "git-diff"}, {@code "search"} —
     * lowercase letters, digits and dashes only
     * @param dir REQUIRED. Null is rejected rather than treated as "the tmp root": {@link TempFileDirEnum} exists to
     * keep the directories that matter enumerated in one place, and a null that silently parks files in the
     * unenumerated root defeats exactly that. A caller that genuinely needs the root should add a constant for it, so
     * the decision is visible and documented rather than implied by a null.
     * @param extension file extension, with or without the leading dot — {@code ".log"} and {@code "log"} both yield
     * {@code .log}. Null or blank falls through to the platform default ({@code .tmp}).
     * @param text the complete unabridged output to preserve
     * @return the written file's path, or null when the file cannot be created or written — callers treat null as
     * "return the full output instead"
     */
    public static Path spool(String sessionId, TempFileDirEnum dir, String toolName, String extension, String text) {
        if (dir == null) {
            // Returning null rather than throwing keeps this class's never-throws contract; the caller degrades to
            // returning its output inline, which loses nothing.
            LOG.log(Level.WARNING, "Refusing to spool without a TempFileDirEnum — tool={0}", toolName);
            return null;
        }
        TempFile file = TempFileRegistry.createTempFile(sessionId, dir.dirName(), toolName, normaliseExtension(extension));
        if (file == null) {
            return null;
        }
        return writeContent(file, text);
    }

    /**
     * Adds the leading dot when the caller omitted it. {@code File.createTempFile} appends the suffix verbatim, so
     * {@code "log"} produced names ending {@code -1234log} — a stated contract that nothing enforced. Normalising
     * rather than rejecting: the intent is unambiguous, and refusing a spool over a missing dot would lose output to
     * make a point.
     */
    private static String normaliseExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return null;
        }
        String trimmed = extension.trim();
        return trimmed.startsWith(".") ? trimmed : "." + trimmed;
    }

    private static Path writeContent(TempFile file, String text) {
        try {
            Files.writeString(file.path(), text == null ? "" : text, StandardCharsets.UTF_8);
            return file.path();
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Could not spool tool output into " + file, e);
            // Drop the empty husk from cache and disk instead of leaving it for
            // the next age sweep.
            TempFileRegistry.deleteTempFile(file);
            return null;
        }
    }

    private TempFileSpooler() {
    }
}

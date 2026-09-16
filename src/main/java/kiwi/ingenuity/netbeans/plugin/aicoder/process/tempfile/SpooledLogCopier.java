package kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gives a second AI session its own readable copy of a build log.
 * <p>
 * A build several AIs are waiting on is run once, and its full log is spooled into the REQUESTER's temp tree. That tree
 * is readable only by the session that owns it ({@code TempFileSpooler}), so handing another session the requester's
 * path would name a file it cannot open. This copies the log into the other session's own tree and rewrites the path in
 * that session's copy of the result text, so every listener gets a log it can actually read.
 * <p>
 * Best-effort, like the spooler it builds on: any failure returns the text unchanged rather than throwing, since the
 * result body above the path line is still worth delivering.
 */
public final class SpooledLogCopier {

    /**
     * Introduces the spooled log's path at the end of a build result. Shared rather than repeated, because the text is
     * both written (by the build output formatter) and searched for (here, to repoint it at a listener's own copy).
     */
    public static final String LOG_PATH_PREFIX = "Complete log written to: ";

    private static final Logger LOG = Logger.getLogger(SpooledLogCopier.class.getName());
    private static final String FALLBACK_LABEL = "build";

    /**
     * Copies the log named in {@code resultText} into {@code sessionId}'s temp tree and returns the text with the path
     * swapped for that copy.
     *
     * @param sessionId the session that is to receive the result
     * @param resultText a finished build's result, normally ending in a {@link #LOG_PATH_PREFIX} line
     *
     * @return the rewritten text, or {@code resultText} unchanged when there is no path line, or the original cannot be
     * read, or the copy cannot be written
     */
    public static String copyForSession(String sessionId, String resultText) {
        if (sessionId == null || resultText == null) {
            return resultText;
        }
        int prefixAt = resultText.lastIndexOf(LOG_PATH_PREFIX);
        if (prefixAt < 0) {
            return resultText;
        }
        int pathAt = prefixAt + LOG_PATH_PREFIX.length();
        int endOfLine = resultText.indexOf('\n', pathAt);
        String original = (endOfLine < 0 ? resultText.substring(pathAt) : resultText.substring(pathAt, endOfLine)).trim();
        if (original.isEmpty()) {
            return resultText;
        }
        String content = read(original);
        if (content == null) {
            return resultText;
        }
        Path copy = TempFileSpooler.spool(sessionId, TempFileDirEnum.TOOL_RESULTS, labelOf(original), ".log", content);
        return copy == null ? resultText : resultText.replace(original, copy.toString());
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        }
        catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not read a build log to copy for another session: " + path, e);
            return null;
        }
    }

    /**
     * The tool label the original was spooled under, recovered from its file name ({@code maven-<session>-<random>.log}
     * yields {@code maven}), so a listener's copy is recognisably the same kind of log.
     */
    private static String labelOf(String path) {
        String name = Path.of(path).getFileName().toString();
        int dash = name.indexOf('-');
        return dash > 0 ? name.substring(0, dash) : FALLBACK_LABEL;
    }

    private SpooledLogCopier() {
    }
}

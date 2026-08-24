package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.ToolResultSpooler;

/**
 * Turns raw build/test process output into a response worth reading, and parks the complete log where the calling AI
 * session can always get it back.
 * <p>
 * Contract (review 2026-08-23, "build/test tools return a useful result"):
 * <ul>
 * <li>Success — the results/summary block plus the build result line only.</li>
 * <li>Failure — the COMPLETE failure detail verbatim (every [ERROR] line for Maven, the whole failure block for Gradle,
 * javac diagnostics plus the trailer for Ant), never truncated.</li>
 * <li>The full unabridged output is ALWAYS spooled via {@link ToolResultSpooler} into the session's registry-owned temp
 * tree ({@code ~/.ai-coder/{type}/{sessionId}/tmp/tool_results/}, whose lifetime — age sweep plus wholesale removal on
 * session close, IDE shutdown and plugin uninstall — is owned by
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TempFileRegistry}), and its path is appended to the
 * response. That tree is exempt from restrict-to-project via
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer#isOwnSessionConfigFile}, so GetFileContent
 * can read it back without widening any scope rule.</li>
 * </ul>
 * Defensive rule: a summariser that cannot confidently recognise the output shape must degrade to returning EVERYTHING
 * (today's behaviour), never to silently dropping information. The same applies if the log file cannot be written —
 * with no durable copy anywhere, only the full output guarantees nothing was lost.
 */
public final class BuildOutputFormatter {

    // Maven's result banner is "[INFO] BUILD SUCCESS" / "[INFO] BUILD FAILURE";
    // surefire's totals section opens with "[INFO] Results:".
    private static final Pattern MAVEN_RESULT_LINE = Pattern.compile("\\[INFO\\] BUILD (SUCCESS|FAILURE)");
    private static final Predicate<String> MAVEN_RESULTS_MARKER
            = s -> s.trim().equals("[INFO] Results:") || s.trim().equals("Results:");
    private static final String MAVEN_PLUGIN_SECTION_PREFIX = "[INFO] --- ";

    // Gradle prints bare banners: "BUILD SUCCESSFUL in 4s" / "BUILD FAILED in 9s",
    // and structured failures under a "FAILURE: Build failed..." heading.
    private static final Pattern GRADLE_SUCCESS_LINE = Pattern.compile("BUILD SUCCESSFUL.*");
    private static final Pattern GRADLE_FAILED_LINE = Pattern.compile("BUILD FAILED.*");
    private static final Pattern GRADLE_FAILURE_HEADING = Pattern.compile("FAILURE: .*");

    // Ant prints bare banners exactly equal to "BUILD SUCCESSFUL" / "BUILD FAILED".
    // javac diagnostics inside an Ant run look like "path/X.java:12: error: ..."
    // (optionally behind a [javac] prefix) and appear mid-log, BEFORE the trailer.
    private static final Predicate<String> ANT_RESULT_LINE
            = s -> s.trim().equals("BUILD SUCCESSFUL") || s.trim().equals("BUILD FAILED");
    private static final Pattern JAVA_DIAGNOSTIC_LINE = Pattern.compile("\\.java:\\d+:.*\\berror\\b.*");

    /**
     * Formats the output of a build/test run that ran to completion.
     *
     * @param sessionId the calling AI session; locates its own config directory for the log file
     * @param backend which build tool produced the output
     * @param success true when the process exited zero
     * @param exitCode the process exit code (surfaced on failure)
     * @param output the complete captured process output
     */
    public static String formatResult(String sessionId, Backend backend, boolean success,
            int exitCode, String output) {
        Path logFile = ToolResultSpooler.spool(sessionId, backend.logTag(), output);
        if (logFile == null) {
            // No readable copy of the full log exists anywhere, so the only safe
            // response is everything — byte-for-byte what these tools did before.
            return header(backend, success, exitCode) + "\n\n" + output;
        }
        String summary = summarize(backend, success, output);
        String body = summary != null ? summary : output;
        return header(backend, success, exitCode) + "\n\n" + body + "\n\nComplete log written to: " + logFile;
    }

    /**
     * Formats the output of a run that ended abnormally (process timeout or output-reader failure). There is no summary
     * to extract from a half-finished log, so today's full-output behaviour is kept verbatim and the log file is simply
     * attached.
     *
     * @param message the leading explanation line, verbatim as before
     */
    public static String attachLog(String sessionId, Backend backend, String message, String output) {
        Path logFile = ToolResultSpooler.spool(sessionId, backend.logTag(), output);
        String base = message + "\n\n" + output;
        return logFile != null ? base + "\n\nComplete log written to: " + logFile : base;
    }

    private static String header(Backend backend, boolean success, int exitCode) {
        return success ? backend.successWord() : "BUILD FAILED (exit " + exitCode + ")";
    }

    /**
     * Extracts the diagnostically relevant section of {@code output}, or null when the shape is not recognised with
     * confidence (the caller then falls back to the full text).
     */
    static String summarize(Backend backend, boolean success, String output) {
        String[] lines = output.split("\r?\n", -1);
        switch (backend) {
            case MAVEN:
                return summarizeMaven(lines, success);
            case GRADLE:
                return summarizeGradle(lines, success);
            case ANT:
                return summarizeAnt(lines, success);
            default:
                return null;
        }
    }

    /**
     * Maven: tail starts at the last surefire "Results:" marker when present (totals + reactor summary + result banner
     * + total time), otherwise at the result banner itself. On failure every "[ERROR]" line from BEFORE the tail is
     * prepended verbatim — compiler errors and the per-goal failure lines all carry that prefix, so nothing diagnostic
     * is missed. A successful run with neither marker falls back to the last "[INFO] --- " plugin section, which still
     * shows the final goal and the result banner.
     */
    private static String summarizeMaven(String[] lines, boolean success) {
        int resultIdx = lastIndexOf(lines, s -> MAVEN_RESULT_LINE.matcher(s.trim()).matches());
        int resultsIdx = lastIndexOf(lines, MAVEN_RESULTS_MARKER);
        Integer tailStart = null;
        if (resultIdx >= 0 && resultsIdx >= 0 && resultsIdx < resultIdx) {
            tailStart = resultsIdx;
        }
        else if (resultIdx >= 0) {
            tailStart = resultIdx;
        }
        if (tailStart == null) {
            if (!success) {
                return null;
            }
            int pluginSection = lastIndexOf(lines, s -> s.startsWith(MAVEN_PLUGIN_SECTION_PREFIX));
            return pluginSection < 0 ? null : section(lines, pluginSection, lines.length);
        }
        StringBuilder sb = new StringBuilder();
        if (!success) {
            int beforeErrors = sb.length();
            appendSelected(sb, lines, 0, tailStart, s -> s.startsWith("[ERROR]"));
            if (sb.length() > beforeErrors) {
                sb.append('\n');
            }
        }
        appendAll(sb, lines, tailStart, lines.length);
        return sb.toString();
    }

    /**
     * Gradle: on failure the whole structured block from the "FAILURE: Build failed..." heading (or the "BUILD FAILED"
     * banner when no heading was printed) through the end — What-went-wrong, hints, exception stack, test report link
     * and timings. On success just the result banner line.
     */
    private static String summarizeGradle(String[] lines, boolean success) {
        if (success) {
            int okIdx = lastIndexOf(lines, s -> GRADLE_SUCCESS_LINE.matcher(s.trim()).matches());
            return okIdx < 0 ? null : section(lines, okIdx, lines.length);
        }
        int headingIdx = firstIndexOf(lines, s -> GRADLE_FAILURE_HEADING.matcher(s.trim()).matches());
        int failedIdx = firstIndexOf(lines, s -> GRADLE_FAILED_LINE.matcher(s.trim()).matches());
        int start = headingIdx >= 0 ? headingIdx : failedIdx;
        return start < 0 ? null : section(lines, start, lines.length);
    }

    /**
     * Ant: the trailer from the result banner through the end carries the failing target chain and stack trace
     * ("build.xml:123: ..." plus "at org.apache.tools.ant..."). Compile errors do NOT land there — javac prints them
     * mid-run — so every earlier ".java:<line>: ... error" diagnostic line is collected too. Success keeps just the
     * trailer (banner + total time).
     */
    private static String summarizeAnt(String[] lines, boolean success) {
        int resultIdx = firstIndexOf(lines, ANT_RESULT_LINE);
        if (resultIdx < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (!success) {
            int beforeDiag = sb.length();
            appendSelected(sb, lines, 0, resultIdx, s -> JAVA_DIAGNOSTIC_LINE.matcher(s).find());
            if (sb.length() > beforeDiag) {
                sb.append('\n');
            }
        }
        appendAll(sb, lines, resultIdx, lines.length);
        return sb.toString();
    }

    private static String section(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        appendAll(sb, lines, from, to);
        return sb.toString();
    }

    private static void appendAll(StringBuilder sb, String[] lines, int from, int to) {
        for (int i = from; i < to; i++) {
            sb.append(lines[i]);
            if (i < to - 1) {
                sb.append('\n');
            }
        }
    }

    private static void appendSelected(StringBuilder sb, String[] lines, int from, int to,
            Predicate<String> wanted) {
        List<String> picked = new ArrayList<>();
        for (int i = from; i < to; i++) {
            if (wanted.test(lines[i])) {
                picked.add(lines[i]);
            }
        }
        for (int i = 0; i < picked.size(); i++) {
            sb.append(picked.get(i));
            if (i < picked.size() - 1) {
                sb.append('\n');
            }
        }
    }

    private static int firstIndexOf(String[] lines, Predicate<String> matches) {
        for (int i = 0; i < lines.length; i++) {
            if (matches.test(lines[i])) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(String[] lines, Predicate<String> matches) {
        for (int i = lines.length - 1; i >= 0; i--) {
            if (matches.test(lines[i])) {
                return i;
            }
        }
        return -1;
    }

    private BuildOutputFormatter() {
    }

    /**
     * Which build tool produced the output. Drives both the section detection rules (the three tools use different
     * formats) and the success wording of the result line.
     */
    public enum Backend {
        MAVEN("BUILD SUCCESS", "maven"),
        GRADLE("BUILD SUCCESSFUL", "gradle"),
        ANT("BUILD SUCCESSFUL", "ant");

        private final String successWord;
        private final String logTag;

        Backend(String successWord, String logTag) {
            this.successWord = successWord;
            this.logTag = logTag;
        }

        String successWord() {
            return successWord;
        }

        String logTag() {
            return logTag;
        }
    }
}

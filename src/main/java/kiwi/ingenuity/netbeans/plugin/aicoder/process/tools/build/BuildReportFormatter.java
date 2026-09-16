package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.DateUtil;

/**
 * The text built from the build queue: a finished build's result for the calling AI, its one-line summary for the chat,
 * and the ListBuilds report.
 */
public final class BuildReportFormatter {

    /**
     * A finished build's result for the calling AI: status, project, times and duration, then the build's own result
     * text (the same text an inline call returns).
     */
    public static String aiResult(BuildJob job) {
        StringBuilder sb = new StringBuilder("Build ").append(job.id()).append(": ").append(job.request().toolCall())
                .append("\nProject: ").append(job.request().projectPath())
                .append("\nStatus: ").append(statusText(job))
                .append("\nQueued: ").append(DateUtil.format(job.queuedAt()));
        if (job.startedAt() != null) {
            sb.append("\nStarted: ").append(DateUtil.format(job.startedAt()));
        }
        if (job.endedAt() != null) {
            sb.append("\nEnded: ").append(DateUtil.format(job.endedAt()));
        }
        if (job.duration() != null) {
            sb.append("\nDuration: ").append(DateUtil.formatDuration(job.duration()));
        }
        if (job.result() != null && !job.result().isBlank()) {
            sb.append("\n\n").append(job.result());
        }
        return sb.toString();
    }

    /**
     * The one line the user sees in the chat when an async build finishes, e.g.
     * {@code BUILD: BuildMavenProject SUCCESS in 2 mins, 3 secs (/path/to/project)}.
     */
    public static String chatSummary(BuildJob job) {
        String tool = job.request().toolCall();
        int space = tool.indexOf(' ');
        StringBuilder sb = new StringBuilder("BUILD: ").append(space > 0 ? tool.substring(0, space) : tool)
                .append(' ').append(statusText(job));
        if (job.duration() != null) {
            sb.append(" in ").append(DateUtil.formatDuration(job.duration()));
        }
        return sb.append(" (").append(job.request().projectPath()).append(')').toString();
    }

    /**
     * The ListBuilds report as seen by {@code viewerSessionId}. Every AI sees every build, but the id column is filled
     * only for the viewer's own async builds, the only ones it can stop.
     */
    public static String listBuilds(BuildQueueSnapshot snapshot, String viewerSessionId) {
        StringBuilder sb = new StringBuilder("Build queue at ").append(DateUtil.format(snapshot.takenAt()))
                .append("\nOne build runs at a time, first come first served. Ids are shown only for your own async builds.")
                .append("\n\"Longest OK run\" is the longest non-IDE, non-download build or test tool has taken to finish"
                        + " SUCCESSFULLY on that project since the IDE started; an inline build is given that long plus a"
                        + " margin before it times out, so a slow project stops being cut off once it has proved how long"
                        + " it needs.")
                .append("\n\nCurrent builds, in order of execution (").append(snapshot.current().size()).append("):\n");
        appendTable(sb, snapshot.current(), viewerSessionId, false, snapshot.longestSuccessByProject());
        sb.append("\nRecent builds, newest first (last ").append(BuildQueue.RECENT_BUILD_LIMIT).append("):\n");
        appendTable(sb, snapshot.recent(), viewerSessionId, true, snapshot.longestSuccessByProject());
        return sb.toString().stripTrailing();
    }

    private static void appendTable(StringBuilder sb, List<BuildJob> jobs, String viewerSessionId, boolean finished,
                                    Map<String, Duration> longestSuccessByProject) {
        if (jobs.isEmpty()) {
            sb.append("(none)\n");
            return;
        }
        sb.append(finished
                  ? "| Tool call | Project | Caller AI | Type | Status | Started | Ended | Duration | Longest OK run | ID |\n|---|---|---|---|---|---|---|---|---|---|\n"
                  : "| Tool call | Project | Caller AI | Type | Status | Started | Longest OK run | ID |\n|---|---|---|---|---|---|---|---|\n");
        for (BuildJob job : jobs) {
            BuildRequest request = job.request();
            sb.append("| ").append(cell(request.toolCall()))
                    .append(" | ").append(cell(request.projectPath()))
                    .append(" | ").append(cell(request.callerName()))
                    .append(" | ").append(request.type())
                    .append(" | ").append(cell(statusText(job)))
                    .append(" | ").append(job.startedAt() != null ? DateUtil.format(job.startedAt()) : "");
            if (finished) {
                sb.append(" | ").append(job.endedAt() != null ? DateUtil.format(job.endedAt()) : "")
                        .append(" | ").append(job.duration() != null ? DateUtil.formatDuration(job.duration()) : "");
            }
            Duration longest = longestSuccessByProject == null ? null
                               : longestSuccessByProject.get(request.projectKey());
            sb.append(" | ").append(longest != null ? DateUtil.formatDuration(longest) : "none yet");
            boolean ownAsync = request.type() == BuildTypeEnum.ASYNC && request.sessionId().equals(viewerSessionId);
            sb.append(" | ").append(ownAsync ? job.id() : "").append(" |\n");
        }
    }

    private static String statusText(BuildJob job) {
        if (job.status() == BuildStatusEnum.CANCELLED && job.cancelReason() != null) {
            return job.status() + " (" + job.cancelReason().description() + ")";
        }
        if (job.status() == BuildStatusEnum.COMPLETED) {
            return "COMPLETED (result unknown)";
        }
        if (job.status() == BuildStatusEnum.UNKNOWN) {
            return "UNKNOWN (completion not confirmed)";
        }
        return job.status().name();
    }

    private static String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private BuildReportFormatter() {
    }
}

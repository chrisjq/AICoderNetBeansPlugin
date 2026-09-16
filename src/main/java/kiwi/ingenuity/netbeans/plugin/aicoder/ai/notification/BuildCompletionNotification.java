package kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildJob;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildReportFormatter;

/**
 * An async build's result, delivered the way inbox mail is: the chat sees only
 * {@link BuildReportFormatter#chatSummary}, and the calling AI gets the full {@link BuildReportFormatter#aiResult} as
 * agent-only text. Always delivered at once — never held back by the auto-notify-inbox setting, since the calling AI is
 * waiting on this specific result rather than being told about unrelated mail.
 */
public class BuildCompletionNotification extends AbstractNotification {

    private final BuildJob job;
    private final String agentOnlyText;

    public BuildCompletionNotification(BuildJob job) {
        this(job, null);
    }

    /**
     * @param agentOnlyText the exact agent-only text to deliver, or null for the requester's own
     * {@link BuildReportFormatter#aiResult}. A session that only listened to someone else's build is given text naming
     * the log copy in ITS temp tree, since the requester's copy is not readable by it.
     */
    public BuildCompletionNotification(BuildJob job, String agentOnlyText) {
        this.job = job;
        this.agentOnlyText = agentOnlyText;
    }

    @Override
    public String text() {
        return BuildReportFormatter.chatSummary(job);
    }

    /**
     * Returns the per-recipient text when one was given, otherwise the requester's build result.
     *
     * @return the exact per-recipient agent-only text, or the formatted build result when none was given
     */
    @Override
    public String agentOnlyText() {
        return agentOnlyText == null || agentOnlyText.isBlank()
               ? BuildReportFormatter.aiResult(job) : agentOnlyText;
    }

    @Override
    public boolean shouldDeliver() {
        return true;
    }

    @Override
    public NotificationTypeEnum type() {
        return NotificationTypeEnum.BUILD_COMPLETE;
    }

    @Override
    public boolean skipAutoNotifyDeferral() {
        return true;
    }
}

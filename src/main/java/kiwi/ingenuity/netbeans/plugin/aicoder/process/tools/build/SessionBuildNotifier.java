package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.BuildCompletionNotification;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile.SpooledLogCopier;

/**
 * Delivers an async build's result to the session that queued it, the way an important inbox message arrives: a
 * {@link BuildCompletionNotification} reaches the session's inbox-delivery path, and a running session that allows
 * important messages is interrupted to read it.
 * <p>
 * Registered once, at plugin start, via {@link BuildQueue#setCompletionListener}.
 */
public final class SessionBuildNotifier implements BuildCompletionListener {

    @Override
    public void onFinished(BuildJob job) {
        // The requester: only for an async build (an inline caller already has its result from the tool call) and only
        // while its session is still open.
        if (job.request().type() == BuildTypeEnum.ASYNC
                && job.cancelReason() != BuildCancelReasonEnum.SESSION_CLOSED) {
            deliver(job, job.request().sessionId(), null);
        }
        // Everyone who asked for this same build instead of running it a second time, async or inline: the tool call
        // returned them a "joined" reply rather than a result, so this message is the only way they get one. Each gets
        // the log copied into its own temp tree, since the requester's copy is outside its readable scope.
        for (String listenerId : job.listeners()) {
            deliver(job, listenerId, SpooledLogCopier.copyForSession(listenerId, BuildReportFormatter.aiResult(job)));
        }
    }

    /**
     * @param agentOnlyText the exact agent-only text for this recipient, or null for the requester's own
     */
    private static void deliver(BuildJob job, String sessionId, String agentOnlyText) {
        AbstractAiSession abstractSession = SessionRegistry.get(sessionId);
        if (abstractSession == null) {
            return;
        }
        AiSession session = abstractSession.getAiSession();
        session.deliverIncomingMessage(job.request().sessionId(), new BuildCompletionNotification(job, agentOnlyText));
        // Mail, not a dedicated build-interrupt type: a backend that holds Mail until its own in-flight tool call
        // finishes (Claude — see ClaudeAiProcessManager#trackToolCallLifecycle) applies that same hold here, rather
        // than needing its own copy of that logic for a second interrupt reason.
        if (session.isRunning() && session.allowsImportantMessages()) {
            session.requestGracefulInterrupt(InterruptTypeEnum.Mail);
        }
    }
}

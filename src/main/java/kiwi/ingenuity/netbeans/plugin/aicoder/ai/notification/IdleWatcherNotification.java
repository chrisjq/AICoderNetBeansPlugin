package kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch.IdleWatchEventEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch.IdleWatcher;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.DateUtil;

/**
 * An idle watcher firing: the watcher's target has now been continuously idle for the configured timeout, or the target
 * session was closed (which is explicitly NOT an idle event — the closing must be unmistakable so a coordinator can
 * never misread it as its target having gone quiet). Delivered to the watching session, which is normally idle and
 * waiting for exactly this.
 * <p>
 * Like {@link BuildCompletionNotification}, this reaches the session at once — never held back by the auto-notify-inbox
 * setting — because the watcher armed it specifically to be told about this moment.
 */
public class IdleWatcherNotification extends AbstractNotification {

    /**
     * Time-of-day for the one-line chat text, e.g. {@code 14:03:22}. The full agent-only detail uses
     * {@link DateUtil#format(Instant)} instead, which carries the zone.
     */
    private static final DateTimeFormatter TIME_OF_DAY_FORMATTER
            = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final IdleWatcher watcher;
    private final IdleWatchEventEnum event;
    private final Instant idleSince;
    private final String targetName;

    public IdleWatcherNotification(IdleWatcher watcher, IdleWatchEventEnum event, Instant idleSince, String targetName) {
        this.watcher = watcher;
        this.event = event;
        this.idleSince = idleSince;
        this.targetName = targetName;
    }

    @Override
    public String text() {
        String name = targetName;
        if (event == IdleWatchEventEnum.TARGET_CLOSED) {
            return "IDLE WATCH: " + name + " closed — " + watcher.id() + " removed";
        }
        return "IDLE WATCH: " + name + " idle since "
                + (idleSince != null ? TIME_OF_DAY_FORMATTER.format(idleSince) : "?")
                + " (" + watcher.id() + ")";
    }

    @Override
    public String agentOnlyText() {
        StringBuilder sb = new StringBuilder();
        if (event == IdleWatchEventEnum.IDLE) {
            sb.append("IDLE WATCH ").append(watcher.id()).append(": ").append(event)
                    .append(" for target ").append(targetName)
                    .append(" (session ").append(watcher.targetSessionId()).append(")");
            if (idleSince != null) {
                long wholeMinutes = Math.max(0, Duration.between(idleSince, Instant.now()).toMinutes());
                sb.append("\nIdle since: ").append(DateUtil.format(idleSince))
                        .append(" (").append(wholeMinutes).append(" whole minute(s) idle as of now)");
            }
        }
        else {
            // TARGET_CLOSED is NEVER an idle event: the target session was closed, so it cannot be "idle as of now".
            sb.append("IDLE WATCH ").append(watcher.id())
                    .append(": target session ").append(targetName)
                    .append(" (session ").append(watcher.targetSessionId()).append(") was CLOSED");
        }
        sb.append("\nTimeout: ").append(DateUtil.formatDuration(DateUtil.DURATION_TO_MINUTES, watcher.timeout()));
        if (watcher.note() != null && !watcher.note().isBlank()) {
            sb.append("\nNote: ").append(watcher.note());
        }
        sb.append('\n');
        if (event == IdleWatchEventEnum.TARGET_CLOSED) {
            sb.append("The target session was closed; the idle condition was NOT the reason for this notice; watcher"
                    + " removed.");
        }
        else if (watcher.recurring()) {
            sb.append("This recurring watcher stays armed and will fire again the next time ").append(targetName)
                    .append(" goes idle for ").append(watcher.timeout().toMinutes())
                    .append(" minutes after starting another turn. Cancel it with CancelIdleWatcher.");
        }
        else {
            sb.append("This oneshot watcher has now been removed.");
        }
        return sb.toString();
    }

    @Override
    public boolean shouldDeliver() {
        return true;
    }

    @Override
    public NotificationTypeEnum type() {
        return NotificationTypeEnum.IDLE_WATCHER;
    }

    @Override
    public boolean skipAutoNotifyDeferral() {
        return true;
    }
}

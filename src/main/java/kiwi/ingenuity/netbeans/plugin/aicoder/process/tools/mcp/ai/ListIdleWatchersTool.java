package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch.IdleWatcher;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch.IdleWatcherRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch.IdleWatcherStatus;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.DateUtil;

public class ListIdleWatchersTool extends AbstractActionTool {

    public ListIdleWatchersTool() {
        super(McpSectionEnum.PLUGIN,
              McpToolEnum.LIST_IDLE_WATCHERS.toolName(),
              "List this session's idle watcher timers and their current state.",
              McpToolEnum.LIST_IDLE_WATCHERS.toolName()
              + " -> one line per idle watcher owned by this session: id, target, oneshot/recurring, timeout, state and note");
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        if (session == null || !session.getAiSession().allowsIdleWatcherTimer()) {
            return IdleWatcherTools.DISABLED_MESSAGE;
        }
        List<IdleWatcherStatus> statuses = IdleWatcherRegistry.getInstance().list(session.getId());
        if (statuses.isEmpty()) {
            return "No idle watchers created by this session.";
        }
        List<String> lines = new ArrayList<>();
        for (IdleWatcherStatus status : statuses) {
            IdleWatcher watcher = status.watcher();
            String targetName = IdleWatcherTools.sessionName(watcher.targetSessionId());
            String kind = watcher.recurring() ? "recurring" : "oneshot";
            String timeout = DateUtil.formatDuration(DateUtil.DURATION_TO_MINUTES, watcher.timeout());
            String state;
            if (status.firedThisIdlePeriod()) {
                state = "fired for this idle period — re-arms after " + targetName + "'s next turn";
            }
            else if (status.idleSince() != null) {
                state = "idle since " + DateUtil.format(status.idleSince())
                        + ", fires at " + DateUtil.format(status.dueAt());
            }
            else {
                state = targetName + " currently busy — clock stopped";
            }
            StringBuilder line = new StringBuilder();
            line.append(watcher.id()).append(": ").append(targetName)
                    .append(" (").append(watcher.targetSessionId()).append(") — ")
                    .append(kind).append(", ").append(timeout)
                    .append(", ").append(watcher.interrupt() ? "interrupts" : "no interrupt")
                    .append(" — ").append(state);
            String note = watcher.note();
            if (note != null && !note.isBlank()) {
                line.append("; note: ").append(note);
            }
            lines.add(line.toString());
        }
        return String.join("\n", lines);
    }
}

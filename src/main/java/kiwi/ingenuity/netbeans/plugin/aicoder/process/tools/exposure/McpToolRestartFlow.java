package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;

/**
 * Decides whether changing a session's MCP exposure needs a process restart.
 *
 * <p>
 * "Needs a restart" means a tool the AI was not told about is now available to it. That is decided on the tools a
 * session can actually see — {@link McpToolAccess#effectiveTools} — not on group switches, because the inter-AI comms
 * and idle-watcher timer settings also expose or hide tools.
 *
 * <p>
 * The session-tools UI owns persistence and the NetBeans prompt. It calls this class after saving and schedules a
 * restart only after the current turn ends.
 */
public final class McpToolRestartFlow {

    private McpToolRestartFlow() {
    }

    /**
     * The tools a session with {@code settings} can see out of {@code handlers}. Computed up front (before a dialog
     * changes the settings in place) so the result is an immutable snapshot.
     */
    public static Set<McpToolEnum> exposedTools(AiSessionSettings settings, Map<McpToolEnum, McpToolInterface> handlers) {
        EnumSet<McpToolEnum> exposed = EnumSet.noneOf(McpToolEnum.class);
        exposed.addAll(McpToolAccess.effectiveTools(settings, handlers).keySet());
        return Collections.unmodifiableSet(exposed);
    }

    /**
     * Returns tools visible in {@code after} that were not visible in {@code before}.
     */
    public static Set<McpToolEnum> newlyExposed(Set<McpToolEnum> before, Set<McpToolEnum> after) {
        EnumSet<McpToolEnum> added = EnumSet.noneOf(McpToolEnum.class);
        added.addAll(after);
        added.removeAll(before);
        return Collections.unmodifiableSet(added);
    }

    /**
     * Whether a restart prompt should be shown. Ollama applies its tool configuration live, and a backend that is not
     * running needs nothing: its next start already carries the new tools.
     */
    public static boolean shouldPrompt(AiTypeEnum aiType, boolean backendRunning,
                                       Set<McpToolEnum> before, Set<McpToolEnum> after) {
        return aiType != AiTypeEnum.OLLAMA_LOCAL && backendRunning && !newlyExposed(before, after).isEmpty();
    }

    /**
     * Prompts once after configuration has been saved. The supplied restart action must defer an active turn until it
     * ends, then reuse the normal stop/start path.
     */
    public static void onConfigChanged(AiTypeEnum aiType, boolean backendRunning,
                                       Set<McpToolEnum> before, Set<McpToolEnum> after,
                                       Runnable restartWhenTurnEnds) {
        if (!shouldPrompt(aiType, backendRunning, before, after)) {
            return;
        }
        Object restartNow = "Restart now";
        NotifyDescriptor prompt = new NotifyDescriptor(promptText(),
                                                       "Restart session", NotifyDescriptor.DEFAULT_OPTION, NotifyDescriptor.QUESTION_MESSAGE,
                                                       new Object[]{restartNow, "Later"}, "Later");
        if (restartNow.equals(DialogDisplayer.getDefault().notify(prompt))) {
            restartWhenTurnEnds.run();
        }
    }

    /**
     * The prompt text. There is no history-loss variant: every backend that prompts resumes its conversation on restart
     * (Ollama, which does not resume, never prompts because it applies tool changes live).
     */
    public static String promptText() {
        return "Tool changes apply when the session restarts. Restart now?";
    }
}

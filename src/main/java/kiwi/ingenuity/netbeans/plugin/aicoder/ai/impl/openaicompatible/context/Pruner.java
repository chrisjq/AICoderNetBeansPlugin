package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;

/**
 * Shortens old tool output in the model-facing context: tool results older than the keep-recent turns and longer than
 * the prune threshold are replaced by their first {@code charsKept} characters plus a {@code [pruned: M chars]} marker.
 * The chat itself is never touched — only what the model sees.
 *
 * <p>
 * The pruner is pure with respect to everything but the message content it rewrites in place; it holds no state and is
 * safe to reuse. It reports whether it changed anything and how many results it shortened.
 */
public final class Pruner {

    public static final int DEFAULT_THRESHOLD_CHARS = 4000;
    public static final int DEFAULT_CHARS_KEPT = 200;

    private final int thresholdChars;
    private final int charsKept;

    public Pruner() {
        this(DEFAULT_THRESHOLD_CHARS, DEFAULT_CHARS_KEPT);
    }

    /**
     * The pruner a session asks for: its prune threshold and chars kept, falling back to the defaults where unset.
     */
    public static Pruner from(OpenAiCompatibleSessionSettings settings) {
        OpenAiCompatibleSessionSettings session = settings == null ? new OpenAiCompatibleSessionSettings() : settings;
        return new Pruner(session.effectivePruneThresholdChars(), session.effectivePruneCharsKept());
    }

    public Pruner(int thresholdChars, int charsKept) {
        this.thresholdChars = Math.max(0, thresholdChars);
        this.charsKept = Math.max(0, charsKept);
    }

    /**
     * @param keepRecentTurns how many of the newest turns are protected from pruning; the value is clamped to the turn
     * count, so a value at least as large as the turn count prunes nothing
     */
    public Result prune(ContextHistory history, int keepRecentTurns) {
        if (history == null) {
            return new Result(false, 0);
        }
        List<List<ChatMessage>> turns = history.turns();
        int protectedFrom = Math.max(0, turns.size() - Math.max(0, keepRecentTurns));
        int count = 0;
        for (int i = 0; i < protectedFrom; i++) {
            for (ChatMessage message : turns.get(i)) {
                if (message.role() != ChatRole.TOOL) {
                    continue;
                }
                if (prune(message)) {
                    count++;
                }
            }
        }
        return new Result(count > 0, count);
    }

    private boolean prune(ChatMessage message) {
        String content = message.content();
        if (content == null || content.length() <= thresholdChars) {
            return false;
        }
        int kept = Math.min(charsKept, content.length());
        int pruned = content.length() - kept;
        message.setContent(content.substring(0, kept) + "\n[pruned: " + pruned + " chars]");
        return true;
    }

    public int thresholdChars() {
        return thresholdChars;
    }

    public int charsKept() {
        return charsKept;
    }

    /**
     * Whether anything changed, and how many tool results were shortened.
     */
    public record Result(boolean changed, int prunedCount) {

    }
}

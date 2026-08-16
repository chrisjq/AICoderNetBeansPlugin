package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;

/**
 * Mutation logging for the context broker, gated on ai.debugContext.
 *
 * Content is logged, truncated to 100 characters — shapes and counts alone make
 * it too hard to tell which message an eviction actually removed. Truncation
 * lives here rather than at the call sites so no path can bypass it.
 */
public class ContextDebugLog {

    private static final Logger LOG = Logger.getLogger(ContextDebugLog.class.getName());
    private static final int MAX_CHARS = 100;

    public static String truncate(String content) {
        if (content == null) {
            return "<null>";
        }
        String escaped = content.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
        if (content.length() <= MAX_CHARS) {
            return escaped;
        }
        String head = content.substring(0, MAX_CHARS)
                .replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
        return head + "… (" + content.length() + " chars)";
    }

    private final String sessionId;

    public ContextDebugLog(String sessionId) {
        this.sessionId = sessionId;
    }

    public boolean enabled() {
        return PluginSettings.isDebugContext();
    }

    /**
     * @param event one of APPEND, PIN, REPIN, EVICT, TRIM_SUMMARY, SUMMARISE,
     * CALIBRATE, ROLLBACK, PERSIST, RESTORE
     */
    public void event(String event, String detail) {
        if (!enabled()) {
            return;
        }
        LOG.log(Level.INFO, "[ctx {0}] {1} {2}", new Object[]{sessionId, event, detail});
    }

}

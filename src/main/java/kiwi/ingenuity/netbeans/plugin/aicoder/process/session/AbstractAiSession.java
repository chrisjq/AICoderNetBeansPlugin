package kiwi.ingenuity.netbeans.plugin.aicoder.process.session;

import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.MailDeliveryTimingEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

public abstract class AbstractAiSession {

    private final AiSession session;

    protected AbstractAiSession(AiSession session) {
        this.session = session;
    }

    public AiSession getAiSession() {
        return session;
    }

    public abstract String getId();

    public abstract AiProcessEventListener getAiProcessEventListener();

    /**
     * Live — reads from the shared AiSession so renames propagate immediately.
     */
    public String getSessionName() {
        return session.name();
    }

    public abstract Map<McpToolEnum, McpToolInterface> getMcpToolHandlers();

    /**
     * Live — reads from the shared AiSession so settings changes propagate.
     */
    public AiSessionSettings getSettings() {
        return session.settings();
    }

    /**
     * Live — aiType is immutable on AiSession but kept consistent with the shared object.
     */
    public AiTypeEnum getType() {
        return session.aiType();
    }

    /**
     * Live info map from AiSession.infoSnapshot(): name, model (when set), and any extra data. Used by
     * ListAiSessionsTool to build JSON for peers.
     */
    public Map<String, String> getInfo() {
        return session.getSessionInfoMap();
    }

    /**
     * When mail sent with {@code important=true} actually reaches this session.
     *
     * <p>
     * Defaults to the backend's declared timing, which is right for every backend whose capability is fixed. It is
     * overridable because one is not: OpenCode can only steer mid-turn when the agent it spawned exposes the route,
     * which is discovered at runtime from that process. Reporting the static answer there would tell senders the flag
     * does nothing on a session where it works, and they would stop setting it.
     *
     * @return the effective timing; never null
     */
    public MailDeliveryTimingEnum getMailDeliveryTiming() {
        return getType().mailDeliveryTiming();
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.io.File;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;

public interface AiSessionHost {

    File resolveWorkDir();

    void suppressNextTurn(String statusMessage, String completionMessage);

    /**
     * Marks the session busy for a non-turn operation, currently a backend-side compaction. Blocks sending
     * for its duration and releases when it finishes.
     *
     * <p>
     * Deliberately NOT {@link #suppressNextTurn}, whose busy state is a side effect of arming next-turn
     * suppression: a compaction done over RPC ({@code session.history.compact} on Copilot, {@code compact} on
     * pi) produces no turn to consume that arming, so it would sit armed and swallow the user's next real
     * message. This is the busy half alone, with no suppression.
     *
     * <p>
     * Needed because {@code isProcessing()} is false for an RPC's whole duration — nothing is "processing" —
     * so without it the input stays live and a prompt can be sent into a session mid-compaction.
     *
     * <p>
     * Default no-op: every implementation outside {@code AiTopComponent} is a test stub with no input field
     * to disable.
     */
    default void setCompacting(boolean compacting) {
    }

    AiSessionSettings getSessionSettings();

    void updateSessionSettings(AiSessionSettings newSettings);
}

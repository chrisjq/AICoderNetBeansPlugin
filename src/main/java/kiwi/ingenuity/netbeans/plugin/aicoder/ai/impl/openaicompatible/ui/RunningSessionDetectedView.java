package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedSnapshot;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedSnapshotSource;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedSnapshotSources;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowSourceText;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;

/**
 * The Detected panel of a running OpenAI Compatible session, kept up to date while its settings dialog is open: it
 * subscribes to the session's {@link DetectedSnapshotSource} when created and unsubscribes in {@link #dispose()}.
 */
public final class RunningSessionDetectedView {

    private final OpenAiCompatibleDetectedPanel panel = new OpenAiCompatibleDetectedPanel();
    private final JPanel holder = new JPanel(new BorderLayout());
    private final DetectedSnapshotSource source;
    private final AiSession session;
    private final Runnable listener = this::refresh;

    private RunningSessionDetectedView(AiSession session, DetectedSnapshotSource source) {
        this.session = session;
        this.source = source;
        holder.setBorder(BorderFactory.createTitledBorder("Detected"));
        holder.add(panel.component(), BorderLayout.CENTER);
        source.addListener(listener);
        refresh();
    }

    /**
     * @return the view for a running OpenAI Compatible session, or null for any other session or one that is not
     * running (there is then nothing live to show)
     */
    public static RunningSessionDetectedView forSession(AiSession session) {
        if (session == null || session.aiType() != AiTypeEnum.OPENAI_COMPATIBLE) {
            return null;
        }
        DetectedSnapshotSource source = DetectedSnapshotSources.forSession(session.id());
        return source == null ? null : new RunningSessionDetectedView(session, source);
    }

    public JComponent component() {
        return holder;
    }

    /**
     * Stops listening; call when the dialog closes.
     */
    public void dispose() {
        source.removeListener(listener);
    }

    OpenAiCompatibleDetectedPanel panel() {
        return panel;
    }

    private void refresh() {
        DetectedSnapshot snapshot = source.snapshot();
        OpenAiCompatibleSessionSettings settings = session.settings() instanceof OpenAiCompatibleSessionSettings compatible
                                                   ? compatible : null;
        panel.update(snapshot.facts(), settings);
        Runnable rows = () -> {
            if (!snapshot.reachable()) {
                panel.setReachable(false);
            }
            WindowReading window = snapshot.window();
            panel.setWindowSource(window == null ? "" : WindowSourceText.friendly(window.source()));
            panel.setCompaction(snapshot.compactionStrategy());
            panel.setPrunedCount(snapshot.prunedCount());
        };
        if (SwingUtilities.isEventDispatchThread()) {
            rows.run();
        }
        else {
            SwingUtilities.invokeLater(rows);
        }
    }
}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import javax.swing.JComponent;
import javax.swing.JProgressBar;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.UIConstants;

/**
 * A reusable context usage gauge for any OpenAI-compatible backend. Wraps a
 * progress bar showing the ratio of used to configured token threshold.
 *
 * Backend-agnostic: this component contains no reference to specific providers
 * like Ollama, Claude, Copilot, etc. It is embedded in provider-specific info
 * bar extensions.
 */
public class ContextGaugePanel {

    private final JProgressBar bar;

    public ContextGaugePanel() {
        bar = new JProgressBar(0, 100);
        bar.setPreferredSize(new java.awt.Dimension(UIConstants.INFO_BAR_CONTEXT_PROGRESS_WIDTH, UIConstants.INFO_BAR_PROGRESS_HEIGHT));
        bar.setStringPainted(true);
        bar.setString("No usage data");
    }

    /**
     * Update the gauge with current usage and configured token threshold.
     *
     * @param used total tokens estimated to be in the context
     * @param total the configured token threshold (NOT the model's context
     * window, which is unreliably discoverable for OpenAI-compatible endpoints)
     */
    public void update(int used, int total) {
        String displayStr = String.format("%,d / %,d", used, total);
        bar.setString(displayStr);

        int pct = total > 0 ? (int) ((used * 100L) / total) : 0;
        int value = Math.min(100, pct);
        bar.setValue(value);

        int remaining = Math.max(0, total - used);
        String tooltip = String.format(
                "Context usage: %,d / %,d; %,d remaining (%d%%) — 'total' is the configured threshold, not the model's context window",
                used, total, remaining, pct);
        bar.setToolTipText(tooltip);
    }

    /**
     * Toggle the indeterminate state, used when context is being compacted.
     */
    public void setSummarising(boolean summarising) {
        bar.setIndeterminate(summarising);
    }

    /**
     * @return the underlying progress bar
     */
    public JProgressBar bar() {
        return bar;
    }

    /**
     * @return the progress bar as a component for inclusion in a toolbar or
     * info bar
     */
    public JComponent component() {
        return bar;
    }
}

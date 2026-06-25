package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.ui;

import java.awt.Dimension;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;

/**
 *
 * @author chris
 */
public class GithubCopilotAiInfoBarExtension implements AiInfoBarExtension {

    private static int defaultMaxTokensForModel(String model) {
        if (model == null || model.isBlank()) {
            return 200000;
        }
        String m = model.toLowerCase();
        if (m.startsWith("claude-") || m.equals("auto")) {
            return 200000;
        }
        if (m.startsWith("gpt-4") || m.startsWith("gpt-5") || m.contains("o1") || m.contains("o3") || m.contains("o4")) {
            return 128000;
        }
        return 128000;
    }

    private final AiSession session;
    private final AiSessionHost host;
    private final javax.swing.JLabel errorLabel;
    private final javax.swing.JProgressBar contextBar;
    private final JButton compactBtn;
    private final JComboBox<String> modelCombo;
    private final List<GithubCopilotInfoBarListener> listeners = new ArrayList<>();
    private volatile int maxTokens = 0;
    private volatile int currentTokens = 0;
    private volatile boolean hasUsageData = false;
    private volatile String fatalError = null;

    public GithubCopilotAiInfoBarExtension(AiSession session, AiSessionHost host) {
        this.session = session;
        this.host = host;
        errorLabel = new javax.swing.JLabel();
        errorLabel.setFont(errorLabel.getFont().deriveFont(11f).deriveFont(java.awt.Font.BOLD));
        errorLabel.setForeground(java.awt.Color.WHITE);
        errorLabel.setBackground(new java.awt.Color(200, 50, 50));
        errorLabel.setOpaque(true);
        errorLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 4, 2, 4));
        errorLabel.setVisible(false);
        modelCombo = new JComboBox<>(GithubCopilotPluginSettings.getKnownModels());
        modelCombo.setEditable(true);
        modelCombo.setSelectedItem(GithubCopilotPluginSettings.getModel());
        modelCombo.setToolTipText("Model — pick or type a model ID");
        String initialModel = (String) modelCombo.getSelectedItem();
        maxTokens = defaultMaxTokensForModel(initialModel);
        // Copilot never reports a context-window size, so it always comes from
        // the selected model — recompute it on every model change (not just
        // before the first usage event) so the bar's denominator stays correct.
        modelCombo.addActionListener(e -> {
            String sel = (String) modelCombo.getSelectedItem();
            maxTokens = defaultMaxTokensForModel(sel);
            if (hasUsageData) {
                updateContextBar();
            }
        });
        compactBtn = new JButton("⇒ Compact");
        compactBtn.setFont(compactBtn.getFont().deriveFont(11f));
        compactBtn.setToolTipText("Compact conversation to reduce context window usage");
        compactBtn.addActionListener(e -> listeners.forEach(GithubCopilotInfoBarListener::onCompactRequested));
        contextBar = new javax.swing.JProgressBar(0, 100);
        contextBar.setPreferredSize(new Dimension(170, 14));
        contextBar.setStringPainted(true);
        contextBar.setString("No usage data");
        contextBar.setToolTipText("Context window usage — tokens used / total available");
        // Always visible (like the Claude infobar); the string updates to real
        // token counts once a turn reports usage.
        contextBar.setVisible(true);
    }

    public void addListener(GithubCopilotInfoBarListener l) {
        listeners.add(l);
    }

    public void removeListener(GithubCopilotInfoBarListener l) {
        listeners.remove(l);
    }

    public void addModelChangeListener(ActionListener l) {
        modelCombo.addActionListener(e -> {
            if (l != null) {
                l.actionPerformed(e);
            }
        });
    }

    public String getSelectedModel() {
        Object item = modelCombo.getEditor().getItem();
        String typed = item != null ? item.toString().trim() : "";
        return typed.isEmpty() ? GithubCopilotPluginSettings.getModel() : typed;
    }

    public void setSelectedModel(String model) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setSelectedModel(model));
            return;
        }
        modelCombo.setSelectedItem(model);
    }

    /**
     * Replaces the dropdown's items with a discovered model list, preserving
     * the current selection. The combo stays editable so any model can still be
     * typed. EDT-safe.
     */
    public void setAvailableModels(String[] models) {
        if (models == null || models.length == 0) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setAvailableModels(models));
            return;
        }
        String current = getSelectedModel();
        modelCombo.removeAllItems();
        for (String m : models) {
            modelCombo.addItem(m);
        }
        modelCombo.setSelectedItem(current);
        if (!current.equals(modelCombo.getSelectedItem())) {
            modelCombo.getEditor().setItem(current);
        }
    }

    private void updateContextBar() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::updateContextBar);
            return;
        }
        if (!hasUsageData) {
            contextBar.setVisible(true);
            hasUsageData = true;
        }
        int pct = maxTokens > 0 ? (int) ((currentTokens * 100.0) / maxTokens) : 0;
        int remaining = Math.max(0, maxTokens - currentTokens);
        contextBar.setValue(Math.min(100, pct));
        contextBar.setString(String.format("%,d / %,d", currentTokens, maxTokens));
        contextBar.setToolTipText(String.format(
                "Token usage: %,d / %,d; %,d remaining (%d%%)",
                currentTokens, maxTokens, remaining, pct));
    }

    @Override
    public List<javax.swing.JComponent> createComponents() {
        return List.of(modelCombo, compactBtn, contextBar, errorLabel);
    }

    @Override
    public void onPropertyEvent(kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent event) {
    }

    @Override
    public void onAiProcessImplEvent(kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent event) {
        if (event instanceof kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.events.GithubCopilotTokenUsageEvent te) {
            currentTokens = te.currentTokens();
            // Prefer the REAL context-window size read from the Copilot CLI log
            // (CompactionProcessor "used/total tokens"); only fall back to a
            // per-model estimate before the first log read provides a total.
            if (te.maxTokens() > 0) {
                maxTokens = te.maxTokens();
            }
            else if (te.model() != null && !te.model().isBlank()) {
                maxTokens = defaultMaxTokensForModel(te.model());
            }
            updateContextBar();
        }
        else if (event instanceof kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.events.GithubCopilotFatalErrorEvent error) {
            fatalError = error.errorMessage();
            updateErrorLabel();
        }
    }

    private void updateErrorLabel() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::updateErrorLabel);
            return;
        }
        if (fatalError != null) {
            errorLabel.setText("⚠ " + fatalError);
            errorLabel.setVisible(true);
        }
        else {
            errorLabel.setVisible(false);
        }
    }

    @Override
    public void onSessionPct(double pct) {
        if (pct >= 0) {
            currentTokens = (int) (pct * maxTokens / 100.0);
            updateContextBar();
        }
    }

}

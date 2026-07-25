package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionListener;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events.GrokModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events.GrokTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Grok info bar: model selector + context-window usage progress bar. Mirrors
 * {@code ClaudeAiInfoBarExtension} / {@code GithubCopilotAiInfoBarExtension}
 * but omits the compact button and rate-limit bars — grok's headless CLI has no
 * documented context-compaction command or 5-hour/7-day rate-limit query,
 * unlike Claude.
 */
public class GrokAiInfoBarExtension implements AiInfoBarExtension {

    private final JComboBox<String> modelCombo;
    private final JProgressBar contextBar;
    private volatile int maxTokens = 128000;
    private volatile int currentTokens = 0;
    private volatile boolean hasUsageData = false;
    private boolean programmatic = false;
    private Runnable disposeAction;

    public GrokAiInfoBarExtension() {
        modelCombo = new JComboBox<>(GrokPluginSettings.KNOWN_MODELS);
        modelCombo.setEditable(true);
        modelCombo.setSelectedItem(GrokPluginSettings.getModel());
        modelCombo.setToolTipText("Grok model — pick from list or type any model ID");

        contextBar = new JProgressBar(0, 100);
        contextBar.setPreferredSize(new Dimension(170, 14));
        contextBar.setStringPainted(true);
        contextBar.setString("No usage data");
        contextBar.setToolTipText("Context window usage — tokens used / total available");
    }

    public void setDisposeAction(Runnable disposeAction) {
        this.disposeAction = disposeAction;
    }

    @Override
    public void dispose() {
        if (disposeAction != null) {
            disposeAction.run();
            disposeAction = null;
        }
    }

    @Override
    public List<JComponent> createComponents() {
        return List.of(modelCombo, contextBar);
    }

    public void addModelChangeListener(ActionListener l) {
        modelCombo.addActionListener(e -> {
            if (!programmatic) {
                l.actionPerformed(e);
            }
        });
    }

    public String getSelectedModel() {
        Object item = modelCombo.getEditor().getItem();
        String typed = item != null ? item.toString().trim() : "";
        return typed.isEmpty() ? GrokPluginSettings.DEFAULT_MODEL : typed;
    }

    public void setSelectedModel(String model) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setSelectedModel(model));
            return;
        }
        programmatic = true;
        try {
            Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            modelCombo.setSelectedItem(model);
            if (focused != null) {
                focused.requestFocusInWindow();
            }
        }
        finally {
            programmatic = false;
        }
    }

    /**
     * Replaces the dropdown's items with a discovered model list, preserving
     * the current selection. The combo stays editable so any model can still be
     * typed. EDT-safe.
     */
    public void setAvailableModels(List<String> models) {
        if (models == null || models.isEmpty()) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setAvailableModels(models));
            return;
        }
        programmatic = true;
        try {
            Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            String current = getSelectedModel();
            modelCombo.removeAllItems();
            for (String m : models) {
                modelCombo.addItem(m);
            }
            modelCombo.setSelectedItem(current);
            if (!current.equals(getSelectedModel())) {
                modelCombo.getEditor().setItem(current);
            }
            if (focused != null) {
                focused.requestFocusInWindow();
            }
        }
        finally {
            programmatic = false;
        }
    }

    private void updateContextBar() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::updateContextBar);
            return;
        }
        hasUsageData = true;
        int pct = maxTokens > 0 ? (int) ((currentTokens * 100.0) / maxTokens) : 0;
        int remaining = Math.max(0, maxTokens - currentTokens);
        contextBar.setValue(Math.min(100, pct));
        contextBar.setString(String.format("%,d / %,d", currentTokens, maxTokens));
        contextBar.setToolTipText(String.format(
                "Token usage: %,d / %,d; %,d remaining (%d%%)",
                currentTokens, maxTokens, remaining, pct));
    }

    @Override
    public void onPropertyEvent(AiPropertyEvent event) {
        if (event instanceof GrokModelsEvent me) {
            setAvailableModels(me.models());
        }
    }

    @Override
    public void onAiProcessImplEvent(AiProcessImplEvent event) {
        if (event instanceof GrokTokenUsageEvent te) {
            currentTokens = te.currentTokens();
            if (te.maxTokens() > 0) {
                maxTokens = te.maxTokens();
            }
            updateContextBar();
        }
    }

    @Override
    public void onSessionPct(double pct) {
        if (pct >= 0 && hasUsageData) {
            currentTokens = (int) (pct * maxTokens / 100.0);
            updateContextBar();
        }
    }

}

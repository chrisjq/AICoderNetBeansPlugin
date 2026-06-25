package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.events.ClaudeModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.events.ClaudeSessionInfoEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.events.ClaudeUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

public class ClaudeAiInfoBarExtension implements AiInfoBarExtension {

    private static void setPctBar(JProgressBar bar, double pct, String label) {
        if (pct < 0) {
            bar.setValue(0);
            bar.setString("N/A");
            bar.setToolTipText(label + " usage: not available — run 'claude auth login' if this persists");
        }
        else {
            int v = (int) Math.min(100, pct);
            bar.setValue(v);
            bar.setString(v + "%");
            bar.setToolTipText(String.format("%s usage: %.1f%%", label, pct));
        }
    }

    private final JComboBox<String> modelCombo;
    private final JButton compactBtn;
    private final JProgressBar sessionBar;
    private final JProgressBar fiveHourBar;
    private final JProgressBar sevenDayBar;

    private final List<ClaudeInfoBarListener> listeners = new ArrayList<>();
    private boolean programmatic = false;

    public ClaudeAiInfoBarExtension() {
        modelCombo = new JComboBox<>(ClaudePluginSettings.KNOWN_MODELS);
        modelCombo.setEditable(true);
        modelCombo.setSelectedItem(ClaudePluginSettings.getModel());
        modelCombo.setToolTipText("Claude model — pick from list or type any model ID");

        compactBtn = new JButton("⇒ Compact");
        compactBtn.setFont(compactBtn.getFont().deriveFont(11f));
        compactBtn.setToolTipText("Compact conversation to reduce context window usage");
        compactBtn.addActionListener(e -> listeners.forEach(ClaudeInfoBarListener::onCompactRequested));

        sessionBar = new JProgressBar(0, 100);
        sessionBar.setPreferredSize(new Dimension(70, 14));
        sessionBar.setStringPainted(true);
        sessionBar.setString("ctx");
        sessionBar.setToolTipText("Context window usage %");

        fiveHourBar = new JProgressBar(0, 100);
        fiveHourBar.setPreferredSize(new Dimension(55, 14));
        fiveHourBar.setStringPainted(true);
        fiveHourBar.setString("Session");
        fiveHourBar.setToolTipText("5-hour rate limit usage % (resets every 5 hours)");

        sevenDayBar = new JProgressBar(0, 100);
        sevenDayBar.setPreferredSize(new Dimension(55, 14));
        sevenDayBar.setStringPainted(true);
        sevenDayBar.setString("Weekly");
        sevenDayBar.setToolTipText("7-day rate limit usage % (resets weekly)");
    }

    public void addListener(ClaudeInfoBarListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ClaudeInfoBarListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void dispose() {
        listeners.clear();
    }

    @Override
    public List<JComponent> createComponents() {
        return List.of(modelCombo, compactBtn, sessionBar, fiveHourBar, sevenDayBar);
    }

    @Override
    public void onPropertyEvent(AiPropertyEvent event) {
        switch (event) {
            case ClaudeUsageEvent ue ->
                SwingUtilities.invokeLater(() -> {
                    setPctBar(fiveHourBar, ue.fiveHourPct(), "Session (5-hour limit)");
                    setPctBar(sevenDayBar, ue.sevenDayPct(), "Weekly (7-day limit)");
                });
            case ClaudeModelsEvent me ->
                SwingUtilities.invokeLater(() -> setAvailableModels(me.models()));
            default -> {
            }
        }
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
        return typed.isEmpty() ? ClaudePluginSettings.DEFAULT_MODEL : typed;
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

    public void setAvailableModels(List<String> models) {
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

    @Override
    public void onSessionPct(double pct) {
        setSessionPct(pct);
    }

    public void setSessionPct(double pct) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setSessionPct(pct));
            return;
        }
        if (pct < 0) {
            sessionBar.setValue(0);
            sessionBar.setString("N/A");
            sessionBar.setToolTipText("Context window usage: not available");
        }
        else {
            int v = (int) Math.min(100, pct);
            sessionBar.setValue(v);
            sessionBar.setString(v + "%");
            sessionBar.setToolTipText(String.format("Context window usage: %.1f%%", pct));
        }
    }

    @Override
    public void onAiProcessImplEvent(AiProcessImplEvent event) {
        if (event instanceof ClaudeSessionInfoEvent si) {
            if (si.hasSessionPct()) {
                setSessionPct(si.sessionPct());
            }
        }
    }

}

package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.events.AiInfoBarListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;

public class AiInfoBar extends JPanel {

    private final JLabel timeLabel;
    private final JCheckBox saveHistoryCheck;
    private final JCheckBox autoAcceptCheck;
    private final JLabel statusLabel;
    private final JButton stopButton;
    private final JButton configBtn;
    private final JPanel aiControlsPanel;

    private final GridBagConstraints compGbc;
    private final GridBagConstraints sepGbc;
    private final GridBagConstraints fillerGbc;
    private final JLabel fillerLabel;

    private final List<AiInfoBarListener> listeners = new ArrayList<>();

    private long sessionStartMs = 0;
    private final Timer clockTimer;

    public AiInfoBar() {
        setLayout(new BorderLayout(0, 0));

        compGbc = new GridBagConstraints();
        compGbc.insets = new Insets(0, 3, 0, 3);
        sepGbc = new GridBagConstraints();
        sepGbc.fill = GridBagConstraints.VERTICAL;
        sepGbc.insets = new Insets(2, 2, 2, 2);
        fillerGbc = new GridBagConstraints();
        fillerGbc.weightx = 1.0;
        fillerGbc.fill = GridBagConstraints.HORIZONTAL;

        // --- LEFT: session time ---
        JPanel leftPanel = new JPanel(new GridBagLayout());
        leftPanel.setOpaque(false);
        timeLabel = new JLabel("time: —");
        timeLabel.setFont(timeLabel.getFont().deriveFont(11f));
        timeLabel.setToolTipText("Session duration");
        GridBagConstraints leftGbc = new GridBagConstraints();
        leftGbc.insets = new Insets(0, 6, 0, 6);
        leftPanel.add(timeLabel, leftGbc);
        add(leftPanel, BorderLayout.WEST);

        // --- CENTRE: AI-implementation-specific controls (populated via setExtension) ---
        aiControlsPanel = new JPanel(new GridBagLayout());
        aiControlsPanel.setOpaque(false);
        fillerLabel = new JLabel();
        aiControlsPanel.add(fillerLabel, fillerGbc);
        add(aiControlsPanel, BorderLayout.CENTER);

        // --- RIGHT: stop, auto-accept, save, status ---
        JPanel rightPanel = new JPanel(new GridBagLayout());
        rightPanel.setOpaque(false);

        stopButton = new JButton("■ Stop");
        stopButton.setFont(stopButton.getFont().deriveFont(11f));
        stopButton.setToolTipText("Cancel current response");
        stopButton.setVisible(false);
        stopButton.addActionListener(e -> listeners.forEach(AiInfoBarListener::onStopRequested));
        rightPanel.add(stopButton, compGbc);

        // Initial state is false; AiTopComponent sets the per-session value after construction.
        autoAcceptCheck = new JCheckBox("Auto-Accept", false);
        autoAcceptCheck.setFont(autoAcceptCheck.getFont().deriveFont(11f));
        autoAcceptCheck.setToolTipText("Automatically accept all file changes without showing diff");
        autoAcceptCheck.addActionListener(e -> listeners.forEach(l -> l.onAutoAcceptChanged(autoAcceptCheck.isSelected())));
        rightPanel.add(autoAcceptCheck, compGbc);

        saveHistoryCheck = new JCheckBox("Save", PluginSettings.isSaveHistory());
        saveHistoryCheck.setFont(saveHistoryCheck.getFont().deriveFont(11f));
        saveHistoryCheck.setToolTipText("Save conversation history across sessions");
        saveHistoryCheck.addActionListener(e -> PluginSettings.setSaveHistory(saveHistoryCheck.isSelected()));
        rightPanel.add(saveHistoryCheck, compGbc);

        statusLabel = new JLabel(" ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));
        statusLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        statusLabel.setText("n".repeat(15));
        int statusWidth = statusLabel.getPreferredSize().width;
        statusLabel.setText(" ");
        statusLabel.setPreferredSize(new Dimension(statusWidth, statusLabel.getPreferredSize().height));
        GridBagConstraints statusGbc = new GridBagConstraints();
        statusGbc.insets = new Insets(0, 3, 0, 3);
        rightPanel.add(statusLabel, statusGbc);

        configBtn = new JButton("⚙");
        configBtn.setToolTipText("Session configuration");
        configBtn.setMargin(new Insets(0, 4, 0, 4));
        configBtn.addActionListener(e -> listeners.forEach(AiInfoBarListener::onSettingsRequested));
        GridBagConstraints configGbc = new GridBagConstraints();
        configGbc.insets = new Insets(0, 2, 0, 4);
        rightPanel.add(configBtn, configGbc);

        add(rightPanel, BorderLayout.EAST);

        clockTimer = new Timer(1000, e -> updateClock());
        clockTimer.setCoalesce(true);
    }

    public void addListener(AiInfoBarListener listener) {
        listeners.add(listener);
    }

    /**
     * Call when a session starts (binary validated). Call on EDT.
     */
    public void startSessionClock() {
        sessionStartMs = System.currentTimeMillis();
        timeLabel.setText("time: 0:00");
        clockTimer.start();
    }

    /**
     * Reset and stop the clock (call on new session). Call on EDT.
     */
    public void resetSessionClock() {
        clockTimer.stop();
        sessionStartMs = 0;
        timeLabel.setText("time: —");
    }

    private void updateClock() {
        if (sessionStartMs == 0) {
            return;
        }
        Duration d = Duration.ofMillis(System.currentTimeMillis() - sessionStartMs);
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        timeLabel.setText(h > 0
                ? String.format("time: %d:%02d:%02d", h, m, s)
                : String.format("time: %d:%02d", m, s));
    }

    public void setStatusMessage(String text) {
        String raw = (text == null || text.isBlank()) ? " " : text;
        statusLabel.setText(fitToLabel(raw));
        statusLabel.setToolTipText(raw.isBlank() ? null : raw);
    }

    private String fitToLabel(String text) {
        if (text.isBlank()) {
            return text;
        }
        int maxW = statusLabel.getWidth() > 0
                ? statusLabel.getWidth()
                : statusLabel.getPreferredSize().width;
        FontMetrics fm = statusLabel.getFontMetrics(statusLabel.getFont());
        if (fm.stringWidth(text) <= maxW) {
            return text;
        }
        String ellipsis = "...";
        int ellipsisW = fm.stringWidth(ellipsis);
        String t = text;
        while (!t.isEmpty() && fm.stringWidth(t) + ellipsisW > maxW) {
            t = t.substring(0, t.length() - 1);
        }
        return t + ellipsis;
    }

    /**
     * Attach backend-specific info bar components. Call on EDT before the
     * component is shown.
     */
    public void setExtension(AiInfoBarExtension extension) {
        aiControlsPanel.remove(fillerLabel);
        for (JComponent comp : extension.createComponents()) {
            aiControlsPanel.add(new JSeparator(JSeparator.VERTICAL), sepGbc);
            aiControlsPanel.add(comp, compGbc);
        }
        aiControlsPanel.add(fillerLabel, fillerGbc);
        aiControlsPanel.revalidate();
        aiControlsPanel.repaint();
    }

    /**
     * Set the auto-accept checkbox state without firing the listener (used to
     * initialise or sync the UI from session settings).
     */
    public void setAutoAccept(boolean value) {
        autoAcceptCheck.setSelected(value);
    }

    public boolean isAutoAccept() {
        return autoAcceptCheck.isSelected();
    }

    /**
     * Show or hide the stop button (call on EDT).
     */
    public void setProcessing(boolean processing) {
        stopButton.setVisible(processing);
        revalidate();
        repaint();
    }
}

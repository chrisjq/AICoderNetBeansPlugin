package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextTriggerEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextTrimStrategyEnum;

/**
 * Settings panel for context-history behaviour. Binds to
 * OpenAiClientSessionSettings so any OpenAI-compatible backend can embed the
 * same instance — this class contains no reference to any specific backend.
 */
public class OpenAiContextSettingsPanel extends JPanel {

    private final JComboBox<ContextTriggerEnum> triggerCombo
            = new JComboBox<>(ContextTriggerEnum.values());
    private final JComboBox<ContextTrimStrategyEnum> strategyCombo
            = new JComboBox<>(ContextTrimStrategyEnum.values());
    private final JSpinner thresholdSpinner
            = new JSpinner(new SpinnerNumberModel(100000, 100, 1000000, 500));
    private final JSpinner trimTargetSpinner
            = new JSpinner(new SpinnerNumberModel(75, 10, 95, 5));
    private final JSpinner maxMessagesSpinner
            = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 10));
    private final JCheckBox persistOnClose
            = new JCheckBox("Keep context when the session closes");
    private final JLabel thresholdHintLabel = new JLabel();

    public OpenAiContextSettingsPanel() {
        setBorder(BorderFactory.createTitledBorder("Context History"));
        setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        triggerCombo.setToolTipText("When to evaluate whether the context needs trimming");
        strategyCombo.setToolTipText("What to do when the context exceeds the token threshold");
        thresholdSpinner.setToolTipText("<html>Absolute token count at which trimming is triggered.<br>"
                + "The pinned instructions and tool catalogue alone are typically 2,500–3,000 tokens;<br>"
                + "a threshold below that leaves no room for conversation history.<br>"
                + "This is not a percentage of the model's context window — that window<br>"
                + "is not reliably discoverable across arbitrary OpenAI-compatible endpoints.<br>"
                + "Ollama defaults its context window to 4096 unless raised via a Modelfile (PARAMETER num_ctx)<br>"
                + "or the OLLAMA_CONTEXT_LENGTH environment variable, so keep this comfortably below the server's actual limit.</html>");
        trimTargetSpinner.setToolTipText(
                "After trimming, reduce context to this percentage of the token threshold");
        maxMessagesSpinner.setToolTipText("Maximum messages to keep in context (0 = no limit)");
        persistOnClose.setToolTipText(
                "Persist the conversation context so it is available when the session reopens");

        thresholdHintLabel.setFont(thresholdHintLabel.getFont().deriveFont(11f));
        thresholdHintLabel.setForeground(java.awt.Color.GRAY);
        updateThresholdHint();
        thresholdSpinner.addChangeListener(e -> updateThresholdHint());

        addRow(c, 0, new JLabel("Trim trigger:"), triggerCombo);
        addRow(c, 1, new JLabel("When over budget:"), strategyCombo);
        addRow(c, 2, new JLabel("Token threshold:"), thresholdSpinner);
        addFull(c, 3, thresholdHintLabel);
        addRow(c, 4, new JLabel("Trim down to (% of threshold):"), trimTargetSpinner);
        addRow(c, 5, new JLabel("Max messages (0 = off):"), maxMessagesSpinner);
        addFull(c, 6, persistOnClose);
    }

    public void load(OpenAiClientSessionSettings settings) {
        triggerCombo.setSelectedItem(settings.effectiveContextTrimTrigger());
        strategyCombo.setSelectedItem(settings.effectiveContextTrimStrategy());
        thresholdSpinner.setValue(settings.effectiveContextTokenThreshold());
        trimTargetSpinner.setValue(settings.effectiveContextTrimTargetPercent());
        maxMessagesSpinner.setValue(settings.effectiveContextMaxMessages());
        persistOnClose.setSelected(settings.effectiveContextPersistOnClose());
    }

    public void applyTo(OpenAiClientSessionSettings settings) {
        settings.setContextTrimTrigger((ContextTriggerEnum) triggerCombo.getSelectedItem());
        settings.setContextTrimStrategy((ContextTrimStrategyEnum) strategyCombo.getSelectedItem());
        settings.setContextTokenThreshold((Integer) thresholdSpinner.getValue());
        settings.setContextTrimTargetPercent((Integer) trimTargetSpinner.getValue());
        settings.setContextMaxMessages((Integer) maxMessagesSpinner.getValue());
        settings.setContextPersistOnClose(persistOnClose.isSelected());
    }

    public void addChangeListener(ActionListener listener) {
        triggerCombo.addActionListener(listener);
        strategyCombo.addActionListener(listener);
        thresholdSpinner.addChangeListener(e -> listener.actionPerformed(null));
        trimTargetSpinner.addChangeListener(e -> listener.actionPerformed(null));
        maxMessagesSpinner.addChangeListener(e -> listener.actionPerformed(null));
        persistOnClose.addActionListener(listener);
    }

    private void addRow(GridBagConstraints c, int row, JLabel label, java.awt.Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        c.gridwidth = 1;
        add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        add(field, c);
    }

    private void addFull(GridBagConstraints c, int row, java.awt.Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        add(field, c);
        c.gridwidth = 1;
    }

    private void updateThresholdHint() {
        int v = (Integer) thresholdSpinner.getValue();
        thresholdHintLabel.setText(String.format(
                "Requires an AI server context window of at least %,d tokens.", v));
    }

    String thresholdHintText() {
        return thresholdHintLabel.getText();
    }
}

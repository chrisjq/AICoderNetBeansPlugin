package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.AccessControlLabelEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;

public final class AiMessagingSettingsPanel extends JPanel {

    private static String label(AccessControlLabelEnum label, boolean sessionOverrideLabels,
            boolean globalEnabled) {
        return sessionOverrideLabels ? label.sessionLabel(globalEnabled) : label.globalLabel();
    }

    // In session mode each control shows a live "Set from Global" / "Set on Session"
    // marker; base labels are kept so the marker can be re-applied on toggle.
    private final boolean sessionMode;
    private final JCheckBox allowInterAiCheckBox = new JCheckBox();
    private final JCheckBox autoNotifyInboxCheckBox = new JCheckBox();
    private final JCheckBox allowImportantMessagesCheckBox = new JCheckBox();
    private final String allowInterAiBaseLabel;
    private final String autoNotifyBaseLabel;
    private final String allowImportantBaseLabel;
    private final JPanel autoNotifyRow = new JPanel(new BorderLayout(4, 0));

    private Component autoNotifyAccessory;

    public AiMessagingSettingsPanel(boolean sessionOverrideLabels) {
        this.sessionMode = sessionOverrideLabels;
        setBorder(BorderFactory.createTitledBorder("AI Messaging"));
        setLayout(new GridBagLayout());

        allowInterAiBaseLabel = label(AccessControlLabelEnum.ALLOW_INTER_AI_COMMS,
                sessionOverrideLabels, PluginSettings.isAllowInterAiComms());
        autoNotifyBaseLabel = label(AccessControlLabelEnum.AUTO_NOTIFY_INBOX,
                sessionOverrideLabels, PluginSettings.isAutoNotifyInbox());
        allowImportantBaseLabel = label(AccessControlLabelEnum.ALLOW_IMPORTANT_MESSAGES,
                sessionOverrideLabels, PluginSettings.isAllowImportantMessages());
        allowInterAiCheckBox.setText(allowInterAiBaseLabel);
        autoNotifyInboxCheckBox.setText(autoNotifyBaseLabel);
        allowImportantMessagesCheckBox.setText(allowImportantBaseLabel);

        autoNotifyRow.add(autoNotifyInboxCheckBox, BorderLayout.CENTER);

        addRow(allowInterAiCheckBox, 0, 0);
        addRow(autoNotifyRow, 1, 20);
        addRow(allowImportantMessagesCheckBox, 2, 20);

        allowInterAiCheckBox.addActionListener(e -> updateDependentState());
        if (sessionMode) {
            allowInterAiCheckBox.addActionListener(e -> refreshMarkers());
            autoNotifyInboxCheckBox.addActionListener(e -> refreshMarkers());
            allowImportantMessagesCheckBox.addActionListener(e -> refreshMarkers());
        }
        updateDependentState();
        refreshMarkers();
    }

    public void addChangeListener(ActionListener listener) {
        allowInterAiCheckBox.addActionListener(listener);
        autoNotifyInboxCheckBox.addActionListener(listener);
        allowImportantMessagesCheckBox.addActionListener(listener);
    }

    public boolean isAllowInterAiSelected() {
        return allowInterAiCheckBox.isSelected();
    }

    public void setAllowInterAiSelected(boolean selected) {
        allowInterAiCheckBox.setSelected(selected);
        updateDependentState();
        refreshMarkers();
    }

    public boolean isAutoNotifySelected() {
        return autoNotifyInboxCheckBox.isSelected();
    }

    public void setAutoNotifySelected(boolean selected) {
        autoNotifyInboxCheckBox.setSelected(selected);
        refreshMarkers();
    }

    public boolean isAllowImportantSelected() {
        return allowImportantMessagesCheckBox.isSelected();
    }

    public void setAllowImportantSelected(boolean selected) {
        allowImportantMessagesCheckBox.setSelected(selected);
        refreshMarkers();
    }

    public void setAutoNotifyAccessory(Component accessory) {
        if (autoNotifyAccessory != null) {
            autoNotifyRow.remove(autoNotifyAccessory);
        }
        autoNotifyAccessory = accessory;
        if (accessory != null) {
            autoNotifyRow.add(accessory, BorderLayout.EAST);
        }
        updateDependentState();
        revalidate();
        repaint();
    }

    private void updateDependentState() {
        boolean enabled = allowInterAiCheckBox.isSelected();
        autoNotifyInboxCheckBox.setEnabled(enabled);
        allowImportantMessagesCheckBox.setEnabled(enabled);
        if (autoNotifyAccessory != null) {
            autoNotifyAccessory.setEnabled(enabled);
        }
    }

    /**
     * Re-applies the "Set from Global" / "Set on Session" marker to each control
     * based on whether its current value differs from the global default. No-op
     * outside session mode (the global options panel shows no markers).
     */
    private void refreshMarkers() {
        if (!sessionMode) {
            return;
        }
        allowInterAiCheckBox.setText(AccessControlLabelEnum.withSessionMarker(
                allowInterAiBaseLabel,
                allowInterAiCheckBox.isSelected() != PluginSettings.isAllowInterAiComms()));
        autoNotifyInboxCheckBox.setText(AccessControlLabelEnum.withSessionMarker(
                autoNotifyBaseLabel,
                autoNotifyInboxCheckBox.isSelected() != PluginSettings.isAutoNotifyInbox()));
        allowImportantMessagesCheckBox.setText(AccessControlLabelEnum.withSessionMarker(
                allowImportantBaseLabel,
                allowImportantMessagesCheckBox.isSelected() != PluginSettings.isAllowImportantMessages()));
    }

    private void addRow(Component component, int row, int leftInset) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4 + leftInset, 4, 4);
        add(component, c);
    }

}
